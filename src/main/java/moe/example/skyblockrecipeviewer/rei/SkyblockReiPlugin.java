package moe.example.skyblockrecipeviewer.rei;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.github.moulberry.repo.NEURepository;
import io.github.moulberry.repo.data.NEUCraftingRecipe;
import io.github.moulberry.repo.data.NEUIngredient;
import io.github.moulberry.repo.data.NEUItem;
import me.shedaniel.rei.api.client.entry.filtering.base.BasicFilteringRule;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.category.visibility.CategoryVisibilityPredicate;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.registry.display.visibility.DisplayVisibilityPredicate;
import me.shedaniel.rei.api.client.registry.entry.EntryRegistry;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import dev.architectury.event.EventResult;
import moe.example.skyblockrecipeviewer.SkyblockRecipeViewer;
import moe.example.skyblockrecipeviewer.repo.HypixelSkinManager;
import moe.example.skyblockrecipeviewer.repo.NeuRepoManager;
import moe.example.skyblockrecipeviewer.repo.NpcShopIndex;
import moe.example.skyblockrecipeviewer.repo.SkyblockItemResolver;
import moe.example.skyblockrecipeviewer.repo.SkyblockItemCache;
import moe.example.skyblockrecipeviewer.repo.SkullTextureCache;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

public class SkyblockReiPlugin implements REIClientPlugin {

	/**
	 * Tracks which NEURepository instance we've already live-pushed entries for, so the
	 * async completion callback (attached on every reload, see registerEntries) doesn't
	 * double-add the same items if the synchronous fast path already handled them. Static
	 * because REI plugin instances are recreated per reload, but the underlying data (and
	 * therefore "have we shown this yet") persists across that.
	 */
	private static volatile NEURepository lastLivePushedRepo = null;
	private static final Object pushLock = new Object();

	/**
	 * Set at the top of registerCategories(). NOT the first of the three callbacks REI calls on
	 * a plugin each reload - registerEntries() runs first (confirmed via REI's own reload log:
	 * "EntryRegistryImpl" phase completes before "CategoryRegistryImpl" starts) - but it is
	 * always called strictly *after* REI's own DefaultPlugin has finished registering its entry
	 * types, including "minecraft:item". That's the guarantee tryLivePush() actually needs (see
	 * its javadoc), so it still waits on this flag; registerEntries() just can't be the thing
	 * that sets it, and can't block long enough to risk registerCategories() never being reached
	 * before a reload gets cancelled.
	 */
	private static volatile boolean reiPluginsRegistered = false;

	private static volatile List<ItemStack> preparedItemStacks = List.of();
	private static volatile NEURepository preparedItemStacksRepo;
	private static volatile CompletableFuture<Void> bootstrapFuture;
	private static final ExecutorService ITEM_RESOLVE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "skyblock-rei-resolver");
		thread.setDaemon(true);
		return thread;
	});
	private static boolean livePushInProgress = false;
	private static List<SkyblockItemCache.CachedStack> pendingCachedStacks = List.of();
	private static int pendingCacheIndex = 0;
	private static final List<ItemStack> decodedCachedStacks = new ArrayList<>();
	private static boolean cacheReadComplete = false;
	private static NEURepository pendingCacheRepo;
	private static volatile NEURepository repoAwaitingReiRefresh;
	private static volatile NEURepository reiRefreshPendingRepo;
	private static volatile NEURepository reiRefreshRunningRepo;
	private static volatile boolean reiRefreshObserved;
	private static volatile int reiRefreshStartTick;
	private static int clientTick;

	private record CachedItemLoad(NEURepository repository,
			List<SkyblockItemCache.CachedStack> stacks) {
	}

	public static synchronized CompletableFuture<Void> startBootstrap() {
		if (bootstrapFuture != null) return bootstrapFuture;

		NeuRepoManager manager = NeuRepoManager.getInstance();
		CompletableFuture<Void> repoReady = manager.ensureLoaded().thenCompose(repository -> {
			if (repository == null) return CompletableFuture.completedFuture(null);
			return NpcShopIndex.ensureBuilt(manager, repository).handle((entries, error) -> null);
		});

		bootstrapFuture = CompletableFuture.allOf(repoReady, HypixelSkinManager.getInstance().ensureLoaded());
		bootstrapFuture.whenComplete((unused, error) -> {
			if (error != null) {
				SkyblockRecipeViewer.LOGGER.error("SkyBlock REI bootstrap failed.", error);
				return;
			}
			SkyblockRecipeViewer.LOGGER.info("SkyBlock REI data bootstrap completed.");
			CompletableFuture.supplyAsync(() -> {
				NEURepository repository = manager.getLoadedRepoOrNull();
				boolean current = repository != null
					&& !SkyblockItemCache.isStale(manager.getLoadedRevisionKey());
				return new CachedItemLoad(repository,
					current ? SkyblockItemCache.readCachedStacks() : List.of());
			}, ITEM_RESOLVE_EXECUTOR).thenAccept(cached ->
				net.minecraft.client.Minecraft.getInstance().execute(() -> {
					pendingCachedStacks = cached.stacks();
					pendingCacheRepo = cached.repository();
					pendingCacheIndex = 0;
					decodedCachedStacks.clear();
					cacheReadComplete = true;
					if (cached.stacks().isEmpty()) tryLivePush();
				}));
			net.minecraft.client.Minecraft.getInstance().execute(SkyblockReiPlugin::tryLivePush);
		});
		return bootstrapFuture;
	}

	@Override
	public void registerCategories(CategoryRegistry registry) {
		reiPluginsRegistered = true;
		if (reiRefreshRunningRepo != null) reiRefreshObserved = true;
		// Redundant safety net, not the primary call site anymore - registerEntries()
		// (which runs before this every reload) now calls this first, since it's the one
		// that actually needs SkyblockItemEntryDefinition.TYPE to exist already. Harmless to
		// call again here too: registerType() is a plain idempotent overwrite, not a
		// once-only static initializer anymore.
		SkyblockItemEntryDefinition.registerType();
		registry.add(new SkyblockCraftingCategory());
		registry.registerVisibilityPredicate(SkyblockCraftingCategory.hideVanillaCrafting());
		registry.add(new SkyblockReforgeCategory());
		registry.add(new SkyblockReforgeStoneCategory());
		registry.add(new SkyblockForgeCategory());
		registry.add(new SkyblockEssenceCategory());
		registry.add(new SkyblockPetUpgradeCategory());
		registry.add(new SkyblockMobDropCategory());
		registry.add(new SkyblockNpcShopCategory());
		registry.add(new SkyblockInfoCategory());

		Identifier skyblockerInfoId = Identifier.fromNamespaceAndPath("skyblocker", "skyblock_info");
		registry.registerVisibilityPredicate(new CategoryVisibilityPredicate() {
			@Override
			public double getPriority() { return 1000.0; }

			@Override
			public EventResult handleCategory(me.shedaniel.rei.api.client.registry.display.DisplayCategory<?> category) {
				return category.getCategoryIdentifier().getIdentifier().equals(skyblockerInfoId)
					? EventResult.interruptFalse()
					: EventResult.pass();
			}
		});
	}

	/**
	 * Hides every entry in REI's ingredient panel (the scrollable item list on the right) that
	 * isn't one of our own SkyBlock items. Without this, DefaultPlugin's vanilla registration
	 * (every plain Minecraft item/block) sits in that same panel right alongside the ~8000
	 * SkyBlock items this mod adds, which both roughly doubles the panel's size and makes
	 * searching for a SkyBlock item by name return a pile of unrelated vanilla results too.
	 *
	 * registerBasicEntryFiltering (not a one-shot removal in registerEntries) is REI's own
	 * intended hook for this - the Supplier below is re-evaluated by REI itself whenever it
	 * (re)filters the panel (a search, a config change, a real reload, ...), so this stays
	 * correct regardless of what order different plugins' registerEntries() callbacks happen
	 * to run in, or whether some other installed mod adds panel entries later than we do.
	 */
	@Override
	public void registerBasicEntryFiltering(BasicFilteringRule<?> rule) {
		rule.hide(() -> EntryRegistry.getInstance().getEntryStacks()
			.filter(stack -> !(stack.getValue() instanceof ItemStack item)
				|| !SkyblockItemEntryDefinition.hasSkyblockMarker(item))
			.collect(java.util.stream.Collectors.toList()));
	}

	/**
	 * Without this, REI only ever sees these items as ingredients buried inside a specific
	 * recipe's slot - it never gets a standalone list of "these SkyBlock items exist", which
	 * is what its search panel, "find usages"/"find recipes for" lookups, and general entry
	 * bookkeeping are built around.
	 *
	 * MUST NOT BLOCK. Confirmed from REI's own reload log that its phases run in this order:
	 * EntryRegistryImpl (this method) -> ... -> CategoryRegistryImpl (registerCategories(),
	 * which is what flips reiPluginsRegistered) - the opposite of what an earlier version of
	 * this file assumed. REI runs every plugin's registerEntries() back-to-back on one shared
	 * reload thread, and that whole reload is cancelled and restarted from scratch on a server
	 * disconnect ("Player quit, clearing reload tasks!" in REI's log). On Hypixel, joining
	 * silently hops the client between backend servers (limbo -> lobby -> ...), and each hop is
	 * a full disconnect+reconnect from REI's perspective - so this method previously blocking
	 * here for up to 30s (awaitRepoAndSkins) meant the reload got cancelled by the next hop
	 * before registerCategories() ever ran even once, and reiPluginsRegistered stayed false
	 * forever - which is why tryLivePush() below just looped "REI hasn't finished registering
	 * its own plugins yet" indefinitely and no items ever appeared on a straight Hypixel join,
	 * while singleplayer (no hopping, no cancellation) worked fine. Only ever add what's
	 * already sitting in memory *right now* (a cheap, instant check); everything else is left
	 * entirely to the async tryLivePush() path at the bottom, which isn't part of this
	 * cancellable reload pass at all and so isn't affected by any of this.
	 */
	@Override
	public void registerEntries(EntryRegistry registry) {
		// Must happen here, first, every single call - not just once via static init (see
		// registerType()'s own javadoc for why that was the actual bug), and not only in
		// registerCategories() (too late: registerEntries() runs before it every reload, and
		// this method needs SkyblockItemEntryDefinition.TYPE to already exist the moment it
		// tries to build any EntryStack with it, a few lines below).
		SkyblockItemEntryDefinition.registerType();

		startBootstrap();
		pushPreparedItemEntries(registry);
		SkyblockRecipeViewer.LOGGER.info(
			"Using only asynchronously prepared SkyBlock item data during REI initialization.");
	}

	/**
	 * Registers the immutable item snapshot prepared by startBootstrap(). This method never
	 * reads disk or decodes SNBT, so REI's registration callback remains non-blocking.
	 */
	private static boolean pushPreparedItemEntries(EntryRegistry registry) {
		// REI clears and rebuilds its EntryRegistry on every real plugin reload. Re-register
		// lightweight wrappers here while retaining the prepared ItemStacks across reloads.
		List<ItemStack> cached = preparedItemStacks;
		if (cached.isEmpty()) return false;

		List<EntryStack<ItemStack>> entries = new ArrayList<>(cached.size());
		for (ItemStack stack : cached) {
			if (!stack.isEmpty()) entries.add(EntryStack.of(SkyblockItemEntryDefinition.TYPE, stack));
		}
		registry.addEntries(entries);
		SkyblockRecipeViewer.LOGGER.info(
			"Registered {} prepared SkyBlock item entries.", entries.size());
		return true;
	}

	/**
	 * Pushes whatever repo/skin data is currently loaded into REI's live entry list, if it's
	 * newer than what was last pushed. Safe to call from anywhere, any number of times - the
	 * lastLivePushedRepo guard makes repeat/redundant calls a cheap no-op.
	 *
	 * Waits until REI has registered its entry types before pushing data, so item entries can
	 * be resolved safely.
	 */
	public static void tryLivePush() {
		net.minecraft.client.Minecraft.getInstance().execute(SkyblockReiPlugin::tryLivePushOnClient);
	}

	public static void processPendingItemCache() {
		if (pendingCachedStacks.isEmpty() || pendingCacheIndex >= pendingCachedStacks.size()) return;
		if (net.minecraft.client.Minecraft.getInstance().getConnection() == null || !reiPluginsRegistered) return;
		if (pendingCacheRepo != NeuRepoManager.getInstance().getLoadedRepoOrNull()) {
			pendingCachedStacks = List.of();
			decodedCachedStacks.clear();
			tryLivePush();
			return;
		}
		int end = Math.min(pendingCacheIndex + 100, pendingCachedStacks.size());
		for (; pendingCacheIndex < end; pendingCacheIndex++) {
			ItemStack stack = SkyblockItemCache.decodeCachedStack(pendingCachedStacks.get(pendingCacheIndex));
			if (!stack.isEmpty()) decodedCachedStacks.add(stack);
		}
		if (pendingCacheIndex < pendingCachedStacks.size()) return;
		preparedItemStacks = List.copyOf(decodedCachedStacks);
		preparedItemStacksRepo = pendingCacheRepo;
		pendingCachedStacks = List.of();
		pendingCacheRepo = null;
		decodedCachedStacks.clear();
		SkyblockRecipeViewer.LOGGER.info("Loaded {} SkyBlock item(s) from the local resolved-item cache.", preparedItemStacks.size());
		if (reiPluginsRegistered && !preparedItemStacks.isEmpty()) {
			SkyblockItemEntryDefinition.registerType();
			EntryRegistry registry = EntryRegistry.getInstance();
			pushPreparedItemEntries(registry);
			registry.refilter();
		}
		tryLivePush();
	}

	public static void onRepositoryReloaded() {
		net.minecraft.client.Minecraft.getInstance().execute(() -> {
			NEURepository repository = NeuRepoManager.getInstance().getLoadedRepoOrNull();
			if (repository == null) return;
			SkyblockItemResolver.invalidateCache(repository);
			pendingCachedStacks = List.of();
			pendingCacheRepo = null;
			decodedCachedStacks.clear();
			cacheReadComplete = true;
			repoAwaitingReiRefresh = repository;
			tryLivePushOnClient();
		});
	}

	private static void tryLivePushOnClient() {
		// Bail out of the retry chain once we're no longer connected to a server (quit back to
		// the main menu, or disconnected). Without this, every join that happens to land before
		// REI's own plugin reload finishes (e.g. REI cancels and restarts that reload on every
		// disconnect - see "Player quit, clearing reload tasks!" - so a run of quick reconnects
		// can repeatedly outrace it) leaves its 2s retry loop running forever in the background,
		// and a session with several such joins ends up with several of these dangling loops
		// stacked on top of each other, spamming the log indefinitely.
		if (net.minecraft.client.Minecraft.getInstance().getConnection() == null) {
			SkyblockRecipeViewer.LOGGER.info(
				"No longer connected to a server - abandoning this live item push attempt.");
			return;
		}
		// Whether REI's own plugin machinery has ever actually run this session comes first,
		// and is checked independently of isStale() below - they answer different questions.
		// isStale() only tells us our *data* hasn't changed since we last wrote the cache; it
		// says nothing about whether REI itself has ever registered a single category/entry/
		// display this session. On Hypixel specifically (see forceReiReloadIfNeeded's
		// javadoc), that real reload may simply never have happened yet at all - and if the
		// repo also happens to be up to date (the common case on a second+ join, or any join
		// after the very first one this process has seen), isStale() alone would report
		// "nothing to do" and this method would return without ever forcing that reload,
		// leaving REI's category tab and crafting-recipe displays permanently absent for the
		// rest of the session even though the disk-cache fast path in registerEntries() shows
		// *items* just fine - exactly the "REI hasn't attempted to load this time" symptom.
		if (!reiPluginsRegistered) {
			SkyblockRecipeViewer.LOGGER.info(
				"REI hasn't finished registering its plugins yet - deferring the live item push by 2s.");
			CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS).execute(SkyblockReiPlugin::tryLivePush);
			return;
		}
		if (!cacheReadComplete) return;
		NeuRepoManager manager = NeuRepoManager.getInstance();
		NEURepository loaded = manager.getLoadedRepoOrNull();
		if (loaded == null) return;
		synchronized (pushLock) {
			if (livePushInProgress || (loaded == preparedItemStacksRepo && !preparedItemStacks.isEmpty())
				|| (loaded == lastLivePushedRepo && !preparedItemStacks.isEmpty())) return;
			livePushInProgress = true;
		}
		CompletableFuture.supplyAsync(() -> {
			SkyblockItemResolver.invalidateCacheIfRepoChanged(loaded);
			return List.copyOf(resolveAllItemStacks(manager));
		}, ITEM_RESOLVE_EXECUTOR).whenComplete((resolvedStacks, error) ->
			net.minecraft.client.Minecraft.getInstance().execute(() -> finishLivePush(loaded, resolvedStacks, error)));
	}

	private static void finishLivePush(NEURepository loaded, List<ItemStack> resolvedStacks, Throwable error) {
		synchronized (pushLock) {
			livePushInProgress = false;
		}
		if (error != null) {
			SkyblockRecipeViewer.LOGGER.error("Preparing SkyBlock REI entries failed.", error);
			return;
		}
		if (NeuRepoManager.getInstance().getLoadedRepoOrNull() != loaded) {
			SkyblockItemResolver.invalidateCache(
				NeuRepoManager.getInstance().getLoadedRepoOrNull());
			tryLivePush();
			return;
		}
		if (net.minecraft.client.Minecraft.getInstance().getConnection() == null || !reiPluginsRegistered) return;
		preparedItemStacks = resolvedStacks;
		preparedItemStacksRepo = loaded;
		boolean refreshRecipes = repoAwaitingReiRefresh == loaded;
		if (refreshRecipes) {
			repoAwaitingReiRefresh = null;
			reiRefreshPendingRepo = loaded;
		}
		SkyblockItemEntryDefinition.registerType();
		if (!refreshRecipes) {
			EntryRegistry registry = EntryRegistry.getInstance();
			List<EntryStack<ItemStack>> entries = new ArrayList<>(resolvedStacks.size());
			for (ItemStack stack : resolvedStacks) {
				if (!stack.isEmpty()) entries.add(EntryStack.of(SkyblockItemEntryDefinition.TYPE, stack));
			}
			registry.addEntries(entries);
			registry.refilter();
			SkyblockRecipeViewer.LOGGER.info(
				"Registered {} SkyBlock item entries in REI as one batch.", entries.size());
		}
		synchronized (pushLock) {
			lastLivePushedRepo = loaded;
		}
		String repoSha = SkyblockItemCache.currentRepoSha();
		Map<String, ItemStack> snapshot = SkyblockItemResolver.snapshotResolvedCache();
		String cacheJson = SkyblockItemCache.serialize(repoSha,
			NeuRepoManager.getInstance().getLoadedRevisionKey(), snapshot);
		if (cacheJson != null) {
			CompletableFuture.runAsync(() -> SkyblockItemCache.writeSerialized(cacheJson), ITEM_RESOLVE_EXECUTOR);
		}
		CompletableFuture.runAsync(SkullTextureCache::flush, ITEM_RESOLVE_EXECUTOR);
	}

	public static void processPendingReiRefresh() {
		clientTick++;
		if (reiRefreshRunningRepo != null) {
			if (isReiReloading()) {
				reiRefreshObserved = true;
				return;
			}
			if (!reiRefreshObserved && clientTick - reiRefreshStartTick < 2) return;
			NEURepository refreshed = reiRefreshRunningRepo;
			reiRefreshRunningRepo = null;
			SkyblockRecipeViewer.LOGGER.info("REI refresh completed for repository revision {}.",
				NeuRepoManager.getInstance().getLoadedRevisionKey());
			if (reiRefreshPendingRepo == refreshed) reiRefreshPendingRepo = null;
		}

		NEURepository pending = reiRefreshPendingRepo;
		if (pending == null || pending != NeuRepoManager.getInstance().getLoadedRepoOrNull()) return;
		var minecraft = net.minecraft.client.Minecraft.getInstance();
		if (minecraft.level == null || !reiPluginsRegistered || isReiReloading()) return;

		reiRefreshRunningRepo = pending;
		reiRefreshObserved = false;
		reiRefreshStartTick = clientTick;
		SkyblockRecipeViewer.LOGGER.info("REI refresh requested after repository indexes were rebuilt.");
		// A null stage asks REI to run START and END inside one managed reload task.
		me.shedaniel.rei.RoughlyEnoughItemsCoreClient.reloadPlugins(null,
			null, minecraft.level.registryAccess());
	}

	private static boolean isReiReloading() {
		return me.shedaniel.rei.api.common.plugins.PluginManager.areAnyReloading()
			|| me.shedaniel.rei.impl.common.plugins.ReloadManagerImpl.countRunningReloadTasks() > 0;
	}

	/**
	 * The actual CPU-heavy part of resolving every SkyBlock item: SNBT parsing +
	 * DataFixerUpper upgrade + ItemStack.CODEC.parse + skull profile resolution, per item,
	 * over ~8000 items on a cold cache. Pure data work - nothing in here touches REI's
	 * registries, rendering state, or anything else that requires the main render thread, so
	 * callers are free to run this on a background thread. That distinction is why this is
	 * split out on its own: it used to be inlined directly inside pushEntries(), which
	 * tryLivePush() ran via Minecraft.execute(...) - meaning this entire multi-thousand-item
	 * loop ran synchronously on the thread that draws every frame, freezing the game for
	 * however long a cold resolve took. Only the (cheap) registry.addEntries/refilter calls
	 * actually need the main thread.
	 */
	private static List<ItemStack> resolveAllItemStacks(NeuRepoManager manager) {
		List<ItemStack> stacks = new ArrayList<>();
		for (NEUItem item : manager.getAllItems()) {
			ItemStack stack = SkyblockItemResolver.resolveItemStack(item, item.getSkyblockItemId());
			if (stack.isEmpty()) continue;
			stacks.add(stack);
		}
		return stacks;
	}

	@Override
	public void registerScreens(me.shedaniel.rei.api.client.registry.screen.ScreenRegistry registry) {
		registry.registerFocusedStack(new SkyblockFocusedStackProvider());
	}

	@Override
	public void registerDisplays(DisplayRegistry registry) {
		// Must happen unconditionally, before the early-return below: this generator checks
		// NeuRepoManager's *current* state on every call (see SkyblockCraftingDisplayGenerator),
		// not a snapshot from this reload pass, so it needs to be registered now regardless of
		// whether the repo happens to be ready yet - it's what answers "recipes for X"/
		// "usages of X" lookups later, e.g. after joining Hypixel, when no further reload
		// happens to re-run this method at all.
		registry.registerDisplayGenerator(SkyblockCraftingCategory.ID, SkyblockCraftingDisplayGenerator.INSTANCE);
		registry.registerDisplayGenerator(SkyblockReforgeCategory.ID, SkyblockReforgeDisplayGenerator.INSTANCE);
		registry.registerDisplayGenerator(SkyblockReforgeStoneCategory.ID, SkyblockReforgeStoneDisplayGenerator.INSTANCE);
		registry.registerDisplayGenerator(SkyblockForgeCategory.ID, SkyblockForgeDisplayGenerator.INSTANCE);
		registry.registerDisplayGenerator(SkyblockEssenceCategory.ID, SkyblockEssenceDisplayGenerator.INSTANCE);
		registry.registerDisplayGenerator(SkyblockPetUpgradeCategory.ID, SkyblockPetUpgradeDisplayGenerator.INSTANCE);
		registry.registerDisplayGenerator(SkyblockMobDropCategory.ID, SkyblockMobDropDisplayGenerator.INSTANCE);
		registry.registerDisplayGenerator(SkyblockNpcShopCategory.ID, SkyblockNpcShopDisplayGenerator.INSTANCE);
		registry.registerDisplayGenerator(SkyblockInfoCategory.ID, SkyblockInfoDisplayGenerator.INSTANCE);

		Identifier skyblockerInfoId = Identifier.fromNamespaceAndPath("skyblocker", "skyblock_info");
		registry.registerVisibilityPredicate(new DisplayVisibilityPredicate() {
			@Override
			public double getPriority() { return 1000.0; }

			@Override
			public EventResult handleDisplay(me.shedaniel.rei.api.client.registry.display.DisplayCategory<?> category, me.shedaniel.rei.api.common.display.Display display) {
				return category.getCategoryIdentifier().getIdentifier().equals(skyblockerInfoId)
					? EventResult.interruptFalse()
					: EventResult.pass();
			}
		});

		NeuRepoManager manager = NeuRepoManager.getInstance();
		NEURepository repository = manager.getLoadedRepoOrNull();
		if (repository != null) {
			registerFromRepo(registry, manager, repository);
		} else {
			SkyblockRecipeViewer.LOGGER.info(
				"SkyBlock item repo is not loaded yet; dynamic display generators will use it "
					+ "as soon as the background repo load completes.");
		}
	}

	/**
	 * Used to eagerly register every crafting/reforge/forge/essence/pet-upgrade/mob-drop
	 * display directly via registry.add() here, in addition to each category's
	 * DynamicDisplayGenerator (registered above) separately answering the exact same
	 * "recipes for X"/"usages of X" lookups on demand. Both paths independently produced the
	 * same displays, and REI has no way to know two displays from two different registration
	 * paths are "the same recipe" - it just shows everything it's been given - so every recipe
	 * was appearing twice (once from this eager pass, once from the generator) whenever both
	 * had run, which in practice was almost always (this only stayed unnoticed on the very
	 * first Hypixel-join case the generators were built to fix, where this eager pass hadn't
	 * managed to run at all yet).
	 *
	 * Now that each generator's generate() (used for plain category-tab browsing, with no
	 * specific item focused) also returns the full list rather than nothing, the generators
	 * fully cover every case this eager pass used to - so this pass is just cache invalidation
	 * now, with no registration of its own left to do.
	 */
	private void registerFromRepo(DisplayRegistry registry, NeuRepoManager manager, NEURepository repository) {
		SkyblockItemResolver.invalidateCacheIfRepoChanged(repository);
	}

	static SkyblockCraftingDisplay toDisplay(NEURepository repository, NEUCraftingRecipe recipe) {
		// getInputs() returns a fixed-size NEUIngredient[9] (3x3 grid), not a List -
		// confirmed against Firmament's own SBCraftingRecipe.kt, the reference consumer
		// of this same parser library.
		NEUIngredient[] inputs = recipe.getInputs();
		if (inputs == null) return null;

		List<EntryIngredient> inputEntries = new ArrayList<>(9);
		for (int i = 0; i < 9; i++) {
			NEUIngredient ingredient = i < inputs.length ? inputs[i] : null;
			if (SkyblockItemResolver.isEmptySlot(ingredient)) {
				inputEntries.add(EntryIngredient.empty());
				continue;
			}
			ItemStack stack = SkyblockItemResolver.resolve(repository, ingredient);
			if (stack.isEmpty()) {
				inputEntries.add(EntryIngredient.empty());
			} else {
				inputEntries.add(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, stack)));
			}
		}

		NEUIngredient output = recipe.getOutput();
		List<EntryIngredient> outputEntries = new ArrayList<>(1);
		if (output != null) {
			ItemStack outStack = SkyblockItemResolver.resolve(repository, output);
			if (!outStack.isEmpty()) {
				outputEntries.add(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, outStack)));
			}
		}
		if (outputEntries.isEmpty()) return null;

		return new SkyblockCraftingDisplay(inputEntries, outputEntries);
	}

	static SkyblockReforgeDisplay toReforgeDisplay(NEURepository repository,
			moe.example.skyblockrecipeviewer.repo.reforge.ReforgeData reforge) {
		return toReforgeDisplay(repository, reforge, List.of());
	}

	/**
	 * @param highlightRarities the specific rarity tiers to show for this lookup (e.g. an
	 *                          item's natural rarity plus its recombobulated one) - see
	 *                          {@link SkyblockReforgeDisplay#getHighlightRarities}. Empty when
	 *                          browsing the reforge category generally, with no specific item
	 *                          in context.
	 */
	static SkyblockReforgeDisplay toReforgeDisplay(NEURepository repository,
			moe.example.skyblockrecipeviewer.repo.reforge.ReforgeData reforge, List<String> highlightRarities) {
		List<EntryIngredient> inputs = new ArrayList<>(1);
		if (reforge.reforgeStoneId() != null) {
			ItemStack stoneStack = SkyblockItemResolver.resolve(repository,
				NEUIngredient.fromString(reforge.reforgeStoneId() + ":1"));
			if (!stoneStack.isEmpty()) {
				inputs.add(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, stoneStack)));
			}
		}
		return new SkyblockReforgeDisplay(reforge, inputs, highlightRarities);
	}

	/**
	 * Stone-reforge counterpart of {@link #toReforgeDisplay} - returns null (skip this reforge)
	 * if its stone item can't actually be resolved to something displayable, since
	 * {@link SkyblockReforgeStoneCategory} has nothing to show without one (unlike the free-
	 * reforge category, which always has a generic Anvil icon to fall back on).
	 */
	static SkyblockReforgeStoneDisplay toReforgeStoneDisplay(NEURepository repository,
			moe.example.skyblockrecipeviewer.repo.reforge.ReforgeData reforge, List<String> highlightRarities) {
		if (reforge.reforgeStoneId() == null) return null;
		ItemStack stoneStack = SkyblockItemResolver.resolve(repository,
			NEUIngredient.fromString(reforge.reforgeStoneId() + ":1"));
		if (stoneStack.isEmpty()) return null;
		List<EntryIngredient> inputs = List.of(
			EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, stoneStack)));
		return new SkyblockReforgeStoneDisplay(reforge, inputs, highlightRarities);
	}

	static SkyblockForgeDisplay toForgeDisplay(NEURepository repository, io.github.moulberry.repo.data.NEUForgeRecipe recipe) {
		List<NEUIngredient> rawInputs = recipe.getInputs();
		if (rawInputs == null) return null;
		List<EntryIngredient> inputs = new ArrayList<>(rawInputs.size());
		for (NEUIngredient ingredient : rawInputs) {
			if (SkyblockItemResolver.isEmptySlot(ingredient)) continue;
			ItemStack stack = SkyblockItemResolver.resolve(repository, ingredient);
			if (!stack.isEmpty()) {
				inputs.add(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, stack)));
			}
		}
		if (inputs.isEmpty()) return null;

		NEUIngredient output = recipe.getOutputStack();
		if (output == null) return null;
		ItemStack outStack = SkyblockItemResolver.resolve(repository, output);
		if (outStack.isEmpty()) return null;

		return new SkyblockForgeDisplay(recipe, inputs,
			List.of(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, outStack))));
	}

	static SkyblockEssenceDisplay toEssenceDisplay(NEURepository repository,
			moe.example.skyblockrecipeviewer.repo.essence.EssenceUpgradeRecipe recipe) {
		ItemStack itemStack = SkyblockItemResolver.resolve(repository,
			NEUIngredient.fromString(recipe.itemSkyblockId() + ":1"));
		if (itemStack.isEmpty()) return null;
		EntryIngredient itemEntry = EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, itemStack));

		List<EntryIngredient> inputs = new ArrayList<>();
		inputs.add(itemEntry);
		ItemStack essenceStack = SkyblockItemResolver.resolve(repository,
			NEUIngredient.fromString(recipe.essenceSkyblockId() + ":" + recipe.essenceCost()));
		if (!essenceStack.isEmpty()) {
			inputs.add(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, essenceStack)));
		}
		for (String extraItemId : recipe.extraItemIds()) {
			NEUIngredient extraIngredient = NEUIngredient.fromString(extraItemId);
			if (SkyblockItemResolver.isEmptySlot(extraIngredient)) continue;
			ItemStack extraStack = SkyblockItemResolver.resolve(repository, extraIngredient);
			if (!extraStack.isEmpty()) {
				inputs.add(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, extraStack)));
			}
		}

		return new SkyblockEssenceDisplay(recipe, inputs, List.of(itemEntry));
	}

	static SkyblockPetUpgradeDisplay toPetUpgradeDisplay(NEURepository repository,
			io.github.moulberry.repo.data.NEUKatUpgradeRecipe recipe) {
		NEUIngredient input = recipe.getInput();
		NEUIngredient output = recipe.getOutput();
		if (input == null || output == null) return null;

		ItemStack inputStack = SkyblockItemResolver.resolve(repository, input);
		ItemStack outputStack = SkyblockItemResolver.resolve(repository, output);
		if (inputStack.isEmpty() || outputStack.isEmpty()) return null;

		List<EntryIngredient> inputs = new ArrayList<>();
		inputs.add(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, inputStack)));
		List<NEUIngredient> costItems = recipe.getItems();
		if (costItems != null) {
			for (NEUIngredient costItem : costItems) {
				if (SkyblockItemResolver.isEmptySlot(costItem)) continue;
				ItemStack costStack = SkyblockItemResolver.resolve(repository, costItem);
				if (!costStack.isEmpty()) {
					inputs.add(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, costStack)));
				}
			}
		}

		return new SkyblockPetUpgradeDisplay(recipe, inputs,
			List.of(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, outputStack))));
	}

	static SkyblockMobDropDisplay toMobDropDisplay(NEURepository repository,
			io.github.moulberry.repo.data.NEUMobDropRecipe recipe) {
		if (recipe.getDrops() == null) return null;
		List<EntryIngredient> outputs = new ArrayList<>();
		List<String> chances = new ArrayList<>();
		List<List<String>> extras = new ArrayList<>();
		for (io.github.moulberry.repo.data.NEUMobDropRecipe.Drop drop : recipe.getDrops()) {
			NEUIngredient dropItem = drop.getDropItem();
			if (dropItem == null || SkyblockItemResolver.isEmptySlot(dropItem)) continue;
			ItemStack stack = SkyblockItemResolver.resolve(repository, dropItem);
			if (stack.isEmpty()) continue;
			String chance = drop.getChance();
			List<String> extra = drop.getExtra();
			// A drop with no chance at all is a "Hunting Drop" - obtained through some special
			// condition (e.g. "Catch with Pocket Black Hole") rather than a raw probability
			// roll, described in "extra" instead - confirmed against a real repo mob file
			// where exactly this pairing shows up (an attribute shard drop with no "chance" key
			// but a populated "extra" list). Baked directly into the item's own lore (same
			// proven-reliable technique already used for reforge stones) rather than via REI's
			// Widgets.withTooltip, whose append-vs-replace semantics against a Slot's own
			// existing item tooltip couldn't be confirmed from here.
			if (chance == null && extra != null && !extra.isEmpty()) {
				stack = withExtraLore(stack, "Hunting Drop", extra);
			} else if (chance != null) {
				// The chance is also drawn directly on the slot as a corner label (see
				// SkyblockMobDropCategory.DropChanceSlot) - it's baked into the lore here too,
				// on this per-display copy of the stack only (so it doesn't leak into every
				// other place this item shows up in REI), so it's still readable on hover even
				// once the on-slot label has to shrink/overlap for a long value like
				// "0.00004%".
				stack = withExtraLore(stack, null, List.of("Drop Chance: " + chance));
			}
			outputs.add(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, stack)));
			// Confirmed against a real repo mob file: a raw string like "100%"/"0.00004%",
			// already formatted for display - no numeric parsing needed. null (not every drop
			// has a chance at all - some are obtained through a special condition instead of a
			// raw probability roll, described in getExtra() instead - see SkyblockMobDropDisplay's
			// docs) just means no corner label for that drop.
			chances.add(chance);
			extras.add(extra);
		}
		if (outputs.isEmpty()) return null;
		return new SkyblockMobDropDisplay(recipe, outputs, chances, extras);
	}

	/**
	 * Appends extra lore lines to a copy of {@code stack}'s existing lore (same
	 * DataComponents.LORE technique already proven out for reforge stones). {@code heading}, if
	 * non-null, is added first as a bold gold line (e.g. "Hunting Drop") with a blank line
	 * before it; pass null to just append {@code lines} directly with no heading (used for the
	 * plain "Drop Chance: X%" line).
	 */
	private static ItemStack withExtraLore(ItemStack stack, String heading, List<String> lines) {
		List<Component> toAdd = new ArrayList<>();
		toAdd.add(Component.empty());
		if (heading != null) {
			toAdd.add(Component.literal(heading)
				.withStyle(s -> s.withColor(net.minecraft.ChatFormatting.GOLD).withBold(true)));
		}
		for (String line : lines) {
			toAdd.add(Component.literal(line).withStyle(s -> s.withColor(net.minecraft.ChatFormatting.GRAY)));
		}
		net.minecraft.world.item.component.ItemLore existing = stack.get(net.minecraft.core.component.DataComponents.LORE);
		List<Component> combined = new ArrayList<>();
		if (existing != null) combined.addAll(existing.lines());
		combined.addAll(toAdd);
		stack.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(combined));
		return stack;
	}
	@Override
	public double getPriority() {
		// Skyblocker uses -50, so our focused-stack provider is registered first.
		return 100.0;
	}

}
