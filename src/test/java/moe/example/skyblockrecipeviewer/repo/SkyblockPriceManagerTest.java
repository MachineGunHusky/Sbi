package moe.example.skyblockrecipeviewer.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;

import org.junit.jupiter.api.Test;

import moe.example.skyblockrecipeviewer.repo.SkyblockPriceManager.CachedAuctionPrice;

class SkyblockPriceManagerTest {
	@Test
	void parsesBulkPricesWithoutChangingNeuIds() {
		var prices = CachedAuctionPrice.readBulkResponse(
			"{\"SILVERFISH;3\":12500.5,\"ICE_COLD;1\":42,\"POTION_MANA;1\":1000}", 1234);

		assertEquals(Set.of("SILVERFISH;3", "ICE_COLD;1", "POTION_MANA;1"), prices.keySet());
		assertEquals(new CachedAuctionPrice(12500.5, 1234), prices.get("SILVERFISH;3"));
		assertEquals(new CachedAuctionPrice(42, 1234), prices.get("ICE_COLD;1"));
		assertEquals(new CachedAuctionPrice(1000, 1234), prices.get("POTION_MANA;1"));
	}

	@Test
	void skipsInvalidEntriesAndKeepsValidPrices() {
		var prices = CachedAuctionPrice.readBulkResponse("""
			{"VALID":123.5,"NO_LISTING":0,"NEGATIVE":-10,"OVERFLOW":1e9999,
			 "TEXT":"123","FLAG":true,"NULL":null,"OBJECT":{},"ARRAY":[]}
			""", 5678);

		assertEquals(Set.of("VALID", "NO_LISTING"), prices.keySet());
		assertEquals(new CachedAuctionPrice(0, 5678), prices.get("NO_LISTING"));
	}

	@Test
	void rejectsEmptyOrUnusableSnapshots() {
		assertThrows(IllegalArgumentException.class, () -> CachedAuctionPrice.readBulkResponse("{}", 0));
		assertThrows(IllegalArgumentException.class,
			() -> CachedAuctionPrice.readBulkResponse("{\"error\":\"unavailable\"}", 0));
		assertThrows(RuntimeException.class, () -> CachedAuctionPrice.readBulkResponse("[]", 0));
		assertThrows(RuntimeException.class, () -> CachedAuctionPrice.readBulkResponse("{", 0));
	}

	@Test
	void producesIndependentImmutableSnapshots() {
		var first = CachedAuctionPrice.readBulkResponse("{\"ITEM\":100}", 1);
		var second = CachedAuctionPrice.readBulkResponse("{\"ITEM\":200}", 2);

		assertEquals(new CachedAuctionPrice(100, 1), first.get("ITEM"));
		assertEquals(new CachedAuctionPrice(200, 2), second.get("ITEM"));
		assertThrows(UnsupportedOperationException.class,
			() -> first.put("OTHER", new CachedAuctionPrice(300, 3)));
	}
}
