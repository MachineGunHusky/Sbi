package moe.example.skyblockrecipeviewer.rei;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.moulberry.repo.NEURepository;
import me.shedaniel.rei.api.client.registry.display.DynamicDisplayGenerator;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.entry.EntryStack;
import moe.example.skyblockrecipeviewer.repo.NeuRepoManager;
import moe.example.skyblockrecipeviewer.repo.essence.EssenceStore;
import moe.example.skyblockrecipeviewer.repo.essence.EssenceUpgradeRecipe;

/**
 * Live "recipe for X" / "usage of X" lookups for essence upgrades - see
 * {@link SkyblockCraftingDisplayGenerator}'s class docs for why this pattern is needed. Both
 * directions resolve identically: an essence recipe's "input" and "output" are the same item
 * (see {@link SkyblockEssenceDisplay}'s class docs), so there's no meaningful distinction here
 * between "how do I upgrade this" and "what does upgrading this cost".
 */
public final class SkyblockEssenceDisplayGenerator implements DynamicDisplayGenerator<SkyblockEssenceDisplay> {

	public static final SkyblockEssenceDisplayGenerator INSTANCE = new SkyblockEssenceDisplayGenerator();

	private SkyblockEssenceDisplayGenerator() {
	}

	@Override
	public Optional<List<SkyblockEssenceDisplay>> getRecipeFor(EntryStack<?> entry) {
		return lookup(entry);
	}

	@Override
	public Optional<List<SkyblockEssenceDisplay>> getUsageFor(EntryStack<?> entry) {
		return lookup(entry);
	}

	@Override
	public Optional<List<SkyblockEssenceDisplay>> generate(ViewSearchBuilder builder) {
		if (!builder.getRecipesFor().isEmpty() || !builder.getUsagesFor().isEmpty()) return Optional.empty();
		NeuRepoManager manager = NeuRepoManager.getInstance();
		NEURepository repository = manager.getLoadedRepoOrNull();
		if (repository == null) return Optional.empty();

		List<SkyblockEssenceDisplay> displays = new ArrayList<>();
		for (EssenceUpgradeRecipe recipe : EssenceStore.getInstance().getAll()) {
			SkyblockEssenceDisplay display = SkyblockReiPlugin.toEssenceDisplay(repository, recipe);
			if (display != null) displays.add(display);
		}
		return Optional.of(displays);
	}

	private static Optional<List<SkyblockEssenceDisplay>> lookup(EntryStack<?> entry) {
		String skyblockId = skyblockIdOf(entry);
		if (skyblockId == null) return Optional.empty();

		NeuRepoManager manager = NeuRepoManager.getInstance();
		NEURepository repository = manager.getLoadedRepoOrNull();
		if (repository == null) return Optional.empty();

		List<EssenceUpgradeRecipe> recipes = EssenceStore.getInstance().getForItem(skyblockId);
		if (recipes.isEmpty()) return Optional.empty();

		List<SkyblockEssenceDisplay> displays = new ArrayList<>();
		for (EssenceUpgradeRecipe recipe : recipes) {
			SkyblockEssenceDisplay display = SkyblockReiPlugin.toEssenceDisplay(repository, recipe);
			if (display != null) displays.add(display);
		}
		return Optional.of(displays);
	}

	/**
	 * Delegates to SkyblockItemEntryDefinition.getSkyblockId(EntryStack), which also resolves
	 * real gameplay items in the player's own inventory (a plain VanillaEntryTypes.ITEM entry,
	 * not one of our own), so R/U work there too, not just on items in REI's own panel.
	 */
	private static String skyblockIdOf(EntryStack<?> entry) {
		return SkyblockItemEntryDefinition.getSkyblockId(entry);
	}
}
