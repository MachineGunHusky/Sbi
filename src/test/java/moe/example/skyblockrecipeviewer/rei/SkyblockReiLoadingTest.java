package moe.example.skyblockrecipeviewer.rei;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import moe.example.skyblockrecipeviewer.repo.SkyblockItemCache;
import org.junit.jupiter.api.Test;

class SkyblockReiLoadingTest {
	@Test
	void stopsDecodingAfterTheTickBudgetAndResumesAtTheNextItem() {
		List<Integer> source = IntStream.range(0, 20).boxed().toList();
		List<Integer> decoded = new ArrayList<>();
		AtomicLong clock = new AtomicLong();
		int next = SkyblockReiPlugin.processCacheBatch(source, 0, value -> {
			decoded.add(value);
			clock.addAndGet(800_000);
		}, clock::get);
		assertEquals(3, next);
		assertEquals(List.of(0, 1, 2), decoded);
		int resumed = SkyblockReiPlugin.processCacheBatch(source, next, value -> {
			decoded.add(value);
			clock.addAndGet(800_000);
		}, clock::get);
		assertEquals(6, resumed);
		assertEquals(List.of(0, 1, 2, 3, 4, 5), decoded);
	}

	@Test
	void retainsTheItemLimitEvenWhenTheClockDoesNotAdvance() {
		List<Integer> source = IntStream.range(0, 200).boxed().toList();
		List<Integer> decoded = new ArrayList<>();
		assertEquals(100, SkyblockReiPlugin.processCacheBatch(source, 0, decoded::add, () -> 0L));
		assertEquals(source.subList(0, 100), decoded);
	}

	@Test
	void aSlowItemMakesProgressWithoutStartingAnotherItemInTheSameTick() {
		List<Integer> decoded = new ArrayList<>();
		AtomicLong clock = new AtomicLong();
		assertEquals(1, SkyblockReiPlugin.processCacheBatch(List.of(0, 1), 0, value -> {
			decoded.add(value);
			clock.addAndGet(10_000_000);
		}, clock::get));
		assertEquals(List.of(0), decoded);
	}

	@Test
	void drainsTheLoggedRepoSizeExactlyOnceAcrossBudgetedTicks() {
		List<Integer> source = IntStream.range(0, 8943).boxed().toList();
		List<Integer> decoded = new ArrayList<>();
		AtomicLong clock = new AtomicLong();
		int index = 0;
		while (index < source.size()) {
			int previous = index;
			index = SkyblockReiPlugin.processCacheBatch(source, index, value -> {
				decoded.add(value);
				clock.addAndGet(350_000);
			}, clock::get);
			assertTrue(index > previous);
		}
		assertEquals(source, decoded);
		assertEquals(index, SkyblockReiPlugin.processCacheBatch(source, index,
			value -> fail("Completed cache must not decode again"), clock::get));
	}

	@Test
	void coldResolutionCannotStartBeforeCacheReadingOrDecodingCompletes() throws Exception {
		Field complete = SkyblockReiPlugin.class.getDeclaredField("cacheReadComplete");
		Field pending = SkyblockReiPlugin.class.getDeclaredField("pendingCachedStacks");
		complete.setAccessible(true);
		pending.setAccessible(true);
		boolean previousComplete = complete.getBoolean(null);
		Object previousPending = pending.get(null);
		var push = SkyblockReiPlugin.class.getDeclaredMethod("tryLivePushOnClient");
		push.setAccessible(true);
		try {
			complete.setBoolean(null, false);
			pending.set(null, List.of());
			assertDoesNotThrow(() -> push.invoke(null));
			complete.setBoolean(null, true);
			pending.set(null, List.of(new SkyblockItemCache.CachedStack("TEST_ITEM", "{}")));
			assertDoesNotThrow(() -> push.invoke(null));
		} finally {
			complete.setBoolean(null, previousComplete);
			pending.set(null, previousPending);
		}
	}
}
