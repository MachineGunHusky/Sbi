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
import moe.example.skyblockrecipeviewer.repo.SkyblockItemResolver;

/**
 * Live "recipe for X" lookups for mob drops - see {@link SkyblockCraftingDisplayGenerator}'s
 * class docs for why this pattern is needed. "Recipe for X" here means "which mobs drop X"; a
 * mob isn't itself an item, so there's no equivalent "usage of X" - {@link #getUsageFor} always
 * returns empty.
 */
public final class SkyblockMobDropDisplayGenerator implements DynamicDisplayGenerator<SkyblockMobDropDisplay> {

	public static final SkyblockMobDropDisplayGenerator INSTANCE = new SkyblockMobDropDisplayGenerator();

	private SkyblockMobDropDisplayGenerator() {
	}

	@Override
	public Optional<List<SkyblockMobDropDisplay>> getRecipeFor(EntryStack<?> entry) {
		String skyblockId = skyblockIdOf(entry);
		if (skyblockId == null) return Optional.empty();
		if (PetAcquisitionStore.isClassified(skyblockId) && !PetAcquisitionStore.hasMethod(skyblockId, PetAcquisitionStore.Method.DROP)) {
			return Optional.empty();
		}

		NeuRepoManager.RepositoryData data = NeuRepoManager.getInstance().getRepositoryDataOrNull();
		if (data == null) return Optional.empty();

		List<SkyblockMobDropDisplay> displays = new ArrayList<>();
		for (NEUMobDropRecipe recipe : data.mobDropRecipes()) {
			if (recipe.getDrops() == null) continue;
			boolean dropsThisItem = false;
			for (io.github.moulberry.repo.data.NEUMobDropRecipe.Drop drop : recipe.getDrops()) {
				if (drop.getDropItem() == null) continue;
				if (SkyblockItemResolver.isEmptySlot(drop.getDropItem())) continue;
				if (sameSkyblockItemId(skyblockId, drop.getDropItem().getItemId())) {
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
		return Optional.empty();
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
	 * Delegates to SkyblockItemEntryDefinition.getSkyblockId(EntryStack), which also resolves
	 * real gameplay items in the player's own inventory (a plain VanillaEntryTypes.ITEM entry,
	 * not one of our own), so R/U work there too, not just on items in REI's own panel.
	 */
	/**
	 * NEU stores pet variants as IDs such as BABY_YETI;3 / BABY_YETI;4, while some
	 * mob-drop records use the base pet ID. Treat those as the same drop target so
	 * pressing R on any Baby Yeti rarity still shows the Yeti drop table.
	 */
	private static boolean sameSkyblockItemId(String first, String second) {
		if (first == null || second == null) return false;
		if (first.equals(second)) return true;
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
