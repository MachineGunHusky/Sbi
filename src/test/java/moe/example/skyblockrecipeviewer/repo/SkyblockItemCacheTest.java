package moe.example.skyblockrecipeviewer.repo;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class SkyblockItemCacheTest {
	@Test
	void exactRevisionDetectsLocalRepoChangesEvenWhenCommitIsUnchanged() {
		JsonObject root = json("{\"repoSha\":\"same-sha\",\"repoRevision\":\"same-sha:42:old\"}");
		assertTrue(SkyblockItemCache.matchesRevision(root, "same-sha", "same-sha:42:old"));
		assertFalse(SkyblockItemCache.matchesRevision(root, "same-sha", "same-sha:42:new"));
	}

	@Test
	void legacyCachesStillUseTheirCommitSha() {
		JsonObject root = json("{\"repoSha\":\"old-sha\"}");
		assertTrue(SkyblockItemCache.matchesRevision(root, "old-sha", "old-sha:42:hash"));
		assertFalse(SkyblockItemCache.matchesRevision(root, "new-sha", "new-sha:42:hash"));
	}

	@Test
	void unknownOrMissingRevisionsNeverValidateAnUnidentifiedCache() {
		assertFalse(SkyblockItemCache.matchesRevision(new JsonObject(), "some-sha", "some-sha:42:hash"));
		assertFalse(SkyblockItemCache.matchesRevision(json("{\"repoSha\":\"some-sha\"}"), null, "some-sha:42:hash"));
	}

	@Test
	void shaOnlyCallersRemainCompatibleWithRevisionTaggedCaches() {
		JsonObject root = json("{\"repoSha\":\"same-sha\",\"repoRevision\":\"same-sha:42:hash\"}");
		assertTrue(SkyblockItemCache.matchesRevision(root, "same-sha", null));
		assertFalse(SkyblockItemCache.matchesRevision(root, "different-sha", null));
	}

	private static JsonObject json(String source) {
		return JsonParser.parseString(source).getAsJsonObject();
	}
}
