package moe.example.skyblockrecipeviewer.repo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Live SkyBlock market prices - deliberately only ever consumed by our OWN REI list entries
 * (see SkyblockReiPlugin.withPriceTooltip), never real inventory items (Skyblocker, if
 * installed, already covers those with its own, more detailed tooltip).
 *
 * Both markets are bulk-refreshed on the same background executor. Tooltip lookups only
 * read the most recent immutable cache snapshot.
 *
 * Two refinements added after the very first cold launch caused a noticeable freeze (both
 * bulk fetches landing at the same moment as the very first heavy 8000+ item reload):
 *  1. Auction refreshes are staggered from Bazaar refreshes on the same five-minute cadence.
 *  2. Each market is cached to disk and loaded synchronously
 *     - fast, no network - at construction time, so a fresh launch has *something* (possibly a
 *     few minutes stale) to show immediately, rather than every tooltip showing nothing until
 *     the first live fetch completes.
 *
 *  - Bazaar: Hypixel's own official public endpoint returns every bazaar-tradeable product's
 *    order book in one request. Parsed directly from the order-book arrays rather than
 *    "quick_status", per real in-game verification: "sell_summary" is actually the BUY ORDER
 *    book, "buy_summary" is actually the SELL OFFER book (swapped from what the names suggest).
 *    The highest sell_summary price is what a player receives instantly selling; the lowest
 *    buy_summary price is what a player pays instantly buying.
 *  - Auction House: Coflnet's public {@code /api/prices/neu} endpoint supplies a numeric
 *    price map keyed by NEU item ID. No token or item ID conversion is needed.
 */
public final class SkyblockPriceManager {
	private static final Logger LOGGER = LogManager.getLogger("SkyblockRecipeViewer/Price");

	private static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
	private static final String COFLNET_NEU_PRICES_URL = "https://sky.coflnet.com/api/prices/neu";
	private static final long REFRESH_INTERVAL_MINUTES = 5;
	private static final long MAX_PRICE_RESPONSE_BYTES = 32L * 1024 * 1024;

	private static final Path BAZAAR_CACHE_FILE = FabricLoader.getInstance().getGameDir()
		.resolve("skyblockrecipeviewer-bazaar-prices.json");
	private static final Path AUCTION_CACHE_FILE = FabricLoader.getInstance().getGameDir()
		.resolve("skyblockrecipeviewer-auction-bin-prices.json");
	private static final SkyblockPriceManager INSTANCE = new SkyblockPriceManager();

	public static SkyblockPriceManager getInstance() {
		return INSTANCE;
	}

	/**
	 * Either field may be NaN if that side of the order book had no active listings at all
	 * (not the same as a price of 0, which could in principle be a real - if bizarre - price)
	 * - callers should check {@link Double#isNaN} before displaying a given side.
	 */
	public record BazaarPrice(double instantBuyPrice, double instantSellPrice) {
	}

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "SkyblockRecipeViewer-PriceRefresh");
		t.setDaemon(true);
		return t;
	});

	private volatile Map<String, BazaarPrice> bazaarPrices = Map.of();
	private volatile Map<String, CachedAuctionPrice> auctionLowestBin = Map.of();

	record CachedAuctionPrice(double price, long fetchedAt) {
		static Map<String, CachedAuctionPrice> readBulkResponse(String body, long fetchedAt) {
			JsonObject prices = JsonParser.parseString(body).getAsJsonObject();
			Map<String, CachedAuctionPrice> loaded = new HashMap<>();
			for (String itemId : prices.keySet()) {
				JsonElement value = prices.get(itemId);
				if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) continue;
				double price = value.getAsDouble();
				if (Double.isFinite(price) && price >= 0) {
					loaded.put(itemId, new CachedAuctionPrice(price, fetchedAt));
				}
			}
			if (loaded.isEmpty()) throw new IllegalArgumentException("Auction House response contains no valid prices");
			return Map.copyOf(loaded);
		}
	}

	private SkyblockPriceManager() {
		loadBazaarCacheFromDisk();
		loadAuctionCacheFromDisk();

		scheduler.scheduleWithFixedDelay(this::refreshBazaarSafely, 0, REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES);
		scheduler.scheduleWithFixedDelay(this::refreshAuctionSafely,
			Math.max(1, REFRESH_INTERVAL_MINUTES / 2), REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES);
	}

	public Optional<BazaarPrice> getBazaarPrice(String skyblockId) {
		if (skyblockId == null) return Optional.empty();
		return Optional.ofNullable(bazaarPrices.get(skyblockId));
	}

	/**
	 * Forces an immediate refresh outside the normal 5-minute schedule (see class docs) -
	 * for /sbi pricereload. Submitted to the same background executor the scheduled
	 * refreshes already use, so this never blocks the calling (command/render) thread on the
	 * network request itself, and can't run concurrently with a scheduled refresh either
	 * (single-thread executor).
	 */
	public java.util.concurrent.CompletableFuture<Void> reloadBazaarAsync() {
		return java.util.concurrent.CompletableFuture.runAsync(this::refreshBazaarSafely, scheduler);
	}

	/** Refreshes the complete Auction House price snapshot on the existing background executor. */
	public java.util.concurrent.CompletableFuture<Void> reloadAuctionAsync() {
		return java.util.concurrent.CompletableFuture.runAsync(this::refreshAuctionSafely, scheduler);
	}

	/**
	 * Looks up the NEU item ID directly in the most recent Auction House cache snapshot.
	 */
	public Optional<Double> getAuctionLowestBin(String skyblockId) {
		if (skyblockId == null) return Optional.empty();
		CachedAuctionPrice cached = auctionLowestBin.get(skyblockId);
		return cached == null || cached.price() <= 0 ? Optional.empty() : Optional.of(cached.price());
	}

	// ---- Disk cache ----

	private void loadBazaarCacheFromDisk() {
		try {
			if (!Files.exists(BAZAAR_CACHE_FILE)) return;
			JsonObject root = JsonParser.parseString(Files.readString(BAZAAR_CACHE_FILE, StandardCharsets.UTF_8))
				.getAsJsonObject();
			bazaarPrices = readBazaarPrices(root);
			LOGGER.info("Loaded {} Bazaar prices from {} (pending live refresh).", bazaarPrices.size(), BAZAAR_CACHE_FILE);
		} catch (Exception e) {
			LOGGER.warn("Could not read local Bazaar price cache ({}).", e.toString());
		}
	}

	private void loadAuctionCacheFromDisk() {
		try {
			if (!Files.exists(AUCTION_CACHE_FILE)) return;
			JsonObject root = JsonParser.parseString(Files.readString(AUCTION_CACHE_FILE, StandardCharsets.UTF_8))
				.getAsJsonObject();
			auctionLowestBin = readAuctionPrices(root);
			LOGGER.info("Loaded {} Auction House prices from {} (pending live refresh).", auctionLowestBin.size(), AUCTION_CACHE_FILE);
		} catch (Exception e) {
			LOGGER.warn("Could not read local Auction House price cache ({}).", e.toString());
		}
	}

	private static Map<String, BazaarPrice> readBazaarPrices(JsonObject prices) {
		Map<String, BazaarPrice> loaded = new HashMap<>();
		for (String itemId : prices.keySet()) {
			try {
				JsonObject entry = prices.getAsJsonObject(itemId);
				double buy = entry.has("buy") ? entry.get("buy").getAsDouble() : Double.NaN;
				double sell = entry.has("sell") ? entry.get("sell").getAsDouble() : Double.NaN;
				loaded.put(itemId, new BazaarPrice(buy, sell));
			} catch (Exception perItem) {
				LOGGER.warn("Skipping corrupt cached Bazaar entry for {} ({}).", itemId, perItem.toString());
			}
		}
		return Map.copyOf(loaded);
	}

	private static Map<String, CachedAuctionPrice> readAuctionPrices(JsonObject prices) {
		Map<String, CachedAuctionPrice> loaded = new HashMap<>();
		for (String itemId : prices.keySet()) {
			try {
				JsonElement value = prices.get(itemId);
				if (value.isJsonObject()) {
					JsonObject entry = value.getAsJsonObject();
					loaded.put(itemId, new CachedAuctionPrice(entry.get("price").getAsDouble(),
						entry.get("fetchedAt").getAsLong()));
				}
			} catch (Exception perItem) {
				LOGGER.warn("Skipping corrupt cached auction entry for {} ({}).", itemId, perItem.toString());
			}
		}
		return Map.copyOf(loaded);
	}

	private void writeBazaarCacheToDisk() {
		try {
			JsonObject root = new JsonObject();
			for (Map.Entry<String, BazaarPrice> entry : bazaarPrices.entrySet()) {
				JsonObject value = new JsonObject();
				if (!Double.isNaN(entry.getValue().instantBuyPrice())) value.addProperty("buy", entry.getValue().instantBuyPrice());
				if (!Double.isNaN(entry.getValue().instantSellPrice())) value.addProperty("sell", entry.getValue().instantSellPrice());
				root.add(entry.getKey(), value);
			}
			Files.writeString(BAZAAR_CACHE_FILE, root.toString(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			LOGGER.error("Failed to write local Bazaar price cache.", e);
		}
	}

	private void writeAuctionCacheToDisk() {
		try {
			JsonObject root = new JsonObject();
			for (Map.Entry<String, CachedAuctionPrice> entry : auctionLowestBin.entrySet()) {
				JsonObject value = new JsonObject();
				value.addProperty("price", entry.getValue().price());
				value.addProperty("fetchedAt", entry.getValue().fetchedAt());
				root.add(entry.getKey(), value);
			}
			Files.writeString(AUCTION_CACHE_FILE, root.toString(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			LOGGER.error("Failed to write local Auction House price cache.", e);
		}
	}

	// ---- Live refresh ----

	private void refreshBazaarSafely() {
		try {
			refreshBazaar();
		} catch (Exception e) {
			LOGGER.warn("Failed to refresh Bazaar prices: {}", e.toString());
		}
	}

	private void refreshAuctionSafely() {
		try {
			refreshAuctionLowestBins();
		} catch (Exception e) {
			LOGGER.warn("Failed to refresh Auction House lowest-BIN prices: {}", e.toString());
		}
	}

	private void refreshAuctionLowestBins() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(COFLNET_NEU_PRICES_URL))
			.timeout(Duration.ofSeconds(15)).GET().build();
		HttpResponse<String> response = http.send(request,
			HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), MAX_PRICE_RESPONSE_BYTES));
		if (response.statusCode() != 200) {
			LOGGER.warn("Coflnet bulk price endpoint returned HTTP {}", response.statusCode());
			return;
		}
		Map<String, CachedAuctionPrice> refreshed = CachedAuctionPrice.readBulkResponse(
			response.body(), System.currentTimeMillis());
		auctionLowestBin = refreshed;
		LOGGER.info("Refreshed {} Auction House prices from Coflnet.", refreshed.size());
		writeAuctionCacheToDisk();
	}

	private void refreshBazaar() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(BAZAAR_URL)).timeout(Duration.ofSeconds(15)).GET().build();
		HttpResponse<String> response = http.send(request,
			HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), MAX_PRICE_RESPONSE_BYTES));
		if (response.statusCode() != 200) {
			LOGGER.warn("Bazaar API returned HTTP {}", response.statusCode());
			return;
		}
		JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
		if (!root.has("success") || !root.get("success").getAsBoolean() || !root.has("products")) {
			LOGGER.warn("Bazaar API response missing success/products.");
			return;
		}
		JsonObject products = root.getAsJsonObject("products");
		Map<String, BazaarPrice> refreshed = new HashMap<>();
		int count = 0;
		for (String itemId : products.keySet()) {
			try {
				JsonObject product = products.getAsJsonObject(itemId);
				// sell_summary = the BUY ORDER book (what a player receives instantly selling);
				// buy_summary = the SELL OFFER book (what a player pays instantly buying).
				double instantSellPrice = highestPricePerUnit(product, "sell_summary");
				double instantBuyPrice = lowestPricePerUnit(product, "buy_summary");
				if (Double.isNaN(instantBuyPrice) && Double.isNaN(instantSellPrice)) continue;
				refreshed.put(itemId, new BazaarPrice(instantBuyPrice, instantSellPrice));
				count++;
			} catch (Exception perItem) {
				LOGGER.warn("Skipping malformed Bazaar product {}: {}", itemId, perItem.toString());
			}
		}
		bazaarPrices = Map.copyOf(refreshed);
		LOGGER.info("Refreshed {} Bazaar product prices.", count);
		writeBazaarCacheToDisk();
	}

	/** @return the lowest "pricePerUnit" in product's {@code arrayKey} order-book array, or NaN if absent/empty. */
	private static double lowestPricePerUnit(JsonObject product, String arrayKey) {
		if (!product.has(arrayKey)) return Double.NaN;
		JsonArray array = product.getAsJsonArray(arrayKey);
		double lowest = Double.NaN;
		for (JsonElement element : array) {
			JsonObject entry = element.getAsJsonObject();
			if (!entry.has("pricePerUnit")) continue;
			double price = entry.get("pricePerUnit").getAsDouble();
			if (Double.isNaN(lowest) || price < lowest) lowest = price;
		}
		return lowest;
	}

	private static double highestPricePerUnit(JsonObject product, String arrayKey) {
		if (!product.has(arrayKey)) return Double.NaN;
		JsonArray array = product.getAsJsonArray(arrayKey);
		double highest = Double.NaN;
		for (JsonElement element : array) {
			JsonObject entry = element.getAsJsonObject();
			if (!entry.has("pricePerUnit")) continue;
			double price = entry.get("pricePerUnit").getAsDouble();
			if (Double.isNaN(highest) || price > highest) highest = price;
		}
		return highest;
	}
}
