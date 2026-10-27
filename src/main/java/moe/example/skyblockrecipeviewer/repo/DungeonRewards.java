package moe.example.skyblockrecipeviewer.repo;

import java.io.Reader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.moulberry.repo.NEURepository;
import io.github.moulberry.repo.data.NEUIngredient;
import io.github.moulberry.repo.data.NEUItem;
import io.github.moulberry.repo.data.NEUMobDropRecipe;
import org.apache.logging.log4j.LogManager;

/** Adds verified chest sources to NEU dungeon loot, retaining RNG meter membership as a fallback. */
public final class DungeonRewards {

	private static final List<String> BOSSES = List.of("BONZO_BOSS", "SCARF_BOSS", "PROFESSOR_BOSS",
		"THORN_BOSS", "LIVID_BOSS", "SADAN_BOSS", "NECRON_BOSS");
	public static final List<String> CHESTS = List.of("Wood Chest", "Gold Chest", "Diamond Chest",
		"Emerald Chest", "Obsidian Chest", "Bedrock Chest");
	public static final String UNSPECIFIED_CHEST = "Rewards (chest unspecified)";

	private DungeonRewards() {}

	public record Floor(int number, boolean master) {
		public String key() {
			return (master ? "M" : "F") + number;
		}

		public String title() {
			return "Catacombs Floor " + number + " (" + (master ? "Master" : "Normal") + " Mode)";
		}
	}

	public static Floor floor(NEUMobDropRecipe recipe) {
		if (!"dungeon".equals(recipe.getPanorama()) || recipe.getDropsFrom() == null) return null;
		int index = BOSSES.indexOf(recipe.getDropsFrom().getSkyblockItemId());
		return index < 0 ? null : new Floor(index + 1,
			recipe.getName() != null && recipe.getName().contains("(Master Mode)"));
	}

	public static String chest(List<String> extra) {
		if (extra != null) {
			for (String line : extra) {
				if (line == null) continue;
				String plain = line.replaceAll("\u00a7.", "");
				if (!plain.startsWith("Found in:")) continue;
				String source = plain.substring("Found in:".length()).trim();
				if (CHESTS.contains(source)) return source;
			}
		}
		return UNSPECIFIED_CHEST;
	}

	public static void addMissingRewards(NEURepository repository, List<NEUMobDropRecipe> recipes) {
		JsonObject verified = new JsonObject();
		try (var stream = DungeonRewards.class.getResourceAsStream(
			"/data/skyblockrecipeviewer/dungeon_books.json")) {
			if (stream != null) {
				JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
					.getAsJsonObject();
				verified = root.getAsJsonObject("floors");
				addVerifiedRewards(recipes, repository.getItems().getItems(), verified);
			}
		} catch (Exception e) {
			LogManager.getLogger("SkyblockRecipeViewer/Repo").warn(
				"Could not read verified dungeon reward sources; falling back to NEU data.", e);
			verified = new JsonObject();
		}
		var file = repository.getBaseFolder().resolve("constants/rngscore.json");
		if (!Files.isRegularFile(file)) return;
		try (Reader reader = Files.newBufferedReader(file)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			if (root.has("catacombs") && root.get("catacombs").isJsonObject()) {
				JsonObject fallback = root.getAsJsonObject("catacombs");
				for (String floor : verified.keySet()) fallback.remove(floor);
				addMissingBooks(recipes, repository.getItems().getItems(), fallback);
			}
		} catch (Exception e) {
			LogManager.getLogger("SkyblockRecipeViewer/Repo").warn(
				"Could not read dungeon book sources from rngscore.json; keeping existing drops.", e);
		}
	}

	static void addVerifiedRewards(List<NEUMobDropRecipe> recipes, Map<String, NEUItem> items, JsonObject floors) {
		int added = 0;
		for (NEUMobDropRecipe recipe : recipes) {
			Floor floor = floor(recipe);
			if (floor == null || recipe.getDrops() == null || !floors.has(floor.key())) continue;
			JsonObject verifiedFloor = floors.getAsJsonObject(floor.key());
			Set<String> defaultIds = new HashSet<>();
			for (var drop : recipe.getDrops()) {
				if (drop.getDropItem() != null) defaultIds.add(drop.getDropItem().getItemId());
			}
			for (String table : List.of("item_chests", "chests")) {
				if (!verifiedFloor.has(table)) continue;
				JsonObject chests = verifiedFloor.getAsJsonObject(table);
				for (String chest : List.of("Obsidian Chest", "Bedrock Chest")) {
					if ((floor.number() < 5 && chest.equals("Bedrock Chest")) || !chests.has(chest)) continue;
					for (var entry : chests.getAsJsonObject(chest).entrySet()) {
						String id = entry.getKey();
						NEUItem item = items.get(id);
						if (item == null) continue;
						if (table.equals("item_chests") ? !defaultIds.contains(id)
							: !"minecraft:enchanted_book".equals(item.getMinecraftItemId())) continue;
						boolean exists = recipe.getDrops().stream().anyMatch(drop -> drop.getDropItem() != null
							&& id.equals(drop.getDropItem().getItemId()) && chest.equals(chest(drop.getExtra())));
						if (exists) continue;
						recipe.getDrops().add(new NEUMobDropRecipe.Drop(NEUIngredient.fromString(id), entry.getValue().getAsString(),
							List.of("Found in: " + chest, "Chance basis: S+ with max bonuses",
								"Source: Hypixel SkyBlock Wiki")));
						added++;
					}
				}
			}
		}
		if (added > 0) LogManager.getLogger("SkyblockRecipeViewer/Repo").info(
			"Added {} dungeon chest sources using wiki S+ with max bonuses average chances; existing NEU loot retained.", added);
	}

	public static boolean isVisibleDrop(NEUItem item, NEUMobDropRecipe.Drop drop, List<NEUMobDropRecipe.Drop> drops) {
		if (drop.getDropItem() == null) return false;
		String id = drop.getDropItem().getItemId();
		if (item != null && "minecraft:enchanted_book".equals(item.getMinecraftItemId()) && !id.endsWith(";1")) return false;
		if (!UNSPECIFIED_CHEST.equals(chest(drop.getExtra()))) return true;
		return drops.stream().noneMatch(other -> other.getDropItem() != null
			&& id.equals(other.getDropItem().getItemId()) && !UNSPECIFIED_CHEST.equals(chest(other.getExtra())));
	}

	static void addMissingBooks(List<NEUMobDropRecipe> recipes, Map<String, NEUItem> items, JsonObject floors) {
		int added = 0;
		for (NEUMobDropRecipe recipe : recipes) {
			Floor floor = floor(recipe);
			if (floor == null || recipe.getDrops() == null) continue;
			JsonElement rewards = floors.get(floor.key());
			if (rewards == null || !rewards.isJsonObject()) continue;
			List<NEUMobDropRecipe.Drop> drops = recipe.getDrops();
			for (String id : rewards.getAsJsonObject().keySet()) {
				NEUItem item = items.get(id);
				if (item == null || !"minecraft:enchanted_book".equals(item.getMinecraftItemId())) continue;
				// These two Legion chest sources were explicitly confirmed; NEU supplies the floor and level.
				List<String> sources = floor.number() == 5 && id.equals("ULTIMATE_LEGION;1")
					? List.of("Obsidian Chest", "Bedrock Chest") : List.of(UNSPECIFIED_CHEST);
				for (String source : sources) {
					boolean exists = drops.stream().anyMatch(drop -> drop.getDropItem() != null
						&& id.equals(drop.getDropItem().getItemId())
						&& (source.equals(UNSPECIFIED_CHEST) || source.equals(chest(drop.getExtra()))));
					if (exists) continue;
					List<String> extra = new ArrayList<>();
					if (!source.equals(UNSPECIFIED_CHEST)) extra.add("Found in: " + source);
					extra.add("Drop chance unavailable in NEU repository.");
					drops.add(new NEUMobDropRecipe.Drop(NEUIngredient.fromString(id), null, extra));
					added++;
				}
			}
		}
		if (added > 0) {
			LogManager.getLogger("SkyblockRecipeViewer/Repo").info(
				"Linked {} missing dungeon book sources. RNG meter data has no per-chest probabilities; "
					+ "missing chances and chest types remain unspecified.", added);
		}
	}
}
