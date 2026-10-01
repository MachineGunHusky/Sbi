package moe.example.skyblockrecipeviewer.rei;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.moulberry.repo.NEURepository;
import io.github.moulberry.repo.data.NEUItem;
import moe.example.skyblockrecipeviewer.repo.ItemCategoryResolver;
import moe.example.skyblockrecipeviewer.repo.ItemRarityResolver;
import moe.example.skyblockrecipeviewer.repo.NeuRepoManager;
import moe.example.skyblockrecipeviewer.repo.reforge.ReforgeData;
import moe.example.skyblockrecipeviewer.repo.reforge.ReforgeStore;

/**
 * Shared "what reforges apply to this item" matching logic, used by both {@link
 * SkyblockReforgeDisplayGenerator} (free Blacksmith reforges) and {@link
 * SkyblockReforgeStoneDisplayGenerator} (reforge-stone reforges) - the two are really the same
 * lookup filtered to opposite halves of {@link ReforgeStore#getAllReforges()} (reforgeStoneId()
 * null vs. non-null), so this keeps that shared part from drifting into two copies.
 */
final class ReforgeLookupSupport {
	private ReforgeLookupSupport() {
	}

	/**
 * Accessory categories covered by the "EQUIPMENT" umbrella in reforge data.
	 */
	private static final Set<String> EQUIPMENT_CATEGORIES =
		Set.of("BELT", "GLOVES", "CLOAK", "NECKLACE", "BRACELET");

	/**
 * Armor categories covered by the "ARMOR" umbrella in reforge data.
	 */
	private static final Set<String> ARMOR_CATEGORIES =
		Set.of("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS");

	/**
	 * Every reforge (both free and stone-based - callers filter by {@code reforgeStoneId()} for
	 * their own category) that's eligible for {@code skyblockId}: the item itself IS a reforge
	 * stone, the item is explicitly allow-listed by id (a unique-item-specific reforge), or the
 * item's own category matches one of the reforge's eligible item types, including umbrella
 * categories expanded by {@link #EQUIPMENT_CATEGORIES} and {@link #ARMOR_CATEGORIES}.
	 */
	static Set<ReforgeData> matchesFor(NeuRepoManager manager, String skyblockId) {
		ReforgeStore store = ReforgeStore.getInstance();
		Set<ReforgeData> matches = new LinkedHashSet<>();
		ReforgeData stoneReforge = store.getByReforgeStone(skyblockId);
		if (stoneReforge != null) matches.add(stoneReforge);
		matches.addAll(store.getByInternalName(skyblockId));
		String category = ItemCategoryResolver.getCategory(manager.getRepoDir(), skyblockId);
		if (category != null) {
			matches.addAll(store.getByItemType(category));
			if (EQUIPMENT_CATEGORIES.contains(category)) {
				matches.addAll(store.getByItemType("EQUIPMENT"));
			}
			if (ARMOR_CATEGORIES.contains(category)) {
				matches.addAll(store.getByItemType("ARMOR"));
			}
		}
		return matches;
	}

	/**
	 * The specific rarity tiers to highlight/narrow a lookup down to: the item's own natural
	 * rarity, plus one tier up (what a Recombobulator - extremely common - would bump it to).
	 * Empty (meaning "show every rarity, don't narrow") if:
	 *  - the item's rarity can't be determined at all, in which case callers should fall back
	 *    to showing every rarity rather than guessing, or
	 *  - {@code skyblockId} is itself one of the reforge stones being looked up (confirmed bug:
	 *    viewing "usages of Blazen Sphere" itself was narrowing to Blazen Sphere's OWN rarity -
	 *    "RARE REFORGE STONE" per its own lore - which is a coincidence, not a meaningful
	 *    narrowing. A reforge stone's own rarity has nothing to do with which tier of its
	 *    *applied* reforge someone would want highlighted; that narrowing only makes sense when
	 *    looking up reforges for an actual piece of equipment the reforge would go on.
	 */
	static List<String> highlightRaritiesFor(NEURepository repository, String skyblockId) {
		if (ReforgeStore.getInstance().getByReforgeStone(skyblockId) != null) return List.of();
		NEUItem targetItem = repository.getItems().getItemBySkyblockId(skyblockId);
		String rarity = ItemRarityResolver.getRarity(targetItem);
		if (rarity == null) return List.of();
		String recombobulated = ItemRarityResolver.oneTierUp(rarity);
		return recombobulated != null ? List.of(rarity, recombobulated) : List.of(rarity);
	}
}
