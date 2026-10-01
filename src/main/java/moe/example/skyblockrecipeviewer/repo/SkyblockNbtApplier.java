package moe.example.skyblockrecipeviewer.repo;

import com.mojang.serialization.Dynamic;
import io.github.moulberry.repo.data.NEUItem;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Reconstructs the *real* SkyBlock item NBT (ExtraAttributes with the internal id/enchants/
 * stats, display name, lore, enchant glint, unbreakable flag, etc.) from the NEU repo and
 * overlays it onto a resolved vanilla {@link ItemStack}, instead of only faking a display name
 * on top of a plain vanilla item like {@link SkyblockItemResolver} did on its own before.
 *
 * <h2>Where the data comes from</h2>
 * Every item in the NotEnoughUpdates-REPO json carries an "nbttag" field: an SNBT string
 * captured from a real item stack in-game, in the pre-1.20.5 {@code {id, Count, tag}} format
 * (the format Minecraft used before item data was split into typed "components"). That's the
 * same field the original 1.8.9 NEU mod exposed as a plain {@code NBTTagCompound nbttag}
 * constructor argument, and it's what Firmament (the actively-maintained 1.21 successor to NEU,
 * built on this same neurepoparser library) runs through vanilla's own DataFixerUpper to turn
 * into real items for its REI integration - see Firmament's "skipping DFU for REI lore cache
 * generation" changelog entry, which only makes sense if DFU is normally in that path.
 *
 * <h2>Why the DataFixerUpper specifically</h2>
 * We can't just slap that old-format NBT onto a modern ItemStack - since 1.20.5 items are
 * built from typed DataComponents (CUSTOM_NAME, LORE, CUSTOM_DATA, ...), not a single loose NBT
 * blob. The DataFixerUpper is exactly the machinery vanilla itself uses to upgrade old
 * world/playerdata saves (which are full of this exact old item format) into the current
 * component format, so re-using it here gets us a faithful conversion for free, including
 * things like enchantments and attribute modifiers that reference other vanilla registries.
 */
public final class SkyblockNbtApplier {
	private static final Logger LOGGER = LogManager.getLogger("SkyblockRecipeViewer/Nbt");

	// A data version safely older than any component-based format, so the DataFixerUpper always
	// walks the "old loose tag -> typed components" fixer chain for every item we feed it,
	// regardless of what Minecraft version the repo's captured NBT snapshot actually came from.
	private static final int LEGACY_DATA_VERSION = 3337; // 1.20.4, the last pre-component release
	// DataVersion.version() and WorldVersion.dataVersion() are the real accessor names in this
	// version - DataVersion is a Java record (version, series), so its accessor is version()
	// not getVersion(); WorldVersion's is dataVersion() not getDataVersion().
	private static final int CURRENT_DATA_VERSION = SharedConstants.getCurrentVersion().dataVersion().version();

	// Only the built-in (vanilla) registries - items, enchantments, attributes, etc. - not any
	// datapack/world state, since this needs to work at REI's plugin-load phase, which happens
	// at game launch before a world or server connection exists.
	private static final RegistryAccess VANILLA_REGISTRIES =
		RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	// createSerializationContext() is what turns a plain RegistryAccess into the RegistryOps
	// that component-aware codecs like ItemStack.CODEC actually need to parse/write - built
	// once since it's stateless and only wraps VANILLA_REGISTRIES + the NBT ops format.
	private static final RegistryOps<Tag> REGISTRY_OPS =
		VANILLA_REGISTRIES.createSerializationContext(NbtOps.INSTANCE);

	private SkyblockNbtApplier() {
	}

	/**
	 * Package-private accessor so SkyblockItemCache can (de)serialize ItemStacks with the
	 * exact same RegistryOps this class already built, rather than constructing a second,
	 * redundant (if functionally identical) one.
	 */
	static RegistryOps<Tag> registryOps() {
		return REGISTRY_OPS;
	}

	/**
	 * Returns {@code stack} upgraded in place with the SkyBlock item's real NBT if the repo has
	 * an nbttag entry for it and it parses/upgrades cleanly; otherwise returns {@code stack}
	 * completely untouched so callers can fall back to their own display-name-only handling.
	 */
	public static ItemStack apply(ItemStack stack, NEUItem neuItem) {
		if (neuItem == null || stack.isEmpty()) return stack;

		String snbt = extractNbtTag(neuItem);
		if (snbt == null || snbt.isBlank()) return stack;

		try {
			// The repo's nbttag strings use an ancient (pre-1.13) NBT-to-string quirk: list
			// elements are written as "index:value" (e.g. Lore:[0:"...",1:"..."]) instead of
			// modern SNBT's plain "[value,value]". The current TagParser only understands the
			// modern grammar and throws "Expected literal ." right on the index digit, which is
			// why this was failing for essentially every item (anything with Lore, a textures
			// list, potion Effects, etc.) - see stripLegacyListIndices for the fix.
			String sanitized = stripLegacyListIndices(snbt);
			// parseCompoundFully() is the modern (non-generic-instance) replacement for the old
			// static TagParser.parseTag(String) - same job, parses SNBT straight to a CompoundTag.
			CompoundTag capturedTag = TagParser.parseCompoundFully(sanitized);

			// Wrap in the legacy {id, Count, tag} shape the DataFixerUpper's item-stack fixers
			// expect, using OUR resolved item id (not whatever the snapshot's original stack
			// was) so the upgraded result still lines up with the base item we already picked
			// via minecraft_item_id.
			CompoundTag legacyStack = new CompoundTag();
			legacyStack.putString("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
			legacyStack.putByte("Count", (byte) 1);
			legacyStack.put("tag", capturedTag);

			Dynamic<Tag> upgraded = DataFixers.getDataFixer().update(
				References.ITEM_STACK,
				new Dynamic<>(NbtOps.INSTANCE, legacyStack),
				LEGACY_DATA_VERSION,
				CURRENT_DATA_VERSION);

			// ItemStack has no static parse(RegistryAccess, Tag) helper in this version - the
			// codec itself (ItemStack.CODEC) is what parses, and it needs a RegistryOps (built
			// from VANILLA_REGISTRIES above), not a bare RegistryAccess.
			ItemStack result = ItemStack.CODEC.parse(REGISTRY_OPS, upgraded.getValue())
				.resultOrPartial(error -> LOGGER.warn(
					"Failed to parse upgraded NBT for skyblock item {}: {}",
					neuItem.getSkyblockItemId(), error))
				.orElse(ItemStack.EMPTY);

			if (result.isEmpty()) return stack;

			// The repo's legacy nbttag snapshot never bothers writing out an empty
			// "AttributeModifiers" list when an item has no real vanilla modifiers (SkyBlock
			// items never do - their stats are lore text, computed server-side). DFU has
			// nothing to upgrade in that case, so the resulting stack has NO
			// attribute_modifiers component at all, which makes the client fall back to the
			// base item's built-in defaults (e.g. a plain iron_sword's 6 Attack Damage /
			// 1.6 Attack Speed) instead of showing nothing, exactly like this Hyperion
			// (iron_sword-based) reconstruction did. A genuine modern capture (see the
			// "minecraft:attribute_modifiers": [] in a real Firmament snbt dump) always makes
			// this override explicit, so we do the same unconditionally here.
			result.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);

			// "ItemModel" (see e.g. HYPERION.json's nbttag: ...,ItemModel:"hypixel_skyblock:
			// item/uncategorized/hyperion",...) is a top-level key in the repo's legacy
			// snapshot, but it was never an actual pre-1.20.5 NBT tag - Hypixel's resource
			// pack model-override system is a newer concept the DataFixerUpper has no fixer
			// for. So instead of becoming the real "minecraft:item_model" component, it just
			// gets swept up as inert data inside custom_data along with ExtraAttributes,
			// which is why an item reconstructed this way renders with its plain vanilla
			// model/texture instead of the correct Hypixel resource-pack skin - even though
			// the same repo data clearly has the right model id available. Read it straight
			// off the parsed tag ourselves and set the component directly.
			if (capturedTag.contains("ItemModel")) {
				String modelId = capturedTag.getString("ItemModel").orElse(null);
				if (modelId != null && !modelId.isBlank()) {
					try {
						result.set(DataComponents.ITEM_MODEL, Identifier.parse(modelId));
					} catch (Exception badId) {
						LOGGER.warn("Bad ItemModel id '{}' for skyblock item {}: {}",
							modelId, neuItem.getSkyblockItemId(), badId.toString());
					}
				}
			}

			result.setCount(stack.getCount());
			return result;
		} catch (Exception e) {
			LOGGER.warn("Failed to reconstruct NBT for skyblock item {}: {}",
				neuItem.getSkyblockItemId(), e.toString());
			return stack;
		}
	}

	/**
	 * Strips the pre-1.13-era "index:" prefix NEU's captured NBT puts on every list element
	 * (e.g. turns {@code [0:"a",1:"b"]} into {@code ["a","b"]}) so the modern TagParser can
	 * read it. Quote-aware (tracks whether we're inside a "..." or '...' string, respecting
	 * backslash escapes) so it never touches literal digit/colon text that happens to appear
	 * inside a lore line or name - only real, unquoted "[digits:" / ",digits:" structural
	 * prefixes get removed. Byte/int/long arrays (e.g. {@code [B;1,2,3]}) are untouched since
	 * their elements are never followed by a colon.
	 */
	private static String stripLegacyListIndices(String snbt) {
		StringBuilder out = new StringBuilder(snbt.length());
		boolean inString = false;
		char quoteChar = 0;
		int i = 0;
		int len = snbt.length();
		while (i < len) {
			char c = snbt.charAt(i);
			if (inString) {
				out.append(c);
				if (c == '\\' && i + 1 < len) {
					out.append(snbt.charAt(i + 1));
					i += 2;
					continue;
				}
				if (c == quoteChar) inString = false;
				i++;
				continue;
			}
			if (c == '"' || c == '\'') {
				inString = true;
				quoteChar = c;
				out.append(c);
				i++;
				continue;
			}
			if (c == '[' || c == ',') {
				out.append(c);
				int j = i + 1;
				while (j < len && Character.isWhitespace(snbt.charAt(j))) j++;
				int digitsStart = j;
				while (j < len && Character.isDigit(snbt.charAt(j))) j++;
				if (j > digitsStart && j < len && snbt.charAt(j) == ':') {
					// Legacy "index:" prefix - drop it, resume right after the colon.
					i = j + 1;
					continue;
				}
				i++;
				continue;
			}
			out.append(c);
			i++;
		}
		return out.toString();
	}

	/** Returns the item's captured NBT in legacy SNBT form. */
	private static String extractNbtTag(NEUItem neuItem) {
		return neuItem.getNbttag();
	}
}
