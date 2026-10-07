package moe.example.skyblockrecipeviewer.rei;

/** A consumable item upgrade, including upgrades where the base item stays the same. */
public record SkyblockItemUpgradeRecipe(String inputItemId, String upgradeItemId,
		String outputItemId, String description) {
}
