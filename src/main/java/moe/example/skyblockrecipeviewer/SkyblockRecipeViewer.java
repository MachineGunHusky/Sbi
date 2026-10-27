package moe.example.skyblockrecipeviewer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import moe.example.skyblockrecipeviewer.repo.HypixelSkinManager;
import moe.example.skyblockrecipeviewer.repo.NeuRepoManager;

/**
 * Client entrypoint.
 *
 * Deliberately does NOT:
 *  - check GitHub (or anywhere else) for newer releases of this mod
 *  - send the list of installed mods to any server on join
 *
 * The only network calls this mod makes are:
 *  - checking/fetching the public, community-maintained NotEnoughUpdates-REPO item data
 *  - fetching Hypixel's own public api.hypixel.net/v2/resources/skyblock/items endpoint,
 *    used only for its skull texture ("skin") data - no API key, no account data involved
 * Repository data is also initialized at startup when the owned local copy is missing or incomplete.
 */
public class SkyblockRecipeViewer implements ClientModInitializer {
	public static final String MOD_ID = "skyblockrecipeviewer";
	public static final String VERSION = FabricLoader.getInstance().getModContainer(MOD_ID)
		.map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("development");
	public static final Logger LOGGER = LogManager.getLogger("SkyblockRecipeViewer");
	private static final long REPO_CHECK_INTERVAL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
	private static long nextRepoCheckNanos;
	private static String pendingRepoStatus;

	private static void showRepoStatus(String message) {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			if (client.player == null) {
				pendingRepoStatus = message;
			} else {
				pendingRepoStatus = null;
				client.player.sendSystemMessage(Component.literal("[SBI] " + message).withStyle(ChatFormatting.GRAY));
			}
		});
	}

	@Override
	public void onInitializeClient() {
		moe.example.skyblockrecipeviewer.rei.SkyblockPriceTooltipHandler.register();
		moe.example.skyblockrecipeviewer.rei.SkyblockItemRenameTooltipHandler.register();
		moe.example.skyblockrecipeviewer.command.SkyblockCommands.register();
		NeuRepoManager.getInstance().setStatusListener(SkyblockRecipeViewer::showRepoStatus);

		// Prepare REI data asynchronously, downloading missing repository data on the repo executor.
		moe.example.skyblockrecipeviewer.rei.SkyblockReiPlugin.startBootstrap();
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player != null && pendingRepoStatus != null) {
				String message = pendingRepoStatus;
				pendingRepoStatus = null;
				showRepoStatus(message);
			}
			moe.example.skyblockrecipeviewer.rei.SkyblockReiPlugin.processPendingItemCache();
			moe.example.skyblockrecipeviewer.rei.SkyblockReiPlugin.processPendingReiRefresh();
			long now = System.nanoTime();
			if (now < nextRepoCheckNanos) return;
			nextRepoCheckNanos = now + REPO_CHECK_INTERVAL_NANOS;
			NeuRepoManager.getInstance().checkForDiskChanges().thenAccept(changed -> {
				if (changed) {
					moe.example.skyblockrecipeviewer.rei.SkyblockReiPlugin.onRepositoryReloaded();
				}
			});
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			var server = client.getCurrentServer();
			if (server == null || server.ip == null || !server.ip.toLowerCase().contains("hypixel.net")) {
				return;
			}
			var repoUpdate = NeuRepoManager.getInstance().checkForUpdatesOnJoin();
			repoUpdate.thenAccept(updated -> {
				if (!updated) return;
				LOGGER.info("SkyBlock item repo updated from GitHub and reloaded.");
				moe.example.skyblockrecipeviewer.rei.SkyblockReiPlugin.onRepositoryReloaded();
			});
			// Hypixel's item resource doesn't have a commit-sha/version marker like the NEU
			// repo does, so there's nothing cheap to compare against - just re-fetch it
			// outright once per Hypixel connection, same cadence as the repo update check.
			var skinRefresh = HypixelSkinManager.getInstance().refreshFromNetwork();

			// This is the piece that makes a straight multiplayer join actually show items
			// without requiring an unrelated reload (e.g. loading into singleplayer) first -
			// checkForUpdatesOnJoin/refreshFromNetwork update the underlying data, but don't
			// know anything about REI themselves. See SkyblockReiPlugin.tryLivePush's javadoc.
			java.util.concurrent.CompletableFuture.allOf(repoUpdate, skinRefresh)
				.thenRun(() -> client.execute(moe.example.skyblockrecipeviewer.rei.SkyblockReiPlugin::tryLivePush));

			// Failsafe: 30s after join, unconditionally re-run the whole thing again,
			// regardless of whether the immediate attempt above already succeeded. Covers
			// anything that could've silently fallen through the cracks - a slow/flaky
			// download that hadn't finished by the time REI's plugin reload originally ran,
			// the owned repository becoming available a little later than our own check, or another
			// transient timing issue. Each piece is already cheap/idempotent when there's nothing new
			// to do (checkForUpdatesOnJoin no-ops if the sha's unchanged, tryLivePush no-ops
			// if the loaded repo reference hasn't changed since the last push), so this is
			// safe to fire unconditionally rather than trying to track whether it's "needed".
			java.util.concurrent.CompletableFuture.delayedExecutor(30, java.util.concurrent.TimeUnit.SECONDS)
				.execute(() -> {
					LOGGER.info("30s post-join failsafe: re-checking SkyBlock item repo/skin data.");
					var retryRepo = NeuRepoManager.getInstance().checkForUpdatesOnJoin();
					var retrySkin = HypixelSkinManager.getInstance().refreshFromNetwork();
					java.util.concurrent.CompletableFuture.allOf(retryRepo, retrySkin)
						.thenRun(() -> client.execute(moe.example.skyblockrecipeviewer.rei.SkyblockReiPlugin::tryLivePush));
				});
		});

		LOGGER.info("SkyBlockItems {} loaded.", VERSION);
	}
}
