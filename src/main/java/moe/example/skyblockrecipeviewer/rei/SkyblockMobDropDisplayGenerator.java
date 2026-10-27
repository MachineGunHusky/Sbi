package moe.example.skyblockrecipeviewer.rei;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.moulberry.repo.data.NEUMobDropRecipe;
import me.shedaniel.rei.api.client.registry.display.DynamicDisplayGenerator;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.entry.EntryStack;
import moe.example.skyblockrecipeviewer.repo.NeuRepoManager;
import moe.example.skyblockrecipeviewer.repo.PetAcquisitionStore;
import moe.example.skyblockrecipeviewer.repo.DungeonRewards;
import moe.example.skyblockrecipeviewer.repo.PetStatResolver;
import moe.example.skyblockrecipeviewer.repo.SkyblockItemResolver;

/**
 * Live "recipe for X" lookups for mob drops - see {@link SkyblockCraftingDisplayGenerator}'s
 * class docs for why this pattern is needed. "Recipe for X" here means "which mobs drop X".
 * Dungeon books also expose these sources through uses, and dungeon boss entries open their
 * own reward tables through uses.
 */
public final class SkyblockMobDropDisplayGenerator implements DynamicDisplayGenerator<SkyblockMobDropDisplay> {

	public static final SkyblockMobDropDisplayGenerator INSTANCE = new SkyblockMobDropDisplayGenerator();

	private SkyblockMobDropDisplayGenerator() {
	}

	@Override
	public Optional<List<SkyblockMobDropDisplay>> getRecipeFor(EntryStack<?> entry) {
		return findSources(entry, false);
	}

	private Optional<List<SkyblockMobDropDisplay>> findSources(EntryStack<?> entry, boolean usage) {
		String skyblockId = skyblockIdOf(entry);
		if (skyblockId == null) return Optional.empty();
		if (PetAcquisitionStore.isClassified(skyblockId) && !PetAcquisitionStore.hasMethod(skyblockId, PetAcquisitionStore.Method.DROP)) {
			return Optional.empty();
		}

		NeuRepoManager.RepositoryData data = NeuRepoManager.getInstance().getRepositoryDataOrNull();
		if (data == null) return Optional.empty();
		var item = data.repository().getItems().getItems().get(skyblockId);
		boolean book = item != null && "minecraft:enchanted_book".equals(item.getMinecraftItemId());
		boolean pet = !book && item != null && PetStatResolver.identifyPet(item.getNbttag()).isPresent();

		List<SkyblockMobDropDisplay> displays = new ArrayList<>();
		for (NEUMobDropRecipe recipe : data.mobDropRecipes()) {
			if (recipe.getDrops() == null) continue;
			boolean dungeon = DungeonRewards.floor(recipe) != null;
			if (usage && !dungeon) continue;
			boolean dropsThisItem = dungeon && recipe.getDropsFrom() != null
				&& skyblockId.equals(recipe.getDropsFrom().getSkyblockItemId());
			for (io.github.moulberry.repo.data.NEUMobDropRecipe.Drop drop : recipe.getDrops()) {
				if (drop.getDropItem() == null) continue;
				if (SkyblockItemResolver.isEmptySlot(drop.getDropItem())) continue;
				if ((!usage || book) && sameSkyblockItemId(skyblockId, drop.getDropItem().getItemId(), pet)) {
					dropsThisItem = true;
					break;
				}
			}
			if (!dropsThisItem) continue;
			SkyblockMobDropDisplay display = SkyblockReiPlugin.toMobDropDisplay(data.repository(), recipe);
			if (display != null) displays.add(display);
		}
		return displays.isEmpty() ? Optional.empty() : Optional.of(displays);
	}

	@Override
	public Optional<List<SkyblockMobDropDisplay>> getUsageFor(EntryStack<?> entry) {
		return findSources(entry, true);
	}

	@Override
	public Optional<List<SkyblockMobDropDisplay>> generate(ViewSearchBuilder builder) {
		if (!builder.getRecipesFor().isEmpty() || !builder.getUsagesFor().isEmpty()) return Optional.empty();
		NeuRepoManager.RepositoryData data = NeuRepoManager.getInstance().getRepositoryDataOrNull();
		if (data == null) return Optional.empty();

		List<SkyblockMobDropDisplay> displays = new ArrayList<>();
		for (NEUMobDropRecipe recipe : data.mobDropRecipes()) {
			SkyblockMobDropDisplay display = SkyblockReiPlugin.toMobDropDisplay(data.repository(), recipe);
			if (display != null) displays.add(display);
		}
		return Optional.of(displays);
	}

	/**
	 * NEU stores pet variants as IDs such as BABY_YETI;3 / BABY_YETI;4, while some
	 * mob-drop records use the base pet ID. Treat those as the same drop target so
	 * pressing R on any Baby Yeti rarity still shows the Yeti drop table.
	 */
	static boolean sameSkyblockItemId(String first, String second, boolean pet) {
		if (first == null || second == null) return false;
		if (first.equals(second)) return true;
		if (!pet) return false;
		int firstSeparator = first.indexOf(';');
		int secondSeparator = second.indexOf(';');
		if (firstSeparator < 0 && secondSeparator < 0) return false;
		String firstBase = firstSeparator >= 0 ? first.substring(0, firstSeparator) : first;
		String secondBase = secondSeparator >= 0 ? second.substring(0, secondSeparator) : second;
		return firstBase.equals(secondBase);
	}

	private static String skyblockIdOf(EntryStack<?> entry) {
		return SkyblockItemEntryDefinition.getSkyblockId(entry);
	}
}
