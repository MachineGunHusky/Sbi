package moe.example.skyblockrecipeviewer.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NeuRepoManagerTest {
	private static final List<String> REQUIRED_CONSTANTS = List.of(
		"abiphone.json", "bonuses.json", "parents.json", "enchants.json", "essencecosts.json",
		"fairy_souls.json", "misc.json", "leveling.json", "pets.json", "petnums.json");

	@TempDir
	Path tempDir;
	private HttpServer server;
	private URI serverUrl;
	private volatile String latestSha = "old-sha";
	private volatile byte[] archive;
	private final AtomicInteger downloads = new AtomicInteger();
	private final AtomicInteger revisionChecks = new AtomicInteger();

	@BeforeEach
	void startRepoServer() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/commit", exchange -> {
			revisionChecks.incrementAndGet();
			byte[] body = ("{\"sha\":\"" + latestSha + "\"}").getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (var response = exchange.getResponseBody()) {
				response.write(body);
			}
		});
		server.createContext("/archive/", exchange -> {
			downloads.incrementAndGet();
			byte[] body = archive;
			if (body == null) {
				exchange.sendResponseHeaders(503, -1);
				exchange.close();
				return;
			}
			exchange.sendResponseHeaders(200, body.length);
			try (var response = exchange.getResponseBody()) {
				response.write(body);
			}
		});
		server.start();
		serverUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
	}

	@AfterEach
	void stopRepoServer() {
		server.stop(0);
	}

	@Test
	void reloadsRecipesWhenOwnedRepoChangesAfterInitialization() throws Exception {
		Path repo = repoPath();
		Path items = repo.resolve("items");
		Path constants = repo.resolve("constants");
		Files.createDirectories(items);
		Files.createDirectories(constants);
		for (String name : REQUIRED_CONSTANTS) {
			Files.writeString(constants.resolve(name), "{}");
		}
		Files.writeString(constants.resolve("fairy_souls.json"), "{\"Max Souls\":0}");
		Files.writeString(constants.resolve("reforges.json"), "{}");
		Files.writeString(constants.resolve("reforgestones.json"), "{}");
		Files.writeString(repo.resolve("currentCommit.json"), "{\"sha\":\"old-sha\"}");

		Path target = items.resolve("TEST_OUTPUT.json");
		Files.writeString(target, itemJson(false));
		Files.writeString(items.resolve("TEST_INPUT.json"),
			"{\"itemid\":\"minecraft:stone\",\"displayname\":\"Input\","
				+ "\"internalname\":\"TEST_INPUT\",\"lore\":[]}");

		NeuRepoManager manager = manager(repo);
		var original = manager.ensureLoaded().get(10, TimeUnit.SECONDS);
		assertTrue(manager.getCraftingRecipes().isEmpty());

		Files.writeString(target, itemJson(true));
		Files.setLastModifiedTime(target,
			FileTime.fromMillis(Files.getLastModifiedTime(target).toMillis() + 2_000));

		assertTrue(manager.checkForDiskChanges().get(10, TimeUnit.SECONDS));
		assertNotSame(original, manager.getLoadedRepoOrNull());
		assertEquals(1, manager.getCraftingRecipes().size());
		assertEquals("TEST_OUTPUT", manager.getCraftingRecipes().getFirst().getOutput().getItemId());
		assertEquals("{\"sha\":\"old-sha\"}", Files.readString(repo.resolve("currentCommit.json")));
	}

	@Test
	void keepsOldForgeSnapshotUntilOwnedRepoIsCompleteAndStable() throws Exception {
		Path repo = createRepo();
		Path items = repo.resolve("items");
		Path oldOutput = items.resolve("OLD_FORGE.json");
		Files.writeString(oldOutput, forgeItemJson("OLD_FORGE"));
		Files.writeString(items.resolve("TEST_INPUT.json"), basicItemJson("TEST_INPUT"));

		NeuRepoManager manager = manager(repo);
		var original = manager.ensureLoaded().get(10, TimeUnit.SECONDS);
		assertEquals(1, manager.getForgeRecipes().size());

		Files.delete(repo.resolve("constants/abiphone.json"));
		Files.writeString(items.resolve("NEW_FORGE.json"), forgeItemJson("NEW_FORGE"));
		Files.writeString(repo.resolve("currentCommit.json"), "{\"sha\":\"new-sha\"}");

		assertFalse(manager.checkForDiskChanges().get(10, TimeUnit.SECONDS));
		assertSame(original, manager.getLoadedRepoOrNull());
		assertEquals(1, manager.getForgeRecipes().size());
		assertEquals("OLD_FORGE", manager.getForgeRecipes().getFirst().getOutputStack().getItemId());

		Files.writeString(repo.resolve("constants/abiphone.json"), "{}");
		assertTrue(manager.checkForDiskChanges().get(10, TimeUnit.SECONDS));
		assertNotSame(original, manager.getLoadedRepoOrNull());
		assertEquals(2, manager.getForgeRecipes().size());
		assertTrue(manager.getForgeRecipes().stream()
			.anyMatch(recipe -> "OLD_FORGE".equals(recipe.getOutputStack().getItemId())));
		assertTrue(manager.getForgeRecipes().stream()
			.anyMatch(recipe -> "NEW_FORGE".equals(recipe.getOutputStack().getItemId())));
		assertEquals("{\"sha\":\"new-sha\"}", Files.readString(repo.resolve("currentCommit.json")));
	}

	@Test
	void createsBothDirectoriesAndDownloadsOnFirstLaunch() throws Exception {
		archive = repositoryArchive("FIRST_ITEM");
		Path repo = repoPath();
		assertFalse(Files.exists(repo.getParent()));
		NeuRepoManager manager = manager(repo);
		assertTrue(Files.isDirectory(repo.getParent()));
		assertTrue(Files.isDirectory(repo));
		assertFalse(manager.hasCompleteRepository());

		assertNotNull(manager.ensureLoaded().get(10, TimeUnit.SECONDS));
		assertEquals(1, downloads.get());
		assertTrue(manager.hasCompleteRepository());
		assertEquals(repo, manager.getRepoDir());
		assertTrue(Files.isRegularFile(repo.resolve("items/FIRST_ITEM.json")));
		assertTrue(Files.isRegularFile(repo.resolve("currentCommit.json")));
		assertFalse(Files.exists(repo.resolve("repo")));
	}

	@Test
	void repairsIncompleteRepoEvenWhenItsSavedRevisionMatches() throws Exception {
		Path repo = repoPath();
		Files.createDirectories(repo);
		Files.writeString(repo.resolve("currentCommit.json"), "{\"sha\":\"old-sha\"}");
		archive = repositoryArchive("REPAIRED_ITEM");
		assertNotNull(manager(repo).ensureLoaded().get(10, TimeUnit.SECONDS));
		assertEquals(1, downloads.get());
		int checks = revisionChecks.get();

		NeuRepoManager nextLaunch = manager(repo);
		assertNotNull(nextLaunch.ensureLoaded().get(10, TimeUnit.SECONDS));
		assertEquals(1, downloads.get());
		assertEquals(checks, revisionChecks.get());
		assertFalse(nextLaunch.checkForUpdatesOnJoin().get(10, TimeUnit.SECONDS));
		assertEquals(1, downloads.get());
	}

	@Test
	void recreatesMissingDirectoriesAfterRepoWasLoaded() throws Exception {
		Path repo = repoPath();
		archive = repositoryArchive("RESTORED_ITEM");
		NeuRepoManager manager = manager(repo);
		assertNotNull(manager.ensureLoaded().get(10, TimeUnit.SECONDS));
		try (var paths = Files.walk(repo.getParent())) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
		}
		assertNotNull(manager.ensureLoaded().get(10, TimeUnit.SECONDS));
		assertTrue(Files.isDirectory(repo.getParent()));
		assertTrue(manager.hasCompleteRepository());
		assertEquals(2, downloads.get());
	}

	@Test
	void updatesOwnedRepoAndRetainsItWhenDownloadsFail() throws Exception {
		Path repo = createRepo();
		Files.writeString(repo.resolve("items/OLD_ITEM.json"), basicItemJson("OLD_ITEM"));
		NeuRepoManager manager = manager(repo);
		assertNotNull(manager.ensureLoaded().get(10, TimeUnit.SECONDS));
		latestSha = "new-sha";
		archive = repositoryArchive("NEW_ITEM");
		assertTrue(manager.checkForUpdatesOnJoin().get(10, TimeUnit.SECONDS));
		assertTrue(Files.exists(repo.resolve("items/NEW_ITEM.json")));
		assertFalse(Files.exists(repo.resolve("items/OLD_ITEM.json")));
		assertEquals(1, manager.getForgeRecipes().size());
		var loaded = manager.getLoadedRepoOrNull();
		String marker = Files.readString(repo.resolve("currentCommit.json"));

		latestSha = "unavailable-sha";
		archive = null;
		assertFalse(manager.checkForUpdatesOnJoin().get(10, TimeUnit.SECONDS));
		assertSame(loaded, manager.getLoadedRepoOrNull());
		assertEquals(marker, Files.readString(repo.resolve("currentCommit.json")));
		assertTrue(Files.exists(repo.resolve("items/NEW_ITEM.json")));
	}

	@Test
	void handlesDirectoryCreationAndFirstDownloadFailuresWithoutThrowing() throws Exception {
		Path repo = repoPath();
		Files.createDirectories(repo.getParent().getParent());
		Files.writeString(repo.getParent(), "blocked");
		NeuRepoManager blocked = manager(repo);
		assertNull(blocked.ensureLoaded().get(10, TimeUnit.SECONDS));
		assertFalse(blocked.checkForUpdatesOnJoin().get(10, TimeUnit.SECONDS));
		assertEquals(0, downloads.get());
		assertEquals("blocked", Files.readString(repo.getParent()));

		Files.delete(repo.getParent());
		NeuRepoManager offline = manager(repo);
		assertNull(offline.ensureLoaded().get(10, TimeUnit.SECONDS));
		assertFalse(offline.hasCompleteRepository());
		archive = repositoryArchive("RETRIED_ITEM");
		assertNotNull(offline.ensureLoaded().get(10, TimeUnit.SECONDS));
		assertTrue(offline.hasCompleteRepository());
	}

	@Test
	void restoresBackupWhenDirectoryCreationProducesAnEmptyRepo() throws Exception {
		Path repo = createRepo();
		Files.writeString(repo.resolve("items/BACKUP_ITEM.json"), basicItemJson("BACKUP_ITEM"));
		Path backup = repo.resolveSibling("NEUrepo.update-backup");
		Files.move(repo, backup);
		archive = "invalid archive".getBytes(StandardCharsets.UTF_8);
		RepoDownloader downloader = new RepoDownloader(repo, LogManager.getLogger("RepoTest"),
			serverUrl.resolve("commit"), serverUrl.resolve("archive/"));
		assertTrue(Files.isDirectory(repo));
		assertFalse(downloader.downloadAndApply("new-sha"));
		assertTrue(Files.exists(repo.resolve("items/BACKUP_ITEM.json")));
		assertEquals("old-sha", downloader.loadSavedSha());
	}

	@Test
	void leavesOtherModsRepositoriesUntouched() throws Exception {
		Map<Path, String> externalRepos = new LinkedHashMap<>();
		for (String directory : List.of("skyhanni/neurepo", "skyblocker/neurepo", "notenoughupdates/repo")) {
			Path marker = tempDir.resolve("config").resolve(directory).resolve("keep.json");
			Files.createDirectories(marker.getParent());
			Files.writeString(marker, directory);
			externalRepos.put(marker, directory);
		}
		archive = repositoryArchive("OWNED_ITEM");
		assertNotNull(manager(repoPath()).ensureLoaded().get(10, TimeUnit.SECONDS));
		for (var entry : externalRepos.entrySet()) {
			assertEquals(entry.getValue(), Files.readString(entry.getKey()));
			try (var files = Files.list(entry.getKey().getParent())) {
				assertEquals(1, files.count());
			}
		}
	}

	private Path repoPath() {
		return tempDir.resolve("config").resolve("skyblockrecipeviewer").resolve("NEUrepo");
	}

	@Test
	void announcesKeyStepsOnceAcrossRetriesAndRecovery() throws Exception {
		NeuRepoManager manager = manager(repoPath());
		List<String> messages = new CopyOnWriteArrayList<>();
		manager.setStatusListener(messages::add);
		assertNull(manager.ensureLoaded().get(10, TimeUnit.SECONDS));
		assertEquals(List.of("Fetching new repo...", "Repo unavailable - see the log for details."), messages);
		assertNull(manager.ensureLoaded().get(10, TimeUnit.SECONDS));
		assertFalse(manager.checkForUpdatesOnJoin().get(10, TimeUnit.SECONDS));
		assertEquals(2, messages.size());

		archive = repositoryArchive("RECOVERED_ITEM");
		assertNotNull(manager.ensureLoaded().get(10, TimeUnit.SECONDS));
		assertEquals("Repo loaded - refreshing items and recipes.", messages.getLast());
		assertEquals(3, messages.size());
		assertNotNull(manager.ensureLoaded().get(10, TimeUnit.SECONDS));
		assertFalse(manager.checkForUpdatesOnJoin().get(10, TimeUnit.SECONDS));
		assertFalse(manager.checkForDiskChanges().get(10, TimeUnit.SECONDS));
		assertEquals(3, messages.size());

		latestSha = "updated-sha";
		assertTrue(manager.checkForUpdatesOnJoin().get(10, TimeUnit.SECONDS));
		assertEquals(List.of("Fetching new repo...", "Repo loaded - refreshing items and recipes."),
			messages.subList(3, 5));
		assertEquals(5, messages.size());
	}

	@Test
	void announcesCachedRepoOnlyOnceWithoutFetchMessages() throws Exception {
		Path repo = createRepo();
		Files.writeString(repo.resolve("items/CACHED_ITEM.json"), basicItemJson("CACHED_ITEM"));
		NeuRepoManager manager = manager(repo);
		List<String> messages = new CopyOnWriteArrayList<>();
		manager.setStatusListener(messages::add);
		assertNotNull(manager.ensureLoaded().get(10, TimeUnit.SECONDS));
		assertNotNull(manager.loadAsync().get(10, TimeUnit.SECONDS));
		assertFalse(manager.checkForUpdatesOnJoin().get(10, TimeUnit.SECONDS));
		assertEquals(List.of("Repo loaded - refreshing items and recipes."), messages);
		assertEquals(0, downloads.get());
	}

	@Test
	void loadsVerifiedDungeonBooksWithoutRequiringRngMeterData() throws Exception {
		Path repo = createRepo();
		Files.writeString(repo.resolve("items/ULTIMATE_LEGION;1.json"), """
			{"itemid":"minecraft:enchanted_book","internalname":"ULTIMATE_LEGION;1","lore":[]}
			""");
		Files.writeString(repo.resolve("items/LIVID_BOSS.json"), """
			{"itemid":"minecraft:player_head","internalname":"LIVID_BOSS","lore":[],"recipes":[
			 {"type":"drops","name":"Livid","panorama":"dungeon","drops":["LIVID_DAGGER"]},
			 {"type":"drops","name":"Livid (Master Mode)","panorama":"dungeon","drops":["LIVID_DAGGER"]}]}
			""");
		NeuRepoManager manager = manager(repo);
		assertNotNull(manager.ensureLoaded().get(10, TimeUnit.SECONDS));
		var recipes = manager.getMobDropRecipes();
		assertEquals(2, recipes.size());
		assertEquals(List.of("F5", "M5"), recipes.stream().map(recipe -> DungeonRewards.floor(recipe).key()).toList());
		for (var recipe : recipes) {
			assertEquals(3, recipe.getDrops().size());
			assertEquals(List.of("Obsidian Chest", "Bedrock Chest"), recipe.getDrops().subList(1, 3).stream()
				.map(drop -> DungeonRewards.chest(drop.getExtra())).toList());
			assertTrue(recipe.getDrops().subList(1, 3).stream().allMatch(drop -> drop.getChance() != null));
		}
		assertEquals(List.of("12.0009%", "14.8231%"), recipes.getFirst().getDrops().subList(1, 3).stream()
			.map(drop -> drop.getChance()).toList());
		assertEquals(List.of("13.0954%", "17.7685%"), recipes.getLast().getDrops().subList(1, 3).stream()
			.map(drop -> drop.getChance()).toList());
		Files.writeString(repo.resolve("constants/rngscore.json"), "{\"catacombs\":{}}");
		Files.setLastModifiedTime(repo.resolve("constants/rngscore.json"),
			FileTime.fromMillis(System.currentTimeMillis() + 2_000));
		assertTrue(manager.checkForDiskChanges().get(10, TimeUnit.SECONDS));
		assertTrue(manager.getMobDropRecipes().stream().allMatch(recipe -> recipe.getDrops().size() == 3));
	}

	private NeuRepoManager manager(Path repo) {
		return new NeuRepoManager(new RepoDownloader(repo, LogManager.getLogger("RepoTest"),
			serverUrl.resolve("commit"), serverUrl.resolve("archive/")), 10);
	}

	private static byte[] repositoryArchive(String outputId) throws Exception {
		Map<String, String> files = new LinkedHashMap<>();
		for (String name : REQUIRED_CONSTANTS) files.put("constants/" + name, "{}");
		files.put("constants/fairy_souls.json", "{\"Max Souls\":0}");
		files.put("constants/reforges.json", "{}");
		files.put("constants/reforgestones.json", "{}");
		files.put("items/TEST_INPUT.json", basicItemJson("TEST_INPUT"));
		files.put("items/" + outputId + ".json", forgeItemJson(outputId));
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (var entry : files.entrySet()) {
				zip.putNextEntry(new ZipEntry("repo-fixture/" + entry.getKey()));
				zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		return bytes.toByteArray();
	}

	private Path createRepo() throws Exception {
		Path repo = repoPath();
		Files.createDirectories(repo.resolve("items"));
		Files.createDirectories(repo.resolve("constants"));
		for (String name : REQUIRED_CONSTANTS) {
			Files.writeString(repo.resolve("constants").resolve(name), "{}");
		}
		Files.writeString(repo.resolve("constants/fairy_souls.json"), "{\"Max Souls\":0}");
		Files.writeString(repo.resolve("constants/reforges.json"), "{}");
		Files.writeString(repo.resolve("constants/reforgestones.json"), "{}");
		Files.writeString(repo.resolve("currentCommit.json"), "{\"sha\":\"old-sha\"}");
		return repo;
	}

	private static String basicItemJson(String id) {
		return "{\"itemid\":\"minecraft:stone\",\"displayname\":\"" + id + "\","
			+ "\"internalname\":\"" + id + "\",\"lore\":[]}";
	}

	private static String forgeItemJson(String id) {
		return "{\"itemid\":\"minecraft:stone\",\"displayname\":\"" + id + "\","
			+ "\"internalname\":\"" + id + "\",\"lore\":[],"
			+ "\"recipe\":{\"type\":\"forge\",\"inputs\":[\"TEST_INPUT:1\"],\"duration\":60}}";
	}

	private static String itemJson(boolean withRecipe) {
		return "{\"itemid\":\"minecraft:stone\",\"displayname\":\"Output\","
			+ "\"internalname\":\"TEST_OUTPUT\",\"lore\":[]"
			+ (withRecipe ? ",\"recipe\":{\"type\":\"crafting\",\"A1\":\"TEST_INPUT:1\"}" : "")
			+ "}";
	}
}
