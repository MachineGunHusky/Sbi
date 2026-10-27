package moe.example.skyblockrecipeviewer.repo;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.moulberry.repo.data.NEUItem;
import io.github.moulberry.repo.data.NEUMobDropRecipe;
import org.junit.jupiter.api.Test;

class DungeonRewardsTest {

	private static final Gson GSON = new GsonBuilder()
		.registerTypeAdapter(NEUMobDropRecipe.Drop.class, new NEUMobDropRecipe.Drop.Serializer()).create();
	private static final String LEGION = "ULTIMATE_LEGION;1";

	@Test
	void preservesSeparateChestChancesAndAddsOnlyMissingSources() {
		NEUMobDropRecipe normal = recipe("LIVID_BOSS", "Livid", """
			[{"id":"ULTIMATE_LEGION;1", "chance":"0.5%", "extra":["Found in: Obsidian Chest"]},
			 {"id":"LIVID_DAGGER", "chance":"1.05%", "extra":["Found in: Bedrock Chest"]}]
			""");
		NEUMobDropRecipe master = recipe("LIVID_BOSS", "Livid (Master Mode)", """
			[{"id":"ULTIMATE_LEGION;1", "chance":"0.2%", "extra":["Found in: Bedrock Chest"]}]
			""");
		var dagger = normal.getDrops().get(1);
		JsonObject floors = json("""
			{"F5":{"ULTIMATE_LEGION;1":5715,"ULTIMATE_WISDOM;2":4572},
			 "M5":{"ULTIMATE_LEGION;1":6000}}
			""");
		Map<String, NEUItem> items = Map.of(LEGION, book(LEGION),
			"ULTIMATE_WISDOM;2", book("ULTIMATE_WISDOM;2"));
		DungeonRewards.addMissingBooks(List.of(normal, master), items, floors);
		DungeonRewards.addMissingBooks(List.of(normal, master), items, floors);

		assertEquals("F5", DungeonRewards.floor(normal).key());
		assertEquals("M5", DungeonRewards.floor(master).key());
		assertEquals(4, normal.getDrops().size());
		assertEquals(2, master.getDrops().size());
		assertSame(dagger, normal.getDrops().get(1));
		assertEquals("0.5%", drop(normal, LEGION, "Obsidian Chest").getChance());
		assertNull(drop(normal, LEGION, "Bedrock Chest").getChance());
		assertEquals("0.2%", drop(master, LEGION, "Bedrock Chest").getChance());
		assertNull(drop(master, LEGION, "Obsidian Chest").getChance());
		assertEquals("ULTIMATE_WISDOM;2", normal.getDrops().getLast().getDropItem().getItemId());
		assertEquals(DungeonRewards.UNSPECIFIED_CHEST,
			DungeonRewards.chest(normal.getDrops().getLast().getExtra()));
	}

	@Test
	void neverCopiesNormalRewardsIntoMissingMasterData() {
		var normal = recipe("LIVID_BOSS", "Livid", "[]");
		var master = recipe("LIVID_BOSS", "Livid (Master Mode)", "[]");
		DungeonRewards.addMissingBooks(List.of(normal, master), Map.of(LEGION, book(LEGION)),
			json("{\"F5\":{\"ULTIMATE_LEGION;1\":5715}}"));
		assertEquals(2, normal.getDrops().size());
		assertTrue(master.getDrops().isEmpty());
	}

	@Test
	void earlierFloorsDoNotAcquireBedrockSourcesAndIgnorePetsOrMissingItems() {
		String[] bosses = {"BONZO_BOSS", "SCARF_BOSS", "PROFESSOR_BOSS", "THORN_BOSS"};
		for (int i = 0; i < bosses.length; i++) {
			for (boolean master : new boolean[]{false, true}) {
				var recipe = recipe(bosses[i], "Boss" + (master ? " (Master Mode)" : ""), "[]");
				String key = (master ? "M" : "F") + (i + 1);
				DungeonRewards.addMissingBooks(List.of(recipe), Map.of(LEGION, book(LEGION),
					"SPIRIT;4", item("SPIRIT;4", "minecraft:player_head")),
					json("{\"" + key + "\":{\"ULTIMATE_LEGION;1\":1,\"SPIRIT;4\":2,\"MISSING;1\":3}}"));
				assertEquals(1, recipe.getDrops().size());
				assertEquals(DungeonRewards.UNSPECIFIED_CHEST,
					DungeonRewards.chest(recipe.getDrops().getFirst().getExtra()));
				assertNull(recipe.getDrops().getFirst().getChance());
			}
		}
	}

	@Test
	void dedupKeepsModesAndDistinctChestProbabilities() {
		var normal = recipe("LIVID_BOSS", "Livid", "[\"ULTIMATE_LEGION;1\"]");
		var master = recipe("LIVID_BOSS", "Livid (Master Mode)", "[\"ULTIMATE_LEGION;1\"]");
		assertEquals(2, RecipeDedup.dedupe(List.of(normal, master, normal), RecipeDedup::mobDropSignature).size());
		var obsidian = recipe("LIVID_BOSS", "Livid", """
			[{"id":"ULTIMATE_LEGION;1","chance":"1%","extra":["Found in: Obsidian Chest"]}]
			""");
		var bedrock = recipe("LIVID_BOSS", "Livid", """
			[{"id":"ULTIMATE_LEGION;1","chance":"2%","extra":["Found in: Bedrock Chest"]}]
			""");
		assertEquals(2, RecipeDedup.dedupe(List.of(obsidian, bedrock), RecipeDedup::mobDropSignature).size());
	}

	@Test
	void chestDetectionHandlesLegacyColorsWithoutUsingChestPricesAsSources() {
		assertEquals("Bedrock Chest", DungeonRewards.chest(List.of("\u00a7e\u00a7lFound in: \u00a78Bedrock Chest")));
		assertEquals(DungeonRewards.UNSPECIFIED_CHEST, DungeonRewards.chest(List.of("Chest Price: 2000000")));
		assertNull(DungeonRewards.floor(recipe("SOME_MONSTER", "Livid", "[]")));
	}

	@Test
	void verifiedTablesCoverBothModesAndOnlySupportedHighTierChests() throws Exception {
		JsonObject root = verifiedData();
		assertEquals("Average Chance (S+ Max Bonuses)", root.get("chance_basis").getAsString());
		JsonObject floors = root.getAsJsonObject("floors");
		assertEquals(14, floors.size());
		int entries = 0;
		var ids = new HashSet<String>();
		for (boolean master : new boolean[]{false, true}) {
			for (int number = 1; number <= 7; number++) {
				String key = (master ? "M" : "F") + number;
				JsonObject floor = floors.getAsJsonObject(key);
				assertTrue(floor.get("source").getAsString().endsWith(master ? "/Master_Mode_Loot" : "/Loot"));
				JsonObject chests = floor.getAsJsonObject("chests");
				assertEquals(number < 5 ? 1 : 2, chests.size());
				assertTrue(chests.has("Obsidian Chest"));
				assertEquals(number >= 5, chests.has("Bedrock Chest"));
				for (var chest : chests.entrySet()) {
					assertTrue(List.of("Obsidian Chest", "Bedrock Chest").contains(chest.getKey()));
					for (var book : chest.getValue().getAsJsonObject().entrySet()) {
						assertTrue(book.getKey().matches("[A-Z_]+;[1-7]"));
						String chance = book.getValue().getAsString();
						assertTrue(chance.matches("[0-9]+(?:\\.[0-9]+)?%"));
						double percent = Double.parseDouble(chance.substring(0, chance.length() - 1));
						assertTrue(percent > 0 && percent <= 100);
						ids.add(book.getKey());
						entries++;
					}
				}
			}
		}
		assertEquals(183, entries);
		assertEquals(31, ids.size());
	}

	@Test
	void verifiedSourcesSupplementExistingRecordsWithoutReplacingThem() throws Exception {
		var normal = recipe("LIVID_BOSS", "Livid", """
			[{"id":"ULTIMATE_LEGION;1","chance":"0.5%","extra":["Found in: Obsidian Chest"]},
			 {"id":"ULTIMATE_LEGION;1"},
			 {"id":"LIVID_DAGGER","chance":"1.05%","extra":["Found in: Bedrock Chest"]}]
			""");
		var master = recipe("LIVID_BOSS", "Livid (Master Mode)", "[]");
		var dagger = normal.getDrops().getLast();
		var existingBook = normal.getDrops().getFirst();
		var items = Map.of(LEGION, book(LEGION));
		var floors = verifiedData().getAsJsonObject("floors");
		DungeonRewards.addVerifiedRewards(List.of(normal, master), items, floors);
		DungeonRewards.addVerifiedRewards(List.of(normal, master), items, floors);
		assertEquals(4, normal.getDrops().size());
		assertEquals(2, master.getDrops().size());
		assertSame(dagger, normal.getDrops().get(2));
		assertSame(existingBook, normal.getDrops().getFirst());
		assertEquals("1.05%", dagger.getChance());
		assertEquals("0.5%", drop(normal, LEGION, "Obsidian Chest").getChance());
		assertEquals("14.8231%", drop(normal, LEGION, "Bedrock Chest").getChance());
		assertEquals("13.0954%", drop(master, LEGION, "Obsidian Chest").getChance());
		assertEquals("17.7685%", drop(master, LEGION, "Bedrock Chest").getChance());
		assertTrue(drop(normal, LEGION, "Bedrock Chest").getExtra().contains("Chance basis: S+ with max bonuses"));
		assertFalse(DungeonRewards.isVisibleDrop(book(LEGION), normal.getDrops().get(1), normal.getDrops()));
	}

	@Test
	void floorExclusiveBooksAndExactLevelsUseTheirOwnSources() throws Exception {
		String[] bosses = {"BONZO_BOSS", "SCARF_BOSS", "PROFESSOR_BOSS", "THORN_BOSS", "LIVID_BOSS", "SADAN_BOSS", "NECRON_BOSS"};
		var floors = verifiedData().getAsJsonObject("floors");
		Map<String, NEUItem> items = new LinkedHashMap<>();
		for (var floor : floors.entrySet()) {
			for (var chest : floor.getValue().getAsJsonObject().getAsJsonObject("chests").entrySet()) {
				for (String id : chest.getValue().getAsJsonObject().keySet()) items.put(id, book(id));
			}
		}
		for (boolean master : new boolean[]{false, true}) {
			for (int number = 1; number <= 7; number++) {
				var recipe = recipe(bosses[number - 1], "Boss" + (master ? " (Master Mode)" : ""), "[]");
				DungeonRewards.addVerifiedRewards(List.of(recipe), items, floors);
				var ids = recipe.getDrops().stream().map(drop -> drop.getDropItem().getItemId()).toList();
				assertEquals(number == 4, ids.contains("ULTIMATE_REND;2"));
				assertEquals(number == 5, ids.contains(LEGION));
				assertEquals(number == 5, ids.contains("OVERLOAD;1"));
				assertEquals(number == 6, ids.contains("ULTIMATE_SWARM;1"));
				assertEquals(number == 7, ids.contains("ULTIMATE_ONE_FOR_ALL;1"));
				assertEquals(number == 7, ids.contains("ULTIMATE_SOUL_EATER;1"));
				assertEquals(number == 7 && master, ids.contains("THUNDERLORD;7"));
				assertEquals(recipe.getDrops().size(), recipe.getDrops().stream()
					.map(drop -> drop.getDropItem().getItemId() + "|" + DungeonRewards.chest(drop.getExtra())).distinct().count());
				if (number == 5) {
					assertNotNull(drop(recipe, "ULTIMATE_COMBO;1", "Obsidian Chest"));
					assertNotNull(drop(recipe, "ULTIMATE_COMBO;2", "Bedrock Chest"));
				}
			}
		}
	}

	@Test
	void defaultItemLootGainsOnlyVerifiedMissingChestSourcesWithSeparateModeChances() throws Exception {
		var normal = recipe("LIVID_BOSS", "Livid", """
			[{"id":"LIVID_DAGGER","chance":"1.05%","extra":["Found in: Bedrock Chest","Chest Price: 7000000"]},
			 {"id":"SHADOW_FURY","chance":"0.262%","extra":["Found in: Bedrock Chest"]}]
			""");
		var master = recipe("LIVID_BOSS", "Livid (Master Mode)", """
			[{"id":"LIVID_DAGGER","chance":"1.231%","extra":["Found in: Bedrock Chest"]}]
			""");
		var originalDagger = normal.getDrops().getFirst();
		var originalFury = normal.getDrops().getLast();
		var items = Map.of("LIVID_DAGGER", item("LIVID_DAGGER", "minecraft:iron_sword"),
			"SHADOW_FURY", item("SHADOW_FURY", "minecraft:iron_sword"),
			"AOTE_STONE", item("AOTE_STONE", "minecraft:stone"), LEGION, book(LEGION));
		var floors = verifiedData().getAsJsonObject("floors");
		DungeonRewards.addVerifiedRewards(List.of(normal, master), items, floors);
		DungeonRewards.addVerifiedRewards(List.of(normal, master), items, floors);
		assertEquals(5, normal.getDrops().size());
		assertSame(originalDagger, normal.getDrops().getFirst());
		assertSame(originalFury, normal.getDrops().get(1));
		assertEquals("1.05%", originalDagger.getChance());
		assertTrue(originalDagger.getExtra().contains("Chest Price: 7000000"));
		assertEquals("0.2959%", drop(normal, "LIVID_DAGGER", "Obsidian Chest").getChance());
		assertEquals("0.495%", drop(master, "LIVID_DAGGER", "Obsidian Chest").getChance());
		assertEquals("1.231%", drop(master, "LIVID_DAGGER", "Bedrock Chest").getChance());
		assertFalse(normal.getDrops().stream().anyMatch(drop -> "SHADOW_FURY".equals(drop.getDropItem().getItemId())
			&& "Obsidian Chest".equals(DungeonRewards.chest(drop.getExtra()))));
		assertFalse(normal.getDrops().stream().anyMatch(drop -> "AOTE_STONE".equals(drop.getDropItem().getItemId())));
	}

	@Test
	void cardsOnlyShowActualLevelOneBooksWhileKeepingFullDropData() throws Exception {
		var recipe = recipe("LIVID_BOSS", "Livid", "[]");
		var items = Map.of(LEGION, book(LEGION), "ULTIMATE_COMBO;1", book("ULTIMATE_COMBO;1"),
			"ULTIMATE_COMBO;2", book("ULTIMATE_COMBO;2"), "LETHALITY;6", book("LETHALITY;6"));
		DungeonRewards.addVerifiedRewards(List.of(recipe), items, verifiedData().getAsJsonObject("floors"));
		assertNotNull(drop(recipe, "ULTIMATE_COMBO;2", "Bedrock Chest"));
		assertNotNull(drop(recipe, "LETHALITY;6", "Obsidian Chest"));
		var visible = recipe.getDrops().stream().filter(drop -> DungeonRewards.isVisibleDrop(
			items.get(drop.getDropItem().getItemId()), drop, recipe.getDrops())).toList();
		assertEquals(3, visible.size());
		assertTrue(visible.stream().allMatch(drop -> drop.getDropItem().getItemId().endsWith(";1")));
		assertTrue(DungeonRewards.isVisibleDrop(item("SPIRIT;4", "minecraft:player_head"),
			new NEUMobDropRecipe.Drop(io.github.moulberry.repo.data.NEUIngredient.fromString("SPIRIT;4"), "3%", List.of()), List.of()));
	}

	private static JsonObject verifiedData() throws Exception {
		try (var stream = DungeonRewards.class.getResourceAsStream("/data/skyblockrecipeviewer/dungeon_books.json")) {
			assertNotNull(stream);
			return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static NEUMobDropRecipe.Drop drop(NEUMobDropRecipe recipe, String id, String chest) {
		return recipe.getDrops().stream().filter(drop -> id.equals(drop.getDropItem().getItemId())
			&& chest.equals(DungeonRewards.chest(drop.getExtra()))).findFirst().orElseThrow();
	}

	private static NEUMobDropRecipe recipe(String boss, String name, String drops) {
		JsonObject root = json("{\"panorama\":\"dungeon\",\"drops\":" + drops + "}");
		root.addProperty("name", name);
		var recipe = GSON.fromJson(root, NEUMobDropRecipe.class);
		recipe.fillItemInfo(item(boss, "minecraft:player_head"));
		return recipe;
	}

	private static NEUItem book(String id) {
		return item(id, "minecraft:enchanted_book");
	}

	private static NEUItem item(String id, String minecraftId) {
		JsonObject root = new JsonObject();
		root.addProperty("internalname", id);
		root.addProperty("itemid", minecraftId);
		return GSON.fromJson(root, NEUItem.class);
	}

	private static JsonObject json(String source) {
		return JsonParser.parseString(source).getAsJsonObject();
	}
}
