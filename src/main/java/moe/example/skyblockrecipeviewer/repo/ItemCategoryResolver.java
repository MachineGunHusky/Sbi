package moe.example.skyblockrecipeviewer.repo;

import java.nio.file.Path;

/**
 * Resolves a SkyBlock item's "category" (e.g. {@code "SWORD"}, {@code "BOW"}, {@code "ARMOR"}) -
 * the same category vocabulary {@code constants/reforgestones.json}'s {@code itemTypes}
 * eligibility filters use (see {@link moe.example.skyblockrecipeviewer.repo.reforge.ReforgeData
 * ReforgeData.AllowsItemType}) - so item-type-based reforge matching can work at all.
 *
 * IMPORTANT, confirmed against a real repo file: the NEU repo's own {@code items/<id>.json}
 * files (e.g. {@code ADAPTIVE_BOOTS.json}) do NOT have a "category" field. An earlier version
 * of this class read one from there anyway - that assumption was mixed up with Hypixel's own
 * {@code /resources/skyblock/items} API response, which DOES carry a "category" per entry, but
 * is a completely different file/source. Reading a field that never existed meant this always
 * returned null, {@link moe.example.skyblockrecipeviewer.repo.reforge.ReforgeStore#getByItemType}
 * never matched anything, and the vast majority of ordinary (non-unique-item, non-reforge-stone)
 * reforges silently never showed up for any item at all.
 *
 * {@link HypixelSkinManager} already downloads and caches that Hypixel resource for skull
 * skins, and now also indexes "category" from the same entries - see its {@code populateFrom}.
 * This class just delegates to it, so category and skin data can't drift apart from being kept
 * in two separately-maintained caches of the same underlying download.
 */
public final class ItemCategoryResolver {
	private ItemCategoryResolver() {
	}

	/**
	 * @param repoDir unused - kept only so existing call sites (which pass NeuRepoManager's
	 *                repo dir, back when this read a file from it) don't need to change again.
	 * @return the item's category (e.g. {@code "SWORD"}), uppercased, or null if unknown/the
	 * Hypixel item resource hasn't loaded yet.
	 */
	public static String getCategory(Path repoDir, String skyblockId) {
		String category = HypixelSkinManager.getInstance().getCategory(skyblockId).orElse(null);
		// Hypixel's item resource categorises mining drills as DRILL, while the
		// reforge data uses PICKAXE for the mining-tool family. Treat drills as
		// pickaxes for reforge eligibility so every drill gets the same applicable
		// reforges as an ordinary pickaxe.
		if ("DRILL".equals(category)) return "PICKAXE";
		if ("FISHING_ROD".equals(category)) return "ROD";
		return category;
	}
}
