package moe.example.skyblockrecipeviewer.repo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.Logger;

/**
 * Downloads the public NotEnoughUpdates-REPO data into this mod's own NEUrepo directory.
 * The revision marker lives inside the repository; staging and backup directories are siblings.
 *
 * This is plain data (item/recipe JSON), not the mod's own release artifact - there is no
 * self-update-checker here, and nothing about installed mods is ever sent anywhere.
 */
public final class RepoDownloader {

	private static final String OWNER = "NotEnoughUpdates";
	private static final String REPO = "NotEnoughUpdates-REPO";
	private static final String BRANCH = "master";
	private static final long MAX_METADATA_BYTES = 1024 * 1024;
	private static final long MAX_ARCHIVE_BYTES = 256L * 1024 * 1024;
	private static final long MAX_EXTRACTED_ENTRY_BYTES = 32L * 1024 * 1024;
	private static final long MAX_EXTRACTED_TOTAL_BYTES = 512L * 1024 * 1024;
	private static final int MAX_ARCHIVE_ENTRIES = 50_000;

	private final Path repoDir;
	private final Path stagingDir;
	private final Path backupDir;
	private final Path commitFile;
	private final Logger logger;
	private final URI latestCommitUrl;
	private final URI archiveBaseUrl;
	private final HttpClient httpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(15))
		// GitHub's codeload/archive endpoints redirect (302) fairly routinely - e.g. between
		// CDN endpoints - and HttpClient's default redirect policy is NEVER, meaning every
		// archive download was failing outright with "GitHub archive download returned HTTP
		// 302" instead of ever reaching the actual zip. NORMAL follows redirects except
		// HTTPS->HTTP downgrades, which is the right default for a GET against a fixed,
		// known-HTTPS host like this.
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	public RepoDownloader(Path repoPath, Logger logger) {
		this(repoPath, logger,
			URI.create("https://api.github.com/repos/" + OWNER + "/" + REPO + "/commits/" + BRANCH),
			URI.create("https://github.com/" + OWNER + "/" + REPO + "/archive/"));
	}

	RepoDownloader(Path repoPath, Logger logger, URI latestCommitUrl, URI archiveBaseUrl) {
		this.repoDir = repoPath.toAbsolutePath().normalize();
		this.stagingDir = repoDir.resolveSibling(repoDir.getFileName() + ".update-staging");
		this.backupDir = repoDir.resolveSibling(repoDir.getFileName() + ".update-backup");
		this.commitFile = repoDir.resolve("currentCommit.json");
		this.logger = logger;
		this.latestCommitUrl = latestCommitUrl;
		this.archiveBaseUrl = archiveBaseUrl;
		ensureRepoDirectory();
	}

	public Path getRepoDir() {
		return repoDir;
	}

	boolean ensureRepoDirectory() {
		try {
			Files.createDirectories(repoDir);
			return true;
		} catch (IOException | SecurityException e) {
			logger.error("Could not create NEU repository directory {}. Repository operations will retry later.", repoDir, e);
			return false;
		}
	}

	String loadSavedSha() {
		if (!ensureRepoDirectory()) return null;
		try {
			if (Files.exists(repoDir) && Files.exists(commitFile)) {
				JsonObject json = JsonParser.parseString(Files.readString(commitFile, StandardCharsets.UTF_8))
					.getAsJsonObject();
				return json.has("sha") ? json.get("sha").getAsString() : null;
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	private void saveSha(String sha) throws IOException {
		JsonObject json = new JsonObject();
		json.addProperty("sha", sha);
		json.addProperty("time", System.currentTimeMillis());
		Files.createDirectories(commitFile.getParent());
		Path temporary = Files.createTempFile(commitFile.getParent(), "currentCommit", ".tmp");
		try {
			Files.writeString(temporary, json.toString(), StandardCharsets.UTF_8);
			moveReplacing(temporary, commitFile);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private String fetchLatestSha() throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(latestCommitUrl)
			.header("Accept", "application/vnd.github+json")
			.timeout(Duration.ofSeconds(15))
			.GET()
			.build();
		HttpResponse<String> response = httpClient.send(request,
			HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), MAX_METADATA_BYTES));
		if (response.statusCode() != 200) {
			throw new IOException("GitHub API returned HTTP " + response.statusCode());
		}
		JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
		return json.get("sha").getAsString();
	}

	/**
	 * Compares the locally recorded sha (from currentCommit.json) against GitHub's latest
	 * commit on the branch, without downloading anything. Safe to call from a background
	 * thread; intended to run once per Hypixel connection rather than on every launch.
	 *
	 * @return the latest sha if it differs from what's on disk (or nothing is on disk yet),
	 *         or empty if already up to date or the check failed (e.g. offline)
	 */
	public java.util.Optional<String> checkForUpdate() {
		return checkForUpdate(false);
	}

	java.util.Optional<String> checkForUpdate(boolean force) {
		if (!ensureRepoDirectory()) return java.util.Optional.empty();
		String latestSha;
		try {
			latestSha = fetchLatestSha();
		} catch (Exception e) {
			logger.warn("Could not reach GitHub to check for SkyBlock item repo updates ({}).", e.toString());
			return java.util.Optional.empty();
		}
		String currentSha = loadSavedSha();
		if (!force && latestSha.equals(currentSha)) {
			logger.debug("SkyBlock item repo already up to date ({})", currentSha);
			return java.util.Optional.empty();
		}
		return java.util.Optional.of(latestSha);
	}

	/**
	 * Downloads and extracts the given sha into a staging directory, validates it, then
	 * transactionally replaces the owned repo and records it in currentCommit.json.
	 * Safe to call from a background thread.
	 *
	 * @return true on success
	 */
	public boolean downloadAndApply(String sha) {
		if (!ensureRepoDirectory()) return false;
		Path zip = null;
		try {
			prepareUpdateDirectories();
			String downloadUrl = archiveBaseUrl.resolve(sha + ".zip").toString();
			logger.info("Downloading SkyBlock item repo (-> {})", sha);
			zip = downloadArchive(downloadUrl);
			extract(zip, stagingDir);
			validateStagedRepository();
			installStagedRepository(sha);
			logger.info("SkyBlock item repo updated to {}.", sha);
			return true;
		} catch (Exception e) {
			logger.error("Failed to prepare/install SkyBlock item repo; any previous repo remains recoverable.", e);
			return false;
		} finally {
			deleteQuietly(zip);
			if (Files.exists(repoDir)) {
				deleteQuietly(stagingDir);
			} else if (Files.exists(stagingDir)) {
				logger.warn("Keeping staged repository at {} because no live repo is currently present.", stagingDir);
			}
		}
	}

	private void prepareUpdateDirectories() throws IOException {
		Files.createDirectories(repoDir);
		if (Files.exists(backupDir)) {
			boolean empty;
			try (var files = Files.list(repoDir)) {
				empty = files.findAny().isEmpty();
			}
			if (empty) {
				Files.delete(repoDir);
				moveDirectory(backupDir, repoDir);
			}
		}
		if (Files.exists(repoDir)) {
			deleteRecursively(backupDir);
		}
		deleteRecursively(stagingDir);
	}

	private void validateStagedRepository() throws IOException {
		Path items = stagingDir.resolve("items");
		Path constants = stagingDir.resolve("constants");
		if (!Files.isDirectory(items) || !Files.isDirectory(constants)) {
			throw new IOException("Downloaded repository is missing its items or constants directory");
		}
		try (var files = Files.list(items)) {
			if (files.noneMatch(Files::isRegularFile)) {
				throw new IOException("Downloaded repository contains no item files");
			}
		}
	}

	private void installStagedRepository(String sha) throws IOException {
		boolean hadPrevious = Files.exists(repoDir);
		if (hadPrevious) {
			moveDirectory(repoDir, backupDir);
		}

		try {
			moveDirectory(stagingDir, repoDir);
		} catch (IOException installError) {
			if (hadPrevious && Files.exists(backupDir) && !Files.exists(repoDir)) {
				moveDirectory(backupDir, repoDir);
			}
			throw installError;
		}

		try {
			saveSha(sha);
		} catch (IOException markerError) {
			if (hadPrevious && Files.exists(backupDir)) {
				try {
					moveDirectory(repoDir, stagingDir);
					try {
						moveDirectory(backupDir, repoDir);
					} catch (IOException restoreError) {
						markerError.addSuppressed(restoreError);
						try {
							moveDirectory(stagingDir, repoDir);
						} catch (IOException reinstallError) {
							markerError.addSuppressed(reinstallError);
						}
					}
				} catch (IOException rollbackError) {
					markerError.addSuppressed(rollbackError);
				}
			}
			throw markerError;
		}

		deleteQuietly(backupDir);
	}

	private void moveDirectory(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target);
		}
	}

	private void moveReplacing(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private void deleteQuietly(Path path) {
		if (path == null) return;
		try {
			deleteRecursively(path);
		} catch (IOException e) {
			logger.warn("Could not clean up temporary repository path {} ({}).", path, e.toString());
		}
	}

	private Path downloadArchive(String url) throws IOException, InterruptedException {
		Path tempZip = Files.createTempFile("skyblock-repo", ".zip");
		boolean complete = false;
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(60))
				.GET()
				.build();
			HttpResponse<Path> response = httpClient.send(request,
				HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofFile(tempZip), MAX_ARCHIVE_BYTES));
			if (response.statusCode() != 200) {
				throw new IOException("GitHub archive download returned HTTP " + response.statusCode());
			}
			complete = true;
			return response.body();
		} finally {
			if (!complete) {
				Files.deleteIfExists(tempZip);
			}
		}
	}

	/**
	 * Extracts the zip, stripping the single top-level "reponame-sha/" folder GitHub wraps
	 * archives in, with a zip-slip path traversal guard.
	 */
	private void extract(Path zipFile, Path targetDir) throws IOException {
		Files.createDirectories(targetDir);
		Path normalizedTarget = targetDir.normalize();
		try (InputStream fis = Files.newInputStream(zipFile);
			 ZipInputStream zis = new ZipInputStream(fis)) {
			byte[] buffer = new byte[8192];
			long extractedTotal = 0;
			int entryCount = 0;
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				if (++entryCount > MAX_ARCHIVE_ENTRIES) {
					throw new IOException("Repository archive contains too many entries");
				}
				if (entry.isDirectory()) continue;
				String name = entry.getName();
				int slash = name.indexOf('/');
				String relative = slash >= 0 ? name.substring(slash + 1) : "";
				if (relative.isEmpty()) continue;

				Path outPath = normalizedTarget.resolve(relative).normalize();
				if (!outPath.startsWith(normalizedTarget)) {
					throw new IOException("Zip entry escapes target directory (zip-slip): " + name);
				}
				Files.createDirectories(outPath.getParent());
				long declaredSize = entry.getSize();
				if (declaredSize > MAX_EXTRACTED_ENTRY_BYTES
					|| declaredSize >= 0 && declaredSize > MAX_EXTRACTED_TOTAL_BYTES - extractedTotal) {
					throw new IOException("Repository archive entry exceeds extraction limits: " + name);
				}

				long entryBytes = 0;
				try (OutputStream output = Files.newOutputStream(outPath)) {
					int read;
					while ((read = zis.read(buffer)) != -1) {
						if (read > MAX_EXTRACTED_ENTRY_BYTES - entryBytes
							|| read > MAX_EXTRACTED_TOTAL_BYTES - extractedTotal) {
							throw new IOException("Repository archive exceeds extraction limits");
						}
						output.write(buffer, 0, read);
						entryBytes += read;
						extractedTotal += read;
					}
				}
			}
		}
	}

	private void deleteRecursively(Path dir) throws IOException {
		if (!Files.exists(dir)) return;
		try (var walk = Files.walk(dir)) {
			for (Path path : walk.sorted((a, b) -> b.compareTo(a)).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}
}
