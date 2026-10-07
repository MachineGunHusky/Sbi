package moe.example.skyblockrecipeviewer.rei;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

import io.github.moulberry.repo.NEURepository;
import io.github.moulberry.repo.data.NEUItem;
import me.shedaniel.rei.api.client.registry.display.DynamicDisplayGenerator;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import moe.example.skyblockrecipeviewer.repo.LegacyTextParser;
import moe.example.skyblockrecipeviewer.repo.NeuRepoManager;
import moe.example.skyblockrecipeviewer.repo.SkyblockItemResolver;

/** Provides coating, gemstone chamber, and similar consumable item upgrades. */
public final class SkyblockItemUpgradeDisplayGenerator
		implements DynamicDisplayGenerator<SkyblockItemUpgradeDisplay> {
	public static final SkyblockItemUpgradeDisplayGenerator INSTANCE = new SkyblockItemUpgradeDisplayGenerator();

	private static final String COATING_ID = "DIVAN_POWDER_COATING";
	private static final String CHAMBER_ID = "GEMSTONE_CHAMBER";
	private static final List<String> DIVAN_ARMOR_IDS = List.of(
		"DIVAN_HELMET", "DIVAN_CHESTPLATE", "DIVAN_LEGGINGS", "DIVAN_BOOTS");

	private SkyblockItemUpgradeDisplayGenerator() {
	}

	@Override
	public Optional<List<SkyblockItemUpgradeDisplay>> getRecipeFor(EntryStack<?> entry) {
		String itemId = SkyblockItemEntryDefinition.getSkyblockId(entry);
		return itemId == null ? Optional.empty() : nonEmpty(buildDisplays(recipe ->
			sameId(itemId, recipe.outputItemId())));
	}

	@Override
	public Optional<List<SkyblockItemUpgradeDisplay>> getUsageFor(EntryStack<?> entry) {
		String itemId = SkyblockItemEntryDefinition.getSkyblockId(entry);
		return itemId == null ? Optional.empty() : nonEmpty(buildDisplays(recipe ->
			sameId(itemId, recipe.inputItemId()) || sameId(itemId, recipe.upgradeItemId())));
	}

	@Override
	public Optional<List<SkyblockItemUpgradeDisplay>> generate(ViewSearchBuilder builder) {
		if (!builder.getRecipesFor().isEmpty() || !builder.getUsagesFor().isEmpty()) return Optional.empty();
		return Optional.of(buildDisplays(recipe -> true));
	}

	private static <T> Optional<List<T>> nonEmpty(List<T> list) {
		return list.isEmpty() ? Optional.empty() : Optional.of(list);
	}

	private static boolean sameId(String first, String second) {
		return first != null && second != null && first.equalsIgnoreCase(second);
	}

	private static List<SkyblockItemUpgradeDisplay> buildDisplays(Predicate<SkyblockItemUpgradeRecipe> filter) {
		NeuRepoManager.RepositoryData data = NeuRepoManager.getInstance().getRepositoryDataOrNull();
		if (data == null) return List.of();

		NEURepository repository = data.repository();
		SkyblockItemResolver.invalidateCacheIfRepoChanged(repository);
		List<SkyblockItemUpgradeRecipe> recipes = new ArrayList<>();
		addCoatingUpgrades(repository, recipes);
		addGemstoneChamberUpgrades(repository, recipes);
		addPowderOfThePeopleUpgrade(repository, recipes);

		List<SkyblockItemUpgradeDisplay> displays = new ArrayList<>();
		for (SkyblockItemUpgradeRecipe recipe : recipes) {
			if (!filter.test(recipe)) continue;
			NEUItem inputItem = repository.getItems().getItemBySkyblockId(recipe.inputItemId());
			NEUItem upgradeItem = repository.getItems().getItemBySkyblockId(recipe.upgradeItemId());
			NEUItem outputItem = repository.getItems().getItemBySkyblockId(recipe.outputItemId());
			var input = SkyblockItemResolver.resolveItemStack(inputItem, recipe.inputItemId());
			var upgrade = SkyblockItemResolver.resolveItemStack(upgradeItem, recipe.upgradeItemId());
			var output = SkyblockItemResolver.resolveItemStack(outputItem, recipe.outputItemId());
			if (input.isEmpty() || upgrade.isEmpty() || output.isEmpty()) continue;
			List<EntryIngredient> inputs = List.of(
				EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, input)),
				EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, upgrade)));
			List<EntryIngredient> outputs = List.of(
				EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, output)));
			displays.add(new SkyblockItemUpgradeDisplay(recipe, inputs, outputs));
		}
		return displays;
	}

	private static void addCoatingUpgrades(NEURepository repository, List<SkyblockItemUpgradeRecipe> recipes) {
		if (!hasItem(repository, COATING_ID)) return;
		for (NEUItem item : repository.getItems().getItems().values()) {
			String itemId = item.getSkyblockItemId();
			if (itemId == null) continue;
			String name = plainName(item).toLowerCase(Locale.ROOT);
			boolean armorOrPendant = DIVAN_ARMOR_IDS.stream().anyMatch(id -> sameId(id, itemId))
				|| sameId("DIVAN_PENDANT", itemId);
			boolean drill = name.contains("drill") && !name.contains("engine")
				&& !name.contains("tank") && !name.contains("module") && !name.contains("part");
			if (!armorOrPendant && !drill) continue;
			String benefit = armorOrPendant ? "+10 Mining Fortune" : "+500 Mining Speed";
			addIfPresent(repository, recipes, itemId, COATING_ID, itemId,
				"Divan's Powder Coating: " + benefit + ". Can only be applied once.");
		}
	}

	private static void addGemstoneChamberUpgrades(NEURepository repository,
			List<SkyblockItemUpgradeRecipe> recipes) {
		if (!hasItem(repository, CHAMBER_ID)) return;
		for (String armorId : DIVAN_ARMOR_IDS) {
			addIfPresent(repository, recipes, armorId, CHAMBER_ID, armorId,
				"Consumes 1 Gemstone Chamber to unlock a gemstone slot. Repeat up to 5 times per piece.");
		}
	}

	private static void addPowderOfThePeopleUpgrade(NEURepository repository,
			List<SkyblockItemUpgradeRecipe> recipes) {
		String powderOfManyId = findByName(repository, "powder of the many");
		String powderOfPeopleId = findByName(repository, "powder of the people");
		if (powderOfManyId != null && powderOfPeopleId != null) {
			addIfPresent(repository, recipes, powderOfManyId, COATING_ID, powderOfPeopleId,
				"Combine Powder of the Many with Divan's Powder Coating in an anvil.");
		}
	}

	private static String findByName(NEURepository repository, String search) {
		for (NEUItem item : repository.getItems().getItems().values()) {
			if (item.getSkyblockItemId() != null && plainName(item).toLowerCase(Locale.ROOT).contains(search)) {
				return item.getSkyblockItemId();
			}
		}
		return null;
	}

	private static String plainName(NEUItem item) {
		return LegacyTextParser.parseLegacyText(item.getDisplayName()).getString();
	}

	private static boolean hasItem(NEURepository repository, String itemId) {
		return repository.getItems().getItemBySkyblockId(itemId) != null;
	}

	private static void addIfPresent(NEURepository repository, List<SkyblockItemUpgradeRecipe> recipes,
			String inputId, String upgradeId, String outputId, String description) {
		if (!hasItem(repository, inputId) || !hasItem(repository, upgradeId) || !hasItem(repository, outputId)) return;
		SkyblockItemUpgradeRecipe recipe = new SkyblockItemUpgradeRecipe(inputId, upgradeId, outputId, description);
		if (!recipes.contains(recipe)) recipes.add(recipe);
	}
}
