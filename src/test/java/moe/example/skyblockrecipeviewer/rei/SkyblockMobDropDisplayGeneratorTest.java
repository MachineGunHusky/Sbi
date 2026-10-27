package moe.example.skyblockrecipeviewer.rei;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SkyblockMobDropDisplayGeneratorTest {
	@Test
	void enchantmentLevelsRemainExactButPetRaritiesCanShareSources() {
		assertFalse(SkyblockMobDropDisplayGenerator.sameSkyblockItemId("ULTIMATE_LEGION;5", "ULTIMATE_LEGION;1", false));
		assertTrue(SkyblockMobDropDisplayGenerator.sameSkyblockItemId("ULTIMATE_LEGION;1", "ULTIMATE_LEGION;1", false));
		assertTrue(SkyblockMobDropDisplayGenerator.sameSkyblockItemId("BABY_YETI;4", "BABY_YETI", true));
		assertFalse(SkyblockMobDropDisplayGenerator.sameSkyblockItemId("BABY_YETI;4", "YETI", true));
	}
}
