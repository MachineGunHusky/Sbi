package moe.example.skyblockrecipeviewer.repo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import io.github.moulberry.repo.NEURepository;
import io.github.moulberry.repo.data.NEUCraftingRecipe;
import io.github.moulberry.repo.data.NEUForgeRecipe;
import io.github.moulberry.repo.data.NEUKatUpgradeRecipe;
import io.github.moulberry.repo.data.NEUMobDropRecipe;
import moe.example.skyblockrecipeviewer.repo.essence.EssenceStore;
import moe.example.skyblockrecipeviewer.repo.reforge.ReforgeStore;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class NeuRepoManager {
	private static final Logger LOGGER = LogManager.getLogger("SkyblockRecipeViewer/Repo");
	private static final long STABILITY_INTERVAL_MILLIS = 1_000;
	private static final int MAX_STABILITY_OBSERVATIONS = 5;
	private static final List<String> REQUIRED_REPO_FILES = List.of(
		"constants/abiphone.json",
		"constants/bonuses.json",
		"constants/parents.json",
		"constants/enchants.json",
		"constants/essencecosts.json",
		"constants/fairy_souls.json",
		"constants/misc.json",
		"constants/leveling.json",
		"constants/pets.json",
		"constants/petnums.json");

	private final RepoDownloader downloader;
	private final long stabilityIntervalMillis;
	private final AtomicReference<RepositoryData> loadedData = new AtomicReference<>();
	private volatile Consumer<String> statusListener = message -> {};
	private boolean fetchAnnounced;
	private boolean failureAnnounced;
	private CompletableFuture<NEURepository> loadInFlight;
	private CompletableFuture<Boolean> changeCheckInFlight;
	private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "skyblock-repo-loader");
		t.setDaemon(true);
		return t;
	});

	private NeuRepoManager() {
		this(FabricLoader.getInstance().getConfigDir().resolve("skyblockrecipeviewer").resolve("NEUrepo"),
			STABILITY_INTERVAL_MILLIS);
	}

	NeuRepoManager(Path repoPath, long stabilityIntervalMillis) {
		this(new RepoDownloader(repoPath, LOGGER), stabilityIntervalMillis);
	}

	NeuRepoManager(RepoDownloader downloader, long stabilityIntervalMillis) {
		this.downloader = downloader;
		this.stabilityIntervalMillis = stabilityIntervalMillis;
	}

	public static NeuRepoManager getInstance() {
		return InstanceHolder.INSTANCE;
	}

	private static final class InstanceHolder {
		private static final NeuRepoManager INSTANCE = new NeuRepoManager();
	}

	/**
	 * The owned repo root, config/skyblockrecipeviewer/NEUrepo - e.g.
	 * {@code getRepoDir().resolve("constants/petnums.json")}. The
	 * whole NotEnoughUpdates-REPO archive lands here (see RepoDownloader), not just the
	 * items/ folder NEURepository itself parses, so anything under constants/ is already on
	 * disk here the moment the repo has been downloaded once, with nothing extra to fetch.
	 */
	public Path getRepoDir() {
		return downloader.getRepoDir();
	}

	public void setStatusListener(Consumer<String> listener) {
		statusListener = Objects.requireNonNull(listener);
	}

	private void announceFetching() {
		if (fetchAnnounced) return;
		fetchAnnounced = true;
		statusListener.accept("Fetching new repo...");
	}

	private void announceFailure() {
		if (failureAnnounced) return;
		failureAnnounced = true;
		statusListener.accept(loadedData.get() == null
			? "Repo unavailable - see the log for details."
			: "Repo update failed - see the log for details.");
	}

	/**
	 * Same as {@link #loadAsync()}, but skips hitting disk again if we already have a
	 * successfully loaded repo in memory. REI calls registerEntries/registerDisplays/etc as
	 * separate callbacks within the same reload pass; without this, each one independently
	 * re-parses the whole repo from disk, which on a slow first launch can be enough to blow
	 * past a caller's timeout even though the data was already available a moment earlier.
	 */
	public synchronized CompletableFuture<NEURepository> ensureLoaded() {
		RepositoryData cached = loadedData.get();
		if (cached != null && downloader.ensureRepoDirectory()
				&& Files.isDirectory(getRepoDir().resolve("items"))
				&& Files.isDirectory(getRepoDir().resolve("constants"))) {
			return CompletableFuture.completedFuture(cached.repository());
		}
		return loadAsync();
	}

	public synchronized CompletableFuture<NEURepository> loadAsync() {
		if (loadInFlight != null) return loadInFlight;
		CompletableFuture<NEURepository> created =
			CompletableFuture.supplyAsync(this::loadFromDiskWithRetries, executor);
		loadInFlight = created;
		created.whenComplete((repository, error) -> {
			synchronized (NeuRepoManager.this) {
				if (loadInFlight == created) loadInFlight = null;
			}
		});
		return created;
	}

	/**
	 * Checks the current owned repo contents. The file walk runs on the
	 * repo executor and is safe to call periodically from a client tick.
	 */
	public synchronized CompletableFuture<Boolean> checkForDiskChanges() {
		if (changeCheckInFlight != null) return changeCheckInFlight;
		CompletableFuture<Boolean> created = CompletableFuture.supplyAsync(() -> {
			RepoRevision diskRevision = readDiskRevisionQuietly();
			RepositoryData current = loadedData.get();
			String loadedRevision = current == null ? null : current.revisionKey();
			if (diskRevision == null) {
				announceFailure();
				return false;
			}
			if (Objects.equals(diskRevision.cacheKey(), loadedRevision)) return false;

			LOGGER.info("NEU repo change detected on disk ({} -> {}).",
				loadedRevision == null ? "none" : loadedRevision, describeRevision(diskRevision));
			NEURepository previous = current == null ? null : current.repository();
			NEURepository reloaded = loadFromDiskWithRetries();
			return reloaded != null && reloaded != previous;
		}, executor);
		changeCheckInFlight = created;
		created.whenComplete((changed, error) -> {
			synchronized (NeuRepoManager.this) {
				if (changeCheckInFlight == created) changeCheckInFlight = null;
			}
		});
		return created;
	}

	/**
	 * Compares the local repo against GitHub's latest commit and downloads an update if
	 * there's a mismatch; otherwise leaves the on-disk copy untouched. Meant to be called
	 * once per connection to a Hypixel server, not on every launch.
	 *
	 * REI does not cleanly support re-registering displays mid-session (it logs a "runtime
	 * DisplayRegistry modification" warning if you try), so an update here only takes full
	 * effect after the next REI plugin reload or game restart - the returned future
	 * indicates whether that's now the case, so the caller can notify the player.
	 *
	 * @return a future completing with true if an update was downloaded, false if already
	 *         current or the check failed (e.g. offline)
	 */
	public CompletableFuture<Boolean> checkForUpdatesOnJoin() {
		return CompletableFuture.supplyAsync(() -> {
			if (!downloader.ensureRepoDirectory()) {
				announceFailure();
				return false;
			}
			boolean incomplete = !hasCompleteRepository();
			if (incomplete) announceFetching();
			var newSha = downloader.checkForUpdate(incomplete);
			if (newSha.isEmpty()) {
				if (incomplete) announceFailure();
				return false;
			}
			announceFetching();
			boolean applied = downloader.downloadAndApply(newSha.get());
			if (!applied) {
				announceFailure();
				return false;
			}
			LOGGER.info("NEU repo change detected after downloading {}.", newSha.get());
			RepositoryData current = loadedData.get();
			NEURepository previous = current == null ? null : current.repository();
			NEURepository reloaded = loadFromDiskWithRetries();
			return reloaded != null && reloaded != previous;
		}, executor);
	}

	/**
	 * Waits for two identical, complete snapshots before parsing. A failed parse never replaces
	 * the current repository or invalidates its derived caches.
	 */
	private NEURepository loadFromDiskWithRetries() {
		Path repoDir = downloader.getRepoDir();
		RepositoryData previous = loadedData.get();
		if (!downloader.ensureRepoDirectory()) {
			announceFailure();
			return previous == null ? null : previous.repository();
		}
		if (!hasCompleteRepository()) {
			LOGGER.info("NEU repository at {} is missing or incomplete; downloading repository data.", repoDir);
			announceFetching();
			var sha = downloader.checkForUpdate(true);
			if (sha.isEmpty() || !downloader.downloadAndApply(sha.get())) {
				announceFailure();
				return previous == null ? null : previous.repository();
			}
		}
		RepoRevision stableRevision = awaitStableRevision();
		if (stableRevision == null) {
			announceFailure();
			return previous == null ? null : previous.repository();
		}

		NEURepository repository = NEURepository.of(repoDir);
		RepositoryData replacement;
		try {
			repository.reload();
			RepoRevision after = readCompleteDiskRevision();
			if (!Objects.equals(stableRevision, after)) {
				LOGGER.warn("NEU repo load aborted because the snapshot changed while it was parsed "
					+ "({} -> {}). Keeping the previous repository.",
					describeRevision(stableRevision), describeRevision(after));
				announceFailure();
				return previous == null ? null : previous.repository();
			}

			replacement = buildRepositoryData(repository, after);
		} catch (Exception e) {
			LOGGER.warn("NEU repo load failed or was aborted because the snapshot may be incomplete or "
				+ "mid-update. Keeping the previous valid repository at {}.",
				previous == null ? "none" : previous.revisionKey(), e);
			announceFailure();
			return previous == null ? null : previous.repository();
		}

		int oldForgeCount = previous == null ? 0 : previous.forgeRecipes().size();
		loadedData.set(replacement);
		LOGGER.info("Successfully replaced the active NEU repository at revision {} ({} items).",
			replacement.revisionKey(), repository.getItems().getItems().size());

		try {
			invalidateDerivedCaches(repository);
			LOGGER.info("Invalidated caches and indexes derived from the previous NEU repository.");
			ReforgeStore.getInstance().reload(repoDir);
			EssenceStore.getInstance().reload(repoDir);
			NpcShopIndex.ensureBuilt(this, repository).handle((entries, error) -> {
				if (error != null) LOGGER.warn("Failed to rebuild the NPC shop index.", error);
				return entries;
			}).join();
		} catch (Exception e) {
			LOGGER.error("The NEU repository was replaced, but a derived cache rebuild failed.", e);
			announceFailure();
			return repository;
		}
		LOGGER.info("Rebuilt repository indexes ({} crafting, {} Forge, {} mob-drop, {} Kat upgrade, "
			+ "{} NPC shop). Forge recipe count: {} -> {}.", replacement.craftingRecipes().size(),
			replacement.forgeRecipes().size(), replacement.mobDropRecipes().size(),
			replacement.petUpgradeRecipes().size(), NpcShopIndex.getEntries(this).size(),
			oldForgeCount, replacement.forgeRecipes().size());
		if (previous == null || fetchAnnounced || failureAnnounced) {
			statusListener.accept("Repo loaded - refreshing items and recipes.");
		}
		fetchAnnounced = false;
		failureAnnounced = false;
		return repository;
	}

	private RepoRevision awaitStableRevision() {
		RepoRevision previous = null;
		for (int observation = 1; observation <= MAX_STABILITY_OBSERVATIONS; observation++) {
			try {
				RepoRevision current = readCompleteDiskRevision();
				if (Objects.equals(previous, current)) return current;
				previous = current;
				LOGGER.info("Waiting for stable NEU repo snapshot at {} (observation {}/{}).",
					describeRevision(current), observation, MAX_STABILITY_OBSERVATIONS);
			} catch (Exception e) {
				previous = null;
				LOGGER.info("Waiting for stable NEU repo snapshot: {} (observation {}/{}).",
					e.getMessage(), observation, MAX_STABILITY_OBSERVATIONS);
			}
			if (observation < MAX_STABILITY_OBSERVATIONS && !pauseForStability()) return null;
		}
		LOGGER.warn("NEU repo load aborted because no complete stable snapshot was observed. "
			+ "Keeping the previous repository.");
		return null;
	}

	private boolean pauseForStability() {
		try {
			Thread.sleep(stabilityIntervalMillis);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	public NEURepository getLoadedRepoOrNull() {
		RepositoryData data = loadedData.get();
		return data == null ? null : data.repository();
	}

	public String getLoadedRevisionKey() {
		RepositoryData data = loadedData.get();
		return data == null ? null : data.revisionKey();
	}

	private void invalidateDerivedCaches(NEURepository repository) {
		NpcShopIndex.invalidate();
		ReforgeStore.getInstance().invalidate();
		EssenceStore.getInstance().invalidate();
		PetStatResolver.invalidateCache();
		SkyblockWikiManager.getInstance().invalidateCache();
		SkyblockItemResolver.invalidateCache(repository);
	}

	private RepoRevision readDiskRevisionQuietly() {
		try {
			return readDiskRevision();
		} catch (Exception e) {
			LOGGER.debug("Could not inspect the owned NEU repo for changes ({}).", e.toString());
			return null;
		}
	}

	private RepoRevision readDiskRevision() throws IOException {
		Path repoDir = downloader.getRepoDir();
		if (!downloader.ensureRepoDirectory()) return null;
		RevisionAccumulator accumulator = new RevisionAccumulator();
		accumulateRevision(repoDir, repoDir.resolve("items"), accumulator);
		accumulateRevision(repoDir, repoDir.resolve("constants"), accumulator);
		return new RepoRevision(downloader.loadSavedSha(), accumulator.fileCount,
			accumulator.sum ^ Long.rotateLeft(accumulator.xor, 17));
	}

	private RepoRevision readCompleteDiskRevision() throws IOException {
		Path repoDir = downloader.getRepoDir();
		if (!downloader.ensureRepoDirectory()) throw new IOException("repository directory could not be created");
		if (!Files.isDirectory(repoDir.resolve("items"))) {
			throw new IOException("required directory items is missing");
		}
		if (!Files.isDirectory(repoDir.resolve("constants"))) {
			throw new IOException("required directory constants is missing");
		}
		for (String required : REQUIRED_REPO_FILES) {
			if (!Files.isRegularFile(repoDir.resolve(required))) {
				throw new IOException("required file " + required + " is missing");
			}
		}
		try (var items = Files.list(repoDir.resolve("items"))) {
			if (items.noneMatch(path -> Files.isRegularFile(path)
					&& path.getFileName().toString().endsWith(".json"))) {
				throw new IOException("required directory items contains no JSON files");
			}
		}
		RepoRevision revision = readDiskRevision();
		if (revision == null) throw new IOException("repo directory is missing");
		return revision;
	}

	boolean hasCompleteRepository() {
		try {
			readCompleteDiskRevision();
			return true;
		} catch (IOException | java.io.UncheckedIOException | SecurityException e) {
			return false;
		}
	}

	private static void accumulateRevision(Path repoDir, Path root,
			RevisionAccumulator accumulator) throws IOException {
		if (!Files.isDirectory(root)) return;
		try (var paths = Files.walk(root)) {
			Iterator<Path> iterator = paths.iterator();
			while (iterator.hasNext()) {
				Path path = iterator.next();
				if (!Files.isRegularFile(path) || !path.getFileName().toString().endsWith(".json")) continue;
				BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
				var modified = attributes.lastModifiedTime().toInstant();
				int pathHash = repoDir.relativize(path).toString().hashCode();
				long entry = pathHash;
				entry = 31 * entry + attributes.size();
				entry = 31 * entry + modified.getEpochSecond();
				entry = 31 * entry + modified.getNano();
				accumulator.fileCount++;
				accumulator.sum += entry;
				accumulator.xor ^= Long.rotateLeft(entry, pathHash & 63);
			}
		}
	}

	private static String describeRevision(RepoRevision revision) {
		return revision == null ? "none" : revision.cacheKey();
	}

	private record RepoRevision(String sha, long fileCount, long fingerprint) {
		String cacheKey() {
			return (sha == null ? "unknown" : sha) + ":" + fileCount + ":"
				+ Long.toUnsignedString(fingerprint, 16);
		}
	}

	private static final class RevisionAccumulator {
		private long fileCount;
		private long sum;
		private long xor;
	}

	public record RepositoryData(NEURepository repository, String revisionKey,
			List<NEUCraftingRecipe> craftingRecipes, List<NEUForgeRecipe> forgeRecipes,
			List<NEUMobDropRecipe> mobDropRecipes, List<NEUKatUpgradeRecipe> petUpgradeRecipes) {
	}

	private static RepositoryData buildRepositoryData(NEURepository repo, RepoRevision revision) {
		List<NEUCraftingRecipe> crafting = new java.util.ArrayList<>();
		List<NEUForgeRecipe> forge = new java.util.ArrayList<>();
		List<NEUMobDropRecipe> mobDrops = new java.util.ArrayList<>();
		List<NEUKatUpgradeRecipe> petUpgrades = new java.util.ArrayList<>();

		for (io.github.moulberry.repo.data.NEUItem item : repo.getItems().getItems().values()) {
			var recipes = item.getRecipes();
			if (recipes == null || recipes.isEmpty()) continue;

			List<NEUCraftingRecipe> itemCrafting = new java.util.ArrayList<>();
			List<NEUForgeRecipe> itemForge = new java.util.ArrayList<>();
			List<NEUMobDropRecipe> itemMobDrops = new java.util.ArrayList<>();
			List<NEUKatUpgradeRecipe> itemPetUpgrades = new java.util.ArrayList<>();

			for (var recipe : recipes) {
				if (recipe instanceof NEUCraftingRecipe r) itemCrafting.add(r);
				if (recipe instanceof NEUForgeRecipe r) itemForge.add(r);
				if (recipe instanceof NEUMobDropRecipe r) itemMobDrops.add(r);
				if (recipe instanceof NEUKatUpgradeRecipe r) itemPetUpgrades.add(r);
			}

			crafting.addAll(RecipeDedup.dedupe(itemCrafting, RecipeDedup::craftingSignature));
			forge.addAll(RecipeDedup.dedupe(itemForge, RecipeDedup::forgeSignature));
			mobDrops.addAll(RecipeDedup.dedupe(itemMobDrops, RecipeDedup::mobDropSignature));
			petUpgrades.addAll(RecipeDedup.dedupe(itemPetUpgrades, RecipeDedup::katUpgradeSignature));
		}

		DungeonRewards.addMissingRewards(repo, mobDrops);
		return new RepositoryData(repo, revision.cacheKey(), List.copyOf(crafting), List.copyOf(forge),
			List.copyOf(mobDrops), List.copyOf(petUpgrades));
	}

	public RepositoryData getRepositoryDataOrNull() {
		return loadedData.get();
	}

	public List<NEUCraftingRecipe> getCraftingRecipes() {
		RepositoryData data = loadedData.get();
		return data == null ? List.of() : data.craftingRecipes();
	}

	public List<io.github.moulberry.repo.data.NEUItem> getAllItems() {
		RepositoryData data = loadedData.get();
		if (data == null) return List.of();
		return List.copyOf(data.repository().getItems().getItems().values());
	}

	public List<NEUForgeRecipe> getForgeRecipes() {
		RepositoryData data = loadedData.get();
		return data == null ? List.of() : data.forgeRecipes();
	}

	/** Mob-drop tables (what a given mob drops, its XP/coins) embedded in the item repo. */
	public List<NEUMobDropRecipe> getMobDropRecipes() {
		RepositoryData data = loadedData.get();
		return data == null ? List.of() : data.mobDropRecipes();
	}

	/** Kat pet-rarity-upgrade recipes (e.g. Epic -> Legendary pet upgrades). */
	public List<NEUKatUpgradeRecipe> getPetUpgradeRecipes() {
		RepositoryData data = loadedData.get();
		return data == null ? List.of() : data.petUpgradeRecipes();
	}

}
