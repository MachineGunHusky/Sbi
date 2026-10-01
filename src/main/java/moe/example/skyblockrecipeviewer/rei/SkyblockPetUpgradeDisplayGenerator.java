package moe.example.skyblockrecipeviewer.rei;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import io.github.moulberry.repo.data.NEUKatUpgradeRecipe;
import me.shedaniel.rei.api.client.registry.display.DynamicDisplayGenerator;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.entry.EntryStack;
import moe.example.skyblockrecipeviewer.repo.NeuRepoManager;
import moe.example.skyblockrecipeviewer.repo.PetAcquisitionStore;
import moe.example.skyblockrecipeviewer.repo.SkyblockItemResolver;

/**
 * Live "recipe for X" / "usage of X" lookups for Kat pet upgrades - see
 * {@link SkyblockCraftingDisplayGenerator}'s class docs for why this pattern is needed.
 */
public final class SkyblockPetUpgradeDisplayGenerator
	implements DynamicDisplayGenerator<SkyblockPetUpgradeDisplay> {

	public static final SkyblockPetUpgradeDisplayGenerator INSTANCE = new SkyblockPetUpgradeDisplayGenerator();

	private SkyblockPetUpgradeDisplayGenerator() {
	}

	@Override
	public Optional<List<SkyblockPetUpgradeDisplay>> getRecipeFor(EntryStack<?> entry) {
		String skyblockId = skyblockIdOf(entry);
		if (skyblockId == null) return Optional.empty();
		return nonEmpty(buildDisplays(recipe -> matches(recipe.getOutput(), skyblockId)));
	}

	@Override
	public Optional<List<SkyblockPetUpgradeDisplay>> getUsageFor(EntryStack<?> entry) {
		String skyblockId = skyblockIdOf(entry);
		if (skyblockId == null) return Optional.empty();
		return nonEmpty(buildDisplays(recipe -> matches(recipe.getInput(), skyblockId)
			|| matchesAnyCostItem(recipe, skyblockId)));
	}

	/** See {@link SkyblockCraftingDisplayGenerator#nonEmpty}'s javadoc - same reasoning. */
	private static <T> Optional<List<T>> nonEmpty(List<T> list) {
		return list.isEmpty() ? Optional.empty() : Optional.of(list);
	}

	@Override
	public Optional<List<SkyblockPetUpgradeDisplay>> generate(ViewSearchBuilder builder) {
		if (!builder.getRecipesFor().isEmpty() || !builder.getUsagesFor().isEmpty()) return Optional.empty();
		return Optional.of(buildDisplays(recipe -> true));
	}

	/**
	 * Delegates to SkyblockItemEntryDefinition.getSkyblockId(EntryStack), which also resolves
	 * real gameplay items in the player's own inventory (a plain VanillaEntryTypes.ITEM entry,
	 * not one of our own), so R/U work there too, not just on items in REI's own panel.
	 */
	private static String skyblockIdOf(EntryStack<?> entry) {
		return SkyblockItemEntryDefinition.getSkyblockId(entry);
	}

	private static boolean matches(io.github.moulberry.repo.data.NEUIngredient ingredient, String skyblockId) {
		if (SkyblockItemResolver.isEmptySlot(ingredient)) return false;
		return PetAcquisitionStore.baseId(skyblockId).equalsIgnoreCase(
			PetAcquisitionStore.baseId(ingredient.getItemId()));
	}

	private static boolean matchesAnyCostItem(NEUKatUpgradeRecipe recipe, String skyblockId) {
		List<io.github.moulberry.repo.data.NEUIngredient> costs = recipe.getItems();
		if (costs == null) return false;
		for (io.github.moulberry.repo.data.NEUIngredient cost : costs) {
			if (matches(cost, skyblockId)) return true;
		}
		return false;
	}

	private static List<SkyblockPetUpgradeDisplay> buildDisplays(Predicate<NEUKatUpgradeRecipe> filter) {
		NeuRepoManager.RepositoryData data = NeuRepoManager.getInstance().getRepositoryDataOrNull();
		if (data == null) return List.of();

		List<SkyblockPetUpgradeDisplay> displays = new ArrayList<>();
		for (NEUKatUpgradeRecipe recipe : data.petUpgradeRecipes()) {
			if (!filter.test(recipe)) continue;
			SkyblockPetUpgradeDisplay display = SkyblockReiPlugin.toPetUpgradeDisplay(data.repository(), recipe);
			if (display != null) displays.add(display);
		}
		return displays;
	}
}
