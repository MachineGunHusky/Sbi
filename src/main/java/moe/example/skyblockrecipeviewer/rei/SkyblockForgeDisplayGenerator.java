package moe.example.skyblockrecipeviewer.rei;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import io.github.moulberry.repo.data.NEUForgeRecipe;
import io.github.moulberry.repo.data.NEUIngredient;
import me.shedaniel.rei.api.client.registry.display.DynamicDisplayGenerator;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.entry.EntryStack;
import moe.example.skyblockrecipeviewer.repo.NeuRepoManager;
import moe.example.skyblockrecipeviewer.repo.PetAcquisitionStore;
import moe.example.skyblockrecipeviewer.repo.SkyblockItemResolver;

/**
 * Live "recipe for X" / "usage of X" lookups for forge recipes - see
 * {@link SkyblockCraftingDisplayGenerator}'s class docs for why this pattern is needed.
 */
public final class SkyblockForgeDisplayGenerator implements DynamicDisplayGenerator<SkyblockForgeDisplay> {

	public static final SkyblockForgeDisplayGenerator INSTANCE = new SkyblockForgeDisplayGenerator();

	private SkyblockForgeDisplayGenerator() {
	}

	@Override
	public Optional<List<SkyblockForgeDisplay>> getRecipeFor(EntryStack<?> entry) {
		String skyblockId = skyblockIdOf(entry);
		if (skyblockId == null) return Optional.empty();
		if (PetAcquisitionStore.isClassified(skyblockId)
			&& !PetAcquisitionStore.hasMethod(skyblockId, PetAcquisitionStore.Method.FORGE)) {
			return Optional.empty();
		}
		return nonEmpty(buildDisplays(recipe -> matches(recipe.getOutputStack(), skyblockId)));
	}

	@Override
	public Optional<List<SkyblockForgeDisplay>> getUsageFor(EntryStack<?> entry) {
		String skyblockId = skyblockIdOf(entry);
		if (skyblockId == null) return Optional.empty();
		if (PetAcquisitionStore.isClassified(skyblockId)
			&& !PetAcquisitionStore.hasMethod(skyblockId, PetAcquisitionStore.Method.FORGE)) {
			return Optional.empty();
		}
		return nonEmpty(buildDisplays(recipe -> anyInputMatches(recipe, skyblockId)));
	}

	/** See {@link SkyblockCraftingDisplayGenerator#nonEmpty}'s javadoc - same reasoning. */
	private static <T> Optional<List<T>> nonEmpty(List<T> list) {
		return list.isEmpty() ? Optional.empty() : Optional.of(list);
	}

	@Override
	public Optional<List<SkyblockForgeDisplay>> generate(ViewSearchBuilder builder) {
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

	private static boolean matches(NEUIngredient ingredient, String skyblockId) {
		if (SkyblockItemResolver.isEmptySlot(ingredient)) return false;
		return PetAcquisitionStore.baseId(skyblockId).equalsIgnoreCase(
			PetAcquisitionStore.baseId(ingredient.getItemId()));
	}

	private static boolean anyInputMatches(NEUForgeRecipe recipe, String skyblockId) {
		if (recipe.getInputs() == null) return false;
		for (NEUIngredient ingredient : recipe.getInputs()) {
			if (matches(ingredient, skyblockId)) return true;
		}
		return false;
	}

	private static List<SkyblockForgeDisplay> buildDisplays(Predicate<NEUForgeRecipe> filter) {
		NeuRepoManager.RepositoryData data = NeuRepoManager.getInstance().getRepositoryDataOrNull();
		if (data == null) return List.of();

		List<SkyblockForgeDisplay> displays = new ArrayList<>();
		for (NEUForgeRecipe recipe : data.forgeRecipes()) {
			if (!filter.test(recipe)) continue;
			SkyblockForgeDisplay display = SkyblockReiPlugin.toForgeDisplay(data.repository(), recipe);
			if (display != null) displays.add(display);
		}
		return displays;
	}
}
