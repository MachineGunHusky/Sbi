package moe.example.skyblockrecipeviewer.rei;

import java.util.List;
import java.util.Optional;

import moe.example.skyblockrecipeviewer.repo.SkyblockPriceManager;
import net.minecraft.network.chat.Component;

final class RecipeCostTooltip {
	private RecipeCostTooltip() {
	}

	record Cost(List<String> priceIds, double amount) {
	}

	static List<Component> lines(String title, List<Cost> costs) {
		return List.of(Component.literal(title),
			Component.literal("Insta-buy: " + totalCost(costs, true)),
			Component.literal("Buy order: " + totalCost(costs, false)));
	}

	private static String totalCost(List<Cost> costs, boolean instantBuy) {
		SkyblockPriceManager prices = SkyblockPriceManager.getInstance();
		double total = 0;
		boolean missingPrice = false;
		for (Cost cost : costs) {
			if (cost.priceIds().contains("SKYBLOCK_COIN")) {
				total += cost.amount();
				continue;
			}
			double unitPrice = Double.NaN;
			for (String itemId : cost.priceIds()) {
				Optional<SkyblockPriceManager.BazaarPrice> bazaar = prices.getBazaarPrice(itemId);
				if (bazaar.isPresent()) {
					unitPrice = instantBuy ? bazaar.get().instantBuyPrice() : bazaar.get().instantSellPrice();
					if (Double.isFinite(unitPrice)) break;
				}
			}
			if (!Double.isFinite(unitPrice)) {
				for (String itemId : cost.priceIds()) {
					unitPrice = prices.getAuctionLowestBin(itemId).orElse(Double.NaN);
					if (Double.isFinite(unitPrice)) break;
				}
			}
			if (!Double.isFinite(unitPrice)) {
				missingPrice = true;
			} else {
				total += cost.amount() * unitPrice;
			}
		}
		return missingPrice ? "Unavailable" : RecipeFormatting.coins(total) + " coins";
	}
}
