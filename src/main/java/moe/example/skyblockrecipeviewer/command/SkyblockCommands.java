package moe.example.skyblockrecipeviewer.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import moe.example.skyblockrecipeviewer.repo.SkyblockItemRealNbt;
import moe.example.skyblockrecipeviewer.repo.SkyblockItemRenameManager;
import moe.example.skyblockrecipeviewer.repo.SkyblockPriceManager;

/**
 * Registers the mod's client commands under the "/sbi" prefix.
 *
 * Commands:
 * <ul>
 *   <li>{@code /sbi rename <name>} - renames the held item (client-side only)</li>
 *   <li>{@code /sbi rename clear} - clears the held item's custom name, restoring its
 *       original name</li>
 *   <li>{@code /sbi refresh} - reloads both Bazaar and Auction House prices</li>
 *   <li>{@code /sbi refresh bazaar} / {@code /sbi refresh ah} - reloads just one</li>
 * </ul>
 *
 * {@link ClientCommands} provides the client-side command builders used by the dispatcher.
 */
public final class SkyblockCommands {
	private SkyblockCommands() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommands.literal("sbi")
				.then(ClientCommands.literal("rename")
					.then(ClientCommands.argument("name", StringArgumentType.greedyString())
						.executes(context -> {
							String name = StringArgumentType.getString(context, "name");
							renameHeldItem(context.getSource(), name);
							return 1;
						}))
					.then(ClientCommands.literal("clear")
						.executes(context -> {
							clearHeldItemName(context.getSource());
							return 1;
						})))
				.then(ClientCommands.literal("refresh")
					.executes(context -> refreshPrices(context, true, true))
					.then(ClientCommands.literal("bazaar")
						.executes(context -> refreshPrices(context, false, true)))
					.then(ClientCommands.literal("ah")
						.executes(context -> refreshPrices(context, true, false)))));
		});
	}

	// ---- /sbi rename ----

	private static void renameHeldItem(FabricClientCommandSource source, String name) {
		ItemStack held = source.getPlayer().getMainHandItem();
		String uuid = SkyblockItemRealNbt.getUuid(held);
		if (uuid == null) {
			source.sendFeedback(Component.literal(
				"[SkyBlock Recipe Viewer] The item in your hand doesn't have a SkyBlock item "
					+ "UUID to attach a rename to.").withStyle(ChatFormatting.RED));
			return;
		}
		SkyblockItemRenameManager.getInstance().setName(uuid, name);
		source.sendFeedback(Component.literal("[SkyBlock Recipe Viewer] Renamed to: ")
			.withStyle(ChatFormatting.GRAY)
			.append(Component.literal(name)));
	}

	private static void clearHeldItemName(FabricClientCommandSource source) {
		ItemStack held = source.getPlayer().getMainHandItem();
		String uuid = SkyblockItemRealNbt.getUuid(held);
		if (uuid == null) {
			source.sendFeedback(Component.literal(
				"[SkyBlock Recipe Viewer] The item in your hand doesn't have a SkyBlock item UUID.")
				.withStyle(ChatFormatting.RED));
			return;
		}
		SkyblockItemRenameManager.getInstance().clearName(uuid);
		source.sendFeedback(Component.literal(
			"[SkyBlock Recipe Viewer] Cleared custom name - restored to its original name.")
			.withStyle(ChatFormatting.GRAY));
	}

	// ---- /sbi refresh ----

	/**
	 * Command execution itself already runs on the main thread (chat/command input handling
	 * always does), so the immediate "Reloading..." feedback below is safe to send directly -
	 * only the *completion* feedback, which fires once the background price-refresh executor
	 * resolves the returned futures, needs the Minecraft.execute(...) hop back to main thread.
	 */
	private static int refreshPrices(CommandContext<FabricClientCommandSource> context, boolean auction, boolean bazaar) {
		FabricClientCommandSource source = context.getSource();
		SkyblockPriceManager prices = SkyblockPriceManager.getInstance();

		String what = auction && bazaar ? "Auction House and Bazaar"
			: auction ? "Auction House"
			: "Bazaar";
		source.sendFeedback(Component.literal("[SkyBlock Recipe Viewer] Reloading " + what + " prices...")
			.withStyle(ChatFormatting.GRAY));

		List<CompletableFuture<Void>> futures = new ArrayList<>();
		if (auction) futures.add(prices.reloadAuctionAsync());
		if (bazaar) futures.add(prices.reloadBazaarAsync());

		CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
			.thenRun(() -> Minecraft.getInstance().execute(() ->
				source.sendFeedback(Component.literal("[SkyBlock Recipe Viewer] Price reload complete.")
					.withStyle(ChatFormatting.GREEN))));

		return 1;
	}
}
