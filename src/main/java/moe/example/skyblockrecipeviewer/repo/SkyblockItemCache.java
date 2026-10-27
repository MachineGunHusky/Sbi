package moe.example.skyblockrecipeviewer.repo;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists SkyblockItemResolver's fully-resolved ItemStacks - the output of
 * SkyblockNbtApplier's SNBT-parse + DataFixerUpper-upgrade + lore/model overlay pipeline,
 * which is the dominant cost of resolving REI's ~8000+ item entries (see
 * SkyblockReiPlugin's reload-timing history) - to a small local JSON file, tagged with the
 * NEU repo commit sha (see RepoDownloader/NeuRepoManager) it was resolved from.
 *
 * This lets SkyblockReiPlugin do two things it couldn't before:
 *  1. Show a full, correctly-skinned/NBT'd item list on REI's very first reload - even
 *     before the live repo has finished loading/downloading this session - by reading
 *     already-resolved stacks straight back off disk. This is cheap: TagParser + ItemStack.
 *     CODEC.parse on data that's already in the modern component format, no legacy-index
 *     stripping, no DataFixerUpper walk.
 *  2. Tell whether the live repo actually changed since that cache was written with one
 *     tiny string comparison (repo sha), without resolving a single item - so a Hypixel join
 *     where nothing changed since last time can skip forcing a REI reload entirely (see
 *     SkyblockReiPlugin.forceReiReloadIfNeeded), instead of unconditionally forcing one on
 *     every single join whether or not there was anything new to show.
 */
public final class SkyblockItemCache {
	private static final Logger LOGGER = LogManager.getLogger("SkyblockRecipeViewer/Cache");
	private static final class CachePaths {
		private static final Path CACHE_FILE = FabricLoader.getInstance().getConfigDir()
			.resolve("skyblockrecipeviewer").resolve("resolved-items-cache.json");
		private static final Path REPO_SHA_FILE = FabricLoader.getInstance().getConfigDir()
			.resolve("skyblockrecipeviewer").resolve("NEUrepo").resolve("currentCommit.json");
	}

	private SkyblockItemCache() {
	}

	public record CachedStack(String skyblockId, String snbt) {
	}

	/**
	 * @return the repo commit sha currently recorded in currentCommit.json, or null if that
	 * file doesn't exist yet (repo never downloaded by anything) or fails to parse.
	 */
	public static String currentRepoSha() {
		try {
			if (!Files.exists(CachePaths.REPO_SHA_FILE)) return null;
			JsonObject json = JsonParser.parseString(Files.readString(CachePaths.REPO_SHA_FILE, StandardCharsets.UTF_8))
				.getAsJsonObject();
			return json.has("sha") ? json.get("sha").getAsString() : null;
		} catch (Exception e) {
			LOGGER.warn("Could not read repo commit sha for cache validation ({}).", e.toString());
			return null;
		}
	}

	/**
	 * @return true if our cache is missing, unreadable, or was built from a different repo
	 * sha than what's currently recorded - including "we don't know the current sha", which
	 * is treated as stale rather than trusted: guessing wrong here either costs one
	 * unnecessary re-resolve (safe) or shows outdated items indefinitely (not safe), so ties
	 * go to re-resolving.
	 */
	public static boolean isStale() {
		return isStale(null);
	}

	public static boolean isStale(String currentRevision) {
		String currentSha = currentRepoSha();
		if (currentSha == null) return true;
		try {
			if (!Files.exists(CachePaths.CACHE_FILE)) return true;
			JsonObject root = JsonParser.parseString(Files.readString(CachePaths.CACHE_FILE, StandardCharsets.UTF_8))
				.getAsJsonObject();
			return !matchesRevision(root, currentSha, currentRevision);
		} catch (Exception e) {
			return true;
		}
	}

	/**
	 * Reads every stack out of the on-disk cache - regardless of whether it's stale, that's
	 * a separate, deliberate choice left to the caller (see class javadoc point 1: showing a
	 * possibly one-version-old list instantly beats showing nothing) - and primes
	 * SkyblockItemResolver's in-memory cache with each one via
	 * {@link SkyblockItemResolver#primeCache}.
	 *
	 * @return a skyblockId -> ItemStack map of everything successfully loaded (empty, never
	 * null, if there's no cache file yet or it fails to parse)
	 */
	public static Map<String, ItemStack> loadIntoResolverCache() {
		Map<String, ItemStack> result = new LinkedHashMap<>();
		try {
			if (!Files.exists(CachePaths.CACHE_FILE)) return result;
			JsonObject root = JsonParser.parseString(Files.readString(CachePaths.CACHE_FILE, StandardCharsets.UTF_8))
				.getAsJsonObject();
			if (!root.has("items")) return result;
			JsonObject items = root.getAsJsonObject("items");
			for (String skyblockId : items.keySet()) {
				try {
					String snbt = items.get(skyblockId).getAsString();
					CompoundTag tag = TagParser.parseCompoundFully(snbt);
					ItemStack stack = ItemStack.CODEC.parse(SkyblockNbtApplier.registryOps(), tag)
						.resultOrPartial(error -> LOGGER.warn(
							"Skipping cached stack for {} - failed to parse: {}", skyblockId, error))
						.orElse(ItemStack.EMPTY);
					if (stack.isEmpty()) continue;
					SkyblockItemResolver.primeCache(skyblockId, stack);
					result.put(skyblockId, stack);
				} catch (Exception perItem) {
					LOGGER.warn("Skipping corrupt cached stack for {} ({}).", skyblockId, perItem.toString());
				}
			}
			LOGGER.info("Loaded {} SkyBlock item(s) from the local resolved-item cache.", result.size());
		} catch (Exception e) {
			LOGGER.warn("Could not read local resolved-item cache ({}). Items will only appear "
				+ "once the live repo loads instead.", e.toString());
		}
		return result;
	}

	static boolean matchesRevision(JsonObject root, String currentSha, String currentRevision) {
		if (currentSha == null) return false;
		if (currentRevision != null && root.has("repoRevision")) {
			return currentRevision.equals(root.get("repoRevision").getAsString());
		}
		return root.has("repoSha") && currentSha.equals(root.get("repoSha").getAsString());
	}

	public static java.util.List<CachedStack> readCachedStacks() {
		return readCachedStacks(null, false);
	}

	public static java.util.List<CachedStack> readCachedStacks(String currentRevision) {
		return readCachedStacks(currentRevision, true);
	}

	private static java.util.List<CachedStack> readCachedStacks(String currentRevision, boolean validate) {
		java.util.List<CachedStack> result = new java.util.ArrayList<>();
		try {
			if (!Files.exists(CachePaths.CACHE_FILE)) return result;
			JsonObject root = JsonParser.parseString(Files.readString(CachePaths.CACHE_FILE, StandardCharsets.UTF_8))
				.getAsJsonObject();
			if (validate && !matchesRevision(root, currentRepoSha(), currentRevision)) return result;
			if (!root.has("items")) return result;
			for (String skyblockId : root.getAsJsonObject("items").keySet()) {
				result.add(new CachedStack(skyblockId, root.getAsJsonObject("items").get(skyblockId).getAsString()));
			}
		} catch (Exception e) {
			LOGGER.warn("Could not read local resolved-item cache ({}).", e.toString());
		}
		return result;
	}

	public static ItemStack decodeCachedStack(CachedStack cached) {
		try {
			CompoundTag tag = TagParser.parseCompoundFully(cached.snbt());
			ItemStack stack = ItemStack.CODEC.parse(SkyblockNbtApplier.registryOps(), tag)
				.resultOrPartial(error -> LOGGER.warn("Skipping cached stack for {} - failed to parse: {}", cached.skyblockId(), error))
				.orElse(ItemStack.EMPTY);
			if (!stack.isEmpty()) SkyblockItemResolver.primeCache(cached.skyblockId(), stack);
			return stack;
		} catch (Exception e) {
			LOGGER.warn("Skipping corrupt cached stack for {} ({}).", cached.skyblockId(), e.toString());
			return ItemStack.EMPTY;
		}
	}

	/**
	 * Overwrites the on-disk cache with every given stack, tagged to {@code repoSha}. Meant to
	 * be called only after actually resolving fresh data from a repo isStale() found to be
	 * different from what's cached - NOT on every launch/reload, or a future launch would
	 * always find isStale() == false against a sha written for reasons unrelated to whether
	 * the cached item DATA still matches what's on disk.
	 */
	public static void write(String repoSha, Map<String, ItemStack> resolvedStacks) {
		String serialized = serialize(repoSha, resolvedStacks);
		if (serialized != null) writeSerialized(serialized);
	}

	public static String serialize(String repoSha, Map<String, ItemStack> resolvedStacks) {
		return serialize(repoSha, null, resolvedStacks);
	}

	public static String serialize(String repoSha, String repoRevision,
			Map<String, ItemStack> resolvedStacks) {
		if (repoSha == null) {
			LOGGER.warn("Not writing the SkyBlock item cache - the current repo commit sha is "
				+ "unknown, so a future launch could never tell this cache apart from a stale one.");
			return null;
		}
		try {
			JsonObject root = new JsonObject();
			root.addProperty("repoSha", repoSha);
			if (repoRevision != null) root.addProperty("repoRevision", repoRevision);
			JsonObject items = new JsonObject();
			for (Map.Entry<String, ItemStack> entry : resolvedStacks.entrySet()) {
				ItemStack stack = entry.getValue();
				if (stack == null || stack.isEmpty()) continue;
				var encoded = ItemStack.CODEC.encodeStart(SkyblockNbtApplier.registryOps(), stack)
					.resultOrPartial(error -> LOGGER.warn(
						"Not caching {} - failed to encode: {}", entry.getKey(), error));
				if (encoded.isEmpty()) continue;
				items.addProperty(entry.getKey(), encoded.get().toString());
			}
			root.add("items", items);
			LOGGER.info("Prepared {} resolved SkyBlock item(s) for the local cache (repo {}).",
				items.size(), repoSha);
			return root.toString();
		} catch (Exception e) {
			LOGGER.error("Failed to prepare the local resolved-item cache.", e);
			return null;
		}
	}

	public static void writeSerialized(String serialized) {
		try {
			Files.createDirectories(CachePaths.CACHE_FILE.getParent());
			Files.writeString(CachePaths.CACHE_FILE, serialized, StandardCharsets.UTF_8);
		} catch (Exception e) {
			LOGGER.error("Failed to write the local resolved-item cache.", e);
		}
	}
}
