package moe.example.skyblockrecipeviewer.repo.reforge;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Loads and indexes every reforge (both free Blacksmith reforges from
 * {@code constants/reforges.json} and reforge-stone reforges from
 * {@code constants/reforgestones.json}) in the shared NEU item repo.
 *
 * Parsed with plain Gson straight off disk, independent of {@code moe.nea:neurepoparser}'s own
 * typed constants API - the exact shape of that library's constants classes (beyond the
 * NEUCraftingRecipe/NEUItem getters this mod's crafting-display code already relies on and has
 * verified) isn't something we can decompile/confirm, so reading the raw JSON files directly
 * (which the repo download already puts on disk under {@link #reload}'s repoDir, regardless of
 * what the typed parser does with them) avoids guessing at getter names we can't check.
 *
 * Field-name choices below are ported from Firmament's {@code Reforge.kt}/{@code
 * ReforgeStore.kt} (same repo, same files, actively-maintained reference implementation) - see
 * those for the shape this was reverse-engineered from.
 */
public final class ReforgeStore {
	private static final Logger LOGGER = LogManager.getLogger("SkyblockRecipeViewer/Reforge");
	private static final ReforgeStore INSTANCE = new ReforgeStore();

	private final AtomicReference<List<ReforgeData>> all = new AtomicReference<>(List.of());
	private final AtomicReference<Map<String, ReforgeData>> byReforgeStone = new AtomicReference<>(Map.of());
	private final AtomicReference<Map<String, List<ReforgeData>>> byInternalName =
		new AtomicReference<>(Map.of());
	private final AtomicReference<Map<String, List<ReforgeData>>> byItemType =
		new AtomicReference<>(Map.of());

	private ReforgeStore() {
	}

	public static ReforgeStore getInstance() {
		return INSTANCE;
	}

	public List<ReforgeData> getAllReforges() {
		return all.get();
	}

	/** The reforge a given reforge-stone SkyBlock item applies, if {@code skyblockId} is one. */
	public ReforgeData getByReforgeStone(String skyblockId) {
		if (skyblockId == null) return null;
		return byReforgeStone.get().get(skyblockId);
	}

	/** Reforges explicitly allow-listed (by SkyBlock id) for this specific item. */
	public List<ReforgeData> getByInternalName(String skyblockId) {
		if (skyblockId == null) return List.of();
		return byInternalName.get().getOrDefault(skyblockId, List.of());
	}

	/**
	 * Reforges that apply to every item of the given SkyBlock item-type/category (e.g.
	 * {@code "SWORD"}, {@code "BOW"}), the way most reforges (everything except the small set
	 * of unique-item-specific ones {@link #getByInternalName} covers) are actually scoped -
	 * this is the match {@link SkyblockReiPlugin}'s reforge lookup needs for a right-clicked
	 * item to show the reforges that genuinely apply to it, not just the ones naming its exact
	 * id. {@code itemType} should already be uppercased (see {@link
	 * moe.example.skyblockrecipeviewer.repo.ItemCategoryResolver}, which is where a caller
	 * resolving this from a SkyBlock id would get it from) - this index's own keys are stored
	 * uppercased too, so a lowercase/mixed-case argument simply won't match anything.
	 */
	public List<ReforgeData> getByItemType(String itemType) {
		if (itemType == null) return List.of();
		return byItemType.get().getOrDefault(itemType, List.of());
	}

	public void invalidate() {
		all.set(List.of());
		byReforgeStone.set(Map.of());
		byInternalName.set(Map.of());
		byItemType.set(Map.of());
	}

	/**
	 * Re-reads both reforge JSON files from {@code repoDir} (the same shared repo folder
	 * NeuRepoManager downloads/tracks). Cheap and safe to call repeatedly - always replaces the
	 * in-memory tables atomically, never partially. No-ops quietly (keeps whatever was loaded
	 * before) if the files aren't there yet, e.g. before the repo has ever downloaded.
	 */
	public void reload(Path repoDir) {
		try {
			List<ReforgeData> basic = readReforgeFile(repoDir.resolve("constants/reforges.json"));
			List<ReforgeData> stones = readReforgeFile(repoDir.resolve("constants/reforgestones.json"));
			List<ReforgeData> combined = new ArrayList<>(basic.size() + stones.size());
			combined.addAll(basic);
			combined.addAll(stones);

			Map<String, ReforgeData> stoneIndex = new LinkedHashMap<>();
			Map<String, List<ReforgeData>> nameIndex = new LinkedHashMap<>();
			Map<String, List<ReforgeData>> typeIndex = new LinkedHashMap<>();
			for (ReforgeData reforge : combined) {
				if (reforge.reforgeStoneId() != null) {
					stoneIndex.put(reforge.reforgeStoneId(), reforge);
				}
				for (ReforgeData.EligibilityFilter filter : reforge.eligibleItems()) {
					if (filter instanceof ReforgeData.AllowsInternalName(String internalName)) {
						nameIndex.computeIfAbsent(internalName, k -> new ArrayList<>()).add(reforge);
					} else if (filter instanceof ReforgeData.AllowsItemType(String itemType)) {
						String key = itemType.toUpperCase(Locale.ROOT);
						typeIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(reforge);
					}
				}
			}

			all.set(List.copyOf(combined));
			byReforgeStone.set(Map.copyOf(stoneIndex));
			byInternalName.set(Map.copyOf(nameIndex));
			byItemType.set(Map.copyOf(typeIndex));
			LOGGER.info("Loaded {} reforges ({} basic, {} reforge-stone; {} distinct item types "
				+ "indexed) from the item repo.",
				combined.size(), basic.size(), stones.size(), typeIndex.size());
		} catch (Exception e) {
			LOGGER.warn("Failed to load reforge data from the item repo - reforge displays will "
				+ "be unavailable until this succeeds.", e);
		}
	}

	private static List<ReforgeData> readReforgeFile(Path file) throws IOException {
		if (!Files.isRegularFile(file)) return List.of();
		JsonObject root;
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			root = JsonParser.parseReader(reader).getAsJsonObject();
		}
		List<ReforgeData> result = new ArrayList<>();
		for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
			if (!entry.getValue().isJsonObject()) continue;
			ReforgeData parsed = parseReforge(entry.getValue().getAsJsonObject());
			if (parsed != null) result.add(parsed);
		}
		return result;
	}

	private static ReforgeData parseReforge(JsonObject json) {
		String reforgeName = getString(json, "reforgeName");
		if (reforgeName == null) return null;
		String reforgeStoneId = getString(json, "internalName");
		String nbtModifier = getString(json, "nbtModifier");
		String reforgeId = nbtModifier != null ? nbtModifier : reforgeName.toLowerCase(Locale.ROOT);

		List<ReforgeData.EligibilityFilter> eligible = new ArrayList<>();
		eligible.addAll(parseEligibility(json.get("itemTypes")));
		eligible.addAll(parseEligibility(json.get("allowOn")));

		Map<String, Double> costs = parseRarityMapped(json.get("reforgeCosts"), JsonElement::getAsDouble);
		Map<String, String> abilities = parseRarityMapped(json.get("reforgeAbility"), JsonElement::getAsString);
		Map<String, Map<String, Double>> stats = parseRarityMapped(json.get("reforgeStats"),
			el -> {
				Map<String, Double> m = new LinkedHashMap<>();
				for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
					if (e.getValue().isJsonPrimitive()) m.put(e.getKey(), e.getValue().getAsDouble());
				}
				return m;
			});

		Set<String> statUniverse = new LinkedHashSet<>();
		for (Map<String, Double> statMap : stats.values()) statUniverse.addAll(statMap.keySet());

		return new ReforgeData(reforgeName, reforgeId, reforgeStoneId, List.copyOf(eligible),
			costs, abilities, stats, List.copyOf(statUniverse));
	}

	/**
	 * The repo encodes "what can this reforge apply to" as either:
	 *  - a plain string, "/"-separated for multiple item types ("SWORD/BOW")
	 *  - a JSON array of single-filter objects ({"itemType": "SWORD"}, {"internalName": "..."},
	 *    {"minecraftId": "..."})
	 *  - a JSON object with "internalName" and/or "itemId" arrays of many values at once
	 * All three appear in the real repo data depending on the reforge - see Firmament's
	 * Reforge.kt ItemTypesSerializer for the reference this was ported from.
	 */
	private static List<ReforgeData.EligibilityFilter> parseEligibility(JsonElement el) {
		if (el == null || el.isJsonNull()) return List.of();
		List<ReforgeData.EligibilityFilter> out = new ArrayList<>();
		if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
			for (String part : el.getAsString().split("/")) {
				if (!part.isBlank()) out.add(new ReforgeData.AllowsItemType(part));
			}
		} else if (el.isJsonArray()) {
			for (JsonElement item : el.getAsJsonArray()) {
				if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
					out.add(new ReforgeData.AllowsItemType(item.getAsString()));
					continue;
				}
				if (!item.isJsonObject()) continue;
				JsonObject obj = item.getAsJsonObject();
				String internalName = getString(obj, "internalName");
				String itemType = getString(obj, "itemType");
				String minecraftId = getString(obj, "minecraftId");
				if (internalName != null) out.add(new ReforgeData.AllowsInternalName(internalName));
				else if (itemType != null) out.add(new ReforgeData.AllowsItemType(itemType));
				else if (minecraftId != null) out.add(new ReforgeData.AllowsVanillaId(minecraftId));
			}
		} else if (el.isJsonObject()) {
			JsonObject obj = el.getAsJsonObject();
			addStringList(obj.get("internalName"), out, ReforgeData.AllowsInternalName::new);
			addStringList(obj.get("itemId"), out, ReforgeData.AllowsVanillaId::new);
		}
		return out;
	}

	private static void addStringList(JsonElement el, List<ReforgeData.EligibilityFilter> out,
			java.util.function.Function<String, ReforgeData.EligibilityFilter> ctor) {
		if (el == null) return;
		if (el.isJsonArray()) {
			for (JsonElement item : el.getAsJsonArray()) {
				if (item.isJsonPrimitive()) out.add(ctor.apply(item.getAsString()));
			}
		} else if (el.isJsonPrimitive()) {
			out.add(ctor.apply(el.getAsString()));
		}
	}

	/**
	 * A "RarityMapped" value in the repo is either one flat value applying to every rarity, or a
	 * JSON object keyed by rarity name ("COMMON", "LEGENDARY", ...) with a different value per
	 * rarity - mirrors Firmament's {@code Reforge.RarityMapped}.
	 */
	private static <T> Map<String, T> parseRarityMapped(JsonElement el,
			java.util.function.Function<JsonElement, T> valueParser) {
		if (el == null || el.isJsonNull()) return Map.of();
		if (el.isJsonObject()) {
			Map<String, T> out = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> entry : el.getAsJsonObject().entrySet()) {
				try {
					out.put(entry.getKey().toUpperCase(Locale.ROOT), valueParser.apply(entry.getValue()));
				} catch (Exception ignored) {
					// A rarity entry we can't parse (unexpected shape) just doesn't get a value -
					// costFor()/statsFor() fall back to the flat "*" bucket in that case anyway.
				}
			}
			return out;
		}
		try {
			return Map.of(ReforgeData.RARITY_ANY, valueParser.apply(el));
		} catch (Exception e) {
			return Map.of();
		}
	}

	private static String getString(JsonObject obj, String key) {
		JsonElement el = obj.get(key);
		if (el == null || el.isJsonNull()) return null;
		if (!(el.isJsonPrimitive())) return null;
		JsonPrimitive prim = el.getAsJsonPrimitive();
		return prim.isString() ? prim.getAsString() : prim.toString();
	}
}
