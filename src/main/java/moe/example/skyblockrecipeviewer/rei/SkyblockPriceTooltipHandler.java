package moe.example.skyblockrecipeviewer.rei;

import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

import moe.example.skyblockrecipeviewer.repo.SkyblockPriceManager;

/**
 * Adds live Bazaar and Auction House prices to tooltips for this mod's REI item stacks.
 * The stack marker keeps price lines scoped to those entries.
 */
public final class SkyblockPriceTooltipHandler {
	private SkyblockPriceTooltipHandler() {
	}

	public static void register() {
		ItemTooltipCallback.EVENT.register(SkyblockPriceTooltipHandler::onTooltip);
	}

	private static void onTooltip(ItemStack stack, Item.TooltipContext context, TooltipFlag flag,
			List<Component> lines) {
		String skyblockId = SkyblockItemEntryDefinition.getSkyblockId(stack);
		if (skyblockId == null) return;

		SkyblockPriceManager prices = SkyblockPriceManager.getInstance();
		List<String> candidateIds = priceLookupIds(skyblockId, stack);

		Optional<SkyblockPriceManager.BazaarPrice> bazaar = Optional.empty();
		for (String id : candidateIds) {
			bazaar = prices.getBazaarPrice(id);
			if (bazaar.isPresent()) break;
		}
		if (bazaar.isPresent()) {
			// Either side can independently be NaN (no active listings on that side) - see
			// BazaarPrice's own docs.
			SkyblockPriceManager.BazaarPrice price = bazaar.get();
			boolean hasBuy = !Double.isNaN(price.instantBuyPrice());
			boolean hasSell = !Double.isNaN(price.instantSellPrice());
			if (hasBuy || hasSell) {
				lines.add(Component.empty());
				lines.add(Component.literal("Bazaar").withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true)));
				if (hasBuy) {
					lines.add(Component.literal("  Buy: " + RecipeFormatting.coins(price.instantBuyPrice()) + " coins")
						.withStyle(ChatFormatting.GRAY));
				}
				if (hasSell) {
					lines.add(Component.literal("  Sell: " + RecipeFormatting.coins(price.instantSellPrice()) + " coins")
						.withStyle(ChatFormatting.GRAY));
				}
			}
		}

		Optional<Double> auctionLowestBin = Optional.empty();
		for (String id : candidateIds) {
			auctionLowestBin = prices.getAuctionLowestBin(id);
			if (auctionLowestBin.isPresent()) break;
		}
		if (auctionLowestBin.isPresent()) {
			lines.add(Component.literal("Auction House").withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true)));
			lines.add(Component.literal("  Lowest BIN: " + RecipeFormatting.coins(auctionLowestBin.get()) + " coins")
				.withStyle(ChatFormatting.GRAY));
		}
	}

	/**
	 * Most items' repo id and real Bazaar/Auction product id are identical, so the first
	 * candidate (the id itself) is all that's ever needed. Attribute Shards and Enchanted
	 * Books are confirmed exceptions - see {@link #shardBazaarAlias} and
	 * {@link #enchantedBookBazaarAlias} - so for those, a second candidate is also tried.
	 */
	private static List<String> priceLookupIds(String skyblockId, ItemStack stack) {
		if (skyblockId.startsWith("ATTRIBUTE_SHARD_")) {
			String alias = shardBazaarAlias(stack);
			return alias != null ? List.of(skyblockId, alias) : List.of(skyblockId);
		}
		String enchantAlias = enchantedBookBazaarAlias(skyblockId, stack);
		if (enchantAlias != null) {
			return List.of(skyblockId, enchantAlias);
		}
		return List.of(skyblockId);
	}

	/**
	 * Confirmed directly against a real repo file (ICE_COLD;1.json: {@code "itemid":
	 * "minecraft:enchanted_book"}, {@code "internalname": "ICE_COLD;1"}) cross-referenced
	 * against real live Bazaar data (product id {@code "ENCHANTMENT_ICE_COLD_1"}): the repo
	 * names an enchanted book as just {@code <ENCHANT_NAME>;<LEVEL>}, with no
	 * "ENCHANTMENT_" prefix at all, while the real Bazaar/Auction product id has that prefix
	 * and joins the level with an underscore instead of a semicolon - a plain, direct string
	 * transform, no display-name derivation needed.
	 *
	 * Scoped to enchanted books specifically (checking the resolved base item, not just
	 * "does the id contain a semicolon") because pets use that exact same {@code NAME;TIER}
	 * id shape (e.g. {@code SILVERFISH;3}) - but Coflnet's bulk data already uses that
	 * literal format for pets, so applying this same transform there would break, not fix,
	 * pet price lookups.
	 */
	private static String enchantedBookBazaarAlias(String skyblockId, ItemStack stack) {
		if (stack.getItem() != Items.ENCHANTED_BOOK) return null;
		int semicolon = skyblockId.indexOf(';');
		if (semicolon <= 0 || semicolon == skyblockId.length() - 1) return null;
		String enchantName = skyblockId.substring(0, semicolon);
		String level = skyblockId.substring(semicolon + 1);
		return "ENCHANTMENT_" + enchantName + "_" + level;
	}

	/**
	 * Confirmed against a real repo item file (ATTRIBUTE_SHARD_REBORN;1.json, displayname
	 * "Prince Shard") cross-referenced against real live Bazaar data (which has products like
	 * "SHARD_BAL", "SHARD_DODO", "SHARD_MEGALITH"): the repo's internal id names an Attribute
	 * Shard by the ATTRIBUTE it grants ({@code ATTRIBUTE_SHARD_<ATTRIBUTE>;<TIER>}), but the
	 * real Bazaar product for that exact same item is named after the MOB it drops from
	 * instead (e.g. "SHARD_PRINCE") - a completely different naming scheme with no shared
	 * substring, so it can only be derived from the item's own display name, not the id
	 * itself. The display name conveniently already reads "<Mob> Shard" (matching the repo's
	 * own displayname field exactly), so stripping the trailing " Shard" and uppercasing gives
	 * the real Bazaar id directly - no hardcoded attribute-to-mob mapping table needed.
	 */
	private static String shardBazaarAlias(ItemStack stack) {
		String name = stack.getHoverName().getString().trim();
		if (!name.endsWith(" Shard")) return null;
		String mobName = name.substring(0, name.length() - " Shard".length()).trim();
		if (mobName.isEmpty()) return null;
		return "SHARD_" + mobName.toUpperCase(java.util.Locale.ROOT).replace(' ', '_');
	}
}
