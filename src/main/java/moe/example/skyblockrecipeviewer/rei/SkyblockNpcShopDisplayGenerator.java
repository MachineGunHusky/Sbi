package moe.example.skyblockrecipeviewer.rei;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import me.shedaniel.rei.api.client.registry.display.DynamicDisplayGenerator;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import io.github.moulberry.repo.NEURepository;
import io.github.moulberry.repo.data.NEUIngredient;
import moe.example.skyblockrecipeviewer.repo.NeuRepoManager;
import moe.example.skyblockrecipeviewer.repo.NpcShopIndex;
import moe.example.skyblockrecipeviewer.repo.PetAcquisitionStore;
import moe.example.skyblockrecipeviewer.repo.SkyblockItemResolver;
import net.minecraft.world.item.ItemStack;

/**
 * Live display generator for NPC shop purchases, same architecture/motivation as
 * SkyblockCraftingDisplayGenerator (see that class's own docs for the full reasoning): computes
 * displays on demand from NpcShopIndex's current cache rather than requiring a REI reload to
 * have happened after the index finished building, so this correctly answers lookups made
 * right after joining a server even if no reload has re-run registerDisplays() since.
 *
 * "SKYBLOCK_COIN" cost-slot entries are deliberately excluded from both input-resolution (they
 * become the display's separate coin-cost field, not an ingredient slot - see
 * SkyblockNpcShopDisplay) and from getUsageFor's item-cost matching (coins aren't a resolvable
 * SkyBlock item in this mod's own entry system, so they can never accidentally match a real
 * item's "what else uses this" lookup).
 */
public final class SkyblockNpcShopDisplayGenerator implements DynamicDisplayGenerator<SkyblockNpcShopDisplay> {

	public static final SkyblockNpcShopDisplayGenerator INSTANCE = new SkyblockNpcShopDisplayGenerator();

	private static final String COIN_ID = "SKYBLOCK_COIN";

	private SkyblockNpcShopDisplayGenerator() {
	}

	@Override
	public Optional<List<SkyblockNpcShopDisplay>> getRecipeFor(EntryStack<?> entry) {
		try {
			String skyblockId = skyblockIdOf(entry);
			if (skyblockId == null) return Optional.empty();
			return nonEmpty(buildDisplays(shopEntry -> sameItemId(skyblockId, shopEntry.resultItemId())));
		} catch (Throwable ignored) {
			// NPC-shop data must never be able to break REI's other recipe/usage categories.
			return Optional.empty();
		}
	}

	@Override
	public Optional<List<SkyblockNpcShopDisplay>> getUsageFor(EntryStack<?> entry) {
		try {
			String skyblockId = skyblockIdOf(entry);
			if (skyblockId == null) return Optional.empty();
			return nonEmpty(buildDisplays(shopEntry -> shopEntry.cost().stream()
				.anyMatch(slot -> sameItemId(skyblockId, slot.itemId()))));
		} catch (Throwable ignored) {
			return Optional.empty();
		}
	}

	/**
	 * Same choice as SkyblockCraftingDisplayGenerator: browsing the category tab directly
	 * (with nothing focused/searched) shows nothing - this generator only answers "recipes
	 * for X"/"usages of X" lookups.
	 */
	@Override
	public Optional<List<SkyblockNpcShopDisplay>> generate(ViewSearchBuilder builder) {
		return Optional.empty();
	}

	private static String skyblockIdOf(EntryStack<?> entry) {
		return SkyblockItemEntryDefinition.getSkyblockId(entry);
	}

	private static boolean sameItemId(String left, String right) {
		if (left == null || right == null) return false;
		return PetAcquisitionStore.baseId(left).equalsIgnoreCase(PetAcquisitionStore.baseId(right));
	}

	private static <T> Optional<List<T>> nonEmpty(List<T> list) {
		return list.isEmpty() ? Optional.empty() : Optional.of(list);
	}

	private static List<SkyblockNpcShopDisplay> buildDisplays(java.util.function.Predicate<NpcShopIndex.Entry> filter) {
		NeuRepoManager manager = NeuRepoManager.getInstance();
		NEURepository repository = manager.getLoadedRepoOrNull();
		if (repository == null) return List.of();
		List<SkyblockNpcShopDisplay> displays = new ArrayList<>();
		for (NpcShopIndex.Entry shopEntry : NpcShopIndex.getEntries(manager)) {
			try {
				if (!filter.test(shopEntry)) continue;
				SkyblockNpcShopDisplay display = toDisplay(repository, shopEntry);
				if (display != null) displays.add(display);
			} catch (Throwable ignored) {
				// A malformed NPC-shop entry is skipped rather than aborting the whole REI view.
			}
		}
		return displays;
	}

	static SkyblockNpcShopDisplay toDisplay(NEURepository repository, NpcShopIndex.Entry shopEntry) {
		long coinCost = 0;
		List<EntryIngredient> inputs = new ArrayList<>();
		for (NpcShopIndex.CostSlot slot : shopEntry.cost()) {
			if (COIN_ID.equalsIgnoreCase(slot.itemId())) {
				coinCost += slot.count();
				continue;
			}
			ItemStack costStack = SkyblockItemResolver.resolve(repository,
				NEUIngredient.fromString(slot.itemId() + ":" + slot.count()));
			if (costStack.isEmpty()) continue;
			inputs.add(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, costStack)));
		}

		ItemStack outputStack = SkyblockItemResolver.resolve(repository,
			NEUIngredient.fromString(shopEntry.resultItemId() + ":" + shopEntry.resultCount()));
		if (outputStack.isEmpty()) return null;
		List<EntryIngredient> outputs = List.of(EntryIngredient.of(EntryStack.of(SkyblockItemEntryDefinition.TYPE, outputStack)));

		return new SkyblockNpcShopDisplay(inputs, outputs, coinCost);
	}
}
