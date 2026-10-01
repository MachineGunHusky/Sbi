package moe.example.skyblockrecipeviewer.rei;

import java.util.stream.Stream;

import me.shedaniel.rei.api.client.entry.renderer.EntryRenderer;
import me.shedaniel.rei.api.client.gui.widgets.TooltipContext;
import me.shedaniel.rei.api.common.entry.EntrySerializer;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.entry.comparison.ComparisonContext;
import me.shedaniel.rei.api.common.entry.type.EntryDefinition;
import me.shedaniel.rei.api.common.entry.type.EntryType;
import me.shedaniel.rei.api.common.entry.type.EntryTypeRegistry;
import me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes;
import moe.example.skyblockrecipeviewer.SkyblockRecipeViewer;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Wraps REI's built-in vanilla ItemEntryDefinition, delegating everything except identity:
 * getIdentifier/getContainingNamespace/hash/equals/getTagsFor read the SkyBlock id embedded
 * in the stack's CustomData component (see tagWithSkyblockId / SkyblockItemResolver) instead
 * of falling back to whatever real vanilla item happens to be reused underneath - that
 * vanilla identity leaking through was both why REI's tooltip showed "Minecraft" instead of
 * a SkyBlock-specific label, and why distinct SkyBlock items sharing the same base item (e.g.
 * several different swords all being minecraft:iron_sword under the hood) collapsed together
 * for tag/search/lookup purposes.
 *
 * Verified against the RoughlyEnoughItems-fabric-26.2.821 jar: every method below matches
 * EntryDefinition's real method set exactly, including getTagsFor's generic signature
 * (Stream<? extends TagKey<?>>) and EntryTypeRegistry.register(Identifier, EntryDefinition).
 * cast()/acceptsNull()/getContainingNamespace() are confirmed default methods on the
 * interface (checked the class file's method access flags directly), so overriding
 * getContainingNamespace here is optional convenience, not filling an abstract gap - but
 * it's the one that actually produces the "SBItems" label, via
 * ModArgumentType$ModInfoPair(String, String), which - based on where it's used
 * (REI's "mod:" search filter) - almost certainly falls back to using the returned string
 * as-is for display when it doesn't match a real registered Fabric mod id (ours is lowercase
 * "skyblockrecipeviewer", so "SBItems" won't match it or anything else). I couldn't fully
 * decompile that fallback logic itself to confirm 100% - if it doesn't show as hoped, the
 * fix is almost certainly still in this one method, not a redesign.
 */
public final class SkyblockItemEntryDefinition implements EntryDefinition<ItemStack> {

	public static final Identifier TYPE_ID =
		Identifier.fromNamespaceAndPath(SkyblockRecipeViewer.MOD_ID, "skyblock_item");
	public static final EntryType<ItemStack> TYPE = EntryType.deferred(TYPE_ID);

	private static final String SKYBLOCK_ID_KEY = "SkyblockRecipeViewerId";
	private static final SkyblockItemEntryDefinition INSTANCE = new SkyblockItemEntryDefinition();

	/** Registers the entry type for the current REI reload. */
	public static void registerType() {
		EntryTypeRegistry.getInstance().register(TYPE_ID, INSTANCE);
	}

	// NOT initialized inline - see vanilla() below for why.
	private EntryDefinition<ItemStack> vanilla;

	private SkyblockItemEntryDefinition() {
	}

	/**
	 * Lazily resolves the real vanilla ItemEntryDefinition on first actual use, rather than
	 * eagerly during construction. This is THE fix for a real crash
	 * (ExceptionInInitializerError -> NullPointerException: "Entry type minecraft:item
	 * doesn't exist!", from EntryTypeDeferred.getDefinition()): merely *referencing* this
	 * class from anywhere - e.g. SkyblockPriceTooltipHandler.getSkyblockId(stack), a plain
	 * Fabric ItemTooltipCallback that fires on every tooltip render, completely independent
	 * of REI's own reload lifecycle - forces the JVM to run this class's static initializer.
	 * With VanillaEntryTypes.ITEM.getDefinition() called eagerly as an instance-field
	 * initializer (the old version of this field), that class-load alone was enough to crash
	 * the moment a tooltip rendered before REI had finished registering "minecraft:item" -
	 * e.g. on Hypixel's initial limbo screen, within ~2 seconds of connecting, well before
	 * this mod's own registerCategories()-triggered load was guaranteed to have run. Once
	 * poisoned this way, Java memoizes ExceptionInInitializerError forever - every tooltip
	 * for the rest of the session crashed identically.
	 *
	 * getSkyblockId(stack) itself is a pure CustomData/NBT read - it never actually needed
	 * `vanilla` at all. Deferring the lookup means that call path (and merely touching TYPE/
	 * TYPE_ID) no longer forces it either; only an actual delegate call below (getIdentifier,
	 * hash, getRenderer, etc - all only ever reached through REI's own registry/rendering
	 * code, which by construction only runs once REI itself is ready) does. And unlike the
	 * old eager field, a failure here isn't memoized as a poisoned class forever - it's just
	 * a null field, so the very next call simply retries instead of crashing every time for
	 * the rest of the session.
	 */
	private EntryDefinition<ItemStack> vanilla() {
		EntryDefinition<ItemStack> resolved = vanilla;
		if (resolved == null) {
			resolved = VanillaEntryTypes.ITEM.getDefinition();
			vanilla = resolved;
		}
		return resolved;
	}

	/** Stamps a SkyBlock id onto a stack's CustomData so this definition can read it back later. */
	public static void tagWithSkyblockId(ItemStack stack, String skyblockId) {
		if (skyblockId == null) return;
		CustomData existing = stack.get(DataComponents.CUSTOM_DATA);
		CompoundTag tag = existing != null ? existing.copyTag() : new CompoundTag();
		tag.putString(SKYBLOCK_ID_KEY, skyblockId);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
	}

	/** Reads back whatever SkyBlock id (if any) tagWithSkyblockId stamped onto this stack. */
	public static String getSkyblockId(ItemStack stack) {
		return skyblockIdOf(stack);
	}

	/** Returns true when this stack carries our SkyBlock identity marker. */
	public static boolean hasSkyblockMarker(ItemStack stack) {
		return stack != null && skyblockIdOf(stack) != null;
	}

	/**
	 * Resolves a SkyBlock id from any EntryStack REI hands us for a recipe/usage lookup -
	 * used by every category's DynamicDisplayGenerator instead of each keeping its own
	 * private "only accept our own TYPE" check.
	 *
	 * Pressing R/U on an item sitting in REI's own search panel gives us one of our own
	 * SkyblockItemEntryDefinition-typed entries (built by SkyblockItemResolver, carrying the
	 * SKYBLOCK_ID_KEY marker read above) - but pressing R/U on an item in the player's actual
	 * inventory gives REI's *default* VanillaEntryTypes.ITEM wrapping the real, literal
	 * ItemStack the Hypixel server sent down, which was never resolved through our pipeline
	 * and so never got that marker stamped onto it. Without handling that second case here,
	 * R/U silently did nothing at all for every item in the player's own inventory - the
	 * lookup would only ever work from REI's own panel.
	 *
	 * The real item still carries everything needed, though: Hypixel sends the SkyBlock id
	 * nested under {@code ExtraAttributes} inside the item's NBT (this is exactly the same
	 * legacy structure the NEU repo's own nbttag snapshots capture items by - see
	 * SkyblockNbtApplier's class docs), which survives the normal server->client item sync
	 * into the stack's CustomData component. BUT: confirmed against Skyblocker's own source
	 * (LegacyItemStackFixer.fixLegacyStack) - if Skyblocker is also installed, it rewrites
	 * every real item's CustomData early, replacing it with just the *inner* ExtraAttributes
	 * compound, so "id" ends up sitting at CustomData's own top level instead of nested. Since
	 * that fixer runs on the actual ItemStack instance before anything else (including us)
	 * gets a look at it, whichever form is checked first needs to be whichever mod actually
	 * touched the item last - so this checks both, flattened form first (the common case for
	 * anyone who has Skyblocker installed, which is why "R does nothing" was reported at all),
	 * falling back to the raw nested form for players without it.
	 */
	public static String getSkyblockId(EntryStack<?> entry) {
		if (entry.getType() != TYPE && entry.getType() != VanillaEntryTypes.ITEM) return null;
		Object value = entry.getValue();
		if (!(value instanceof ItemStack stack)) return null;

		String ownMarker = skyblockIdOf(stack);
		if (ownMarker != null) return ownMarker;

		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return null;
		CompoundTag tag = data.copyTag();

		// Flattened form (Skyblocker's LegacyItemStackFixer already ran on this stack).
		String flat = tag.getString("id").orElse(null);
		String resolved = resolvePetId(flat, tag);
		if (resolved != null) return resolved;
		if (flat != null && !flat.isBlank()) return flat;

		// Raw, un-flattened form (no such fixer has touched this stack).
		return tag.getCompound("ExtraAttributes")
			.flatMap(extraAttributes -> {
				String id = extraAttributes.getString("id").orElse(null);
				String petId = resolvePetId(id, extraAttributes);
				return java.util.Optional.ofNullable(petId != null ? petId : id);
			})
			.orElse(null);
	}

	/**
	 * Hypixel pet items are represented in the live inventory as the generic PET item.
	 * The actual SkyBlock pet family is stored in ExtraAttributes.petInfo as JSON (for
	 * example {\"type\":\"BABY_YETI\"}). NEU's repository entries, however, use
	 * the pet family as the item id (with an optional rarity suffix). Resolve that live
	 * inventory representation to the same family id so R/U can use the existing pet
	 * recipe/drop data.
	 */
	private static String resolvePetId(String id, CompoundTag tag) {
		if (id == null || !"PET".equals(id)) return null;
		String petInfo = tag.getString("petInfo").orElse(null);
		if (petInfo == null || petInfo.isBlank()) return null;
		try {
			com.google.gson.JsonObject info = com.google.gson.JsonParser.parseString(petInfo).getAsJsonObject();
			String type = info.has("type") ? info.get("type").getAsString() : null;
			return type == null || type.isBlank() ? null : type;
		} catch (Exception ignored) {
			return null;
		}
	}

	private static String skyblockIdOf(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return null;
		return data.copyTag().getString(SKYBLOCK_ID_KEY).orElse(null);
	}

	@Override
	public Class<ItemStack> getValueType() {
		return ItemStack.class;
	}

	@Override
	public EntryType<ItemStack> getType() {
		return TYPE;
	}

	@Override
	public EntryRenderer<ItemStack> getRenderer() {
		return vanilla().getRenderer();
	}

	@Override
	public Identifier getIdentifier(EntryStack<ItemStack> entry, ItemStack value) {
		String skyblockId = skyblockIdOf(value);
		if (skyblockId == null) return vanilla().getIdentifier(entry, value);
		return Identifier.fromNamespaceAndPath("sbitems", ReiIdentifierPath.normalize(skyblockId));
	}

	/**
	 * This is the one that actually produces the "SBItems" label in REI's tooltip/mod-search
	 * (see class javadoc) - returned as-is, not run through Identifier's lowercase-only
	 * validation, since this is a plain display String, not a namespace.
	 */
	@Override
	public String getContainingNamespace(EntryStack<ItemStack> entry, ItemStack value) {
		if (skyblockIdOf(value) == null) return vanilla().getContainingNamespace(entry, value);
		return "SBItems";
	}

	@Override
	public boolean isEmpty(EntryStack<ItemStack> entry, ItemStack value) {
		return vanilla().isEmpty(entry, value);
	}

	@Override
	public ItemStack copy(EntryStack<ItemStack> entry, ItemStack value) {
		return vanilla().copy(entry, value);
	}

	@Override
	public ItemStack normalize(EntryStack<ItemStack> entry, ItemStack value) {
		return vanilla().normalize(entry, value);
	}

	@Override
	public ItemStack wildcard(EntryStack<ItemStack> entry, ItemStack value) {
		return vanilla().wildcard(entry, value);
	}

	@Override
	public ItemStack cheatsAs(EntryStack<ItemStack> entry, ItemStack value) {
		return vanilla().cheatsAs(entry, value);
	}

	@Override
	public ItemStack add(ItemStack o1, ItemStack o2) {
		return vanilla().add(o1, o2);
	}

	@Override
	public long hash(EntryStack<ItemStack> entry, ItemStack value, ComparisonContext context) {
		String skyblockId = skyblockIdOf(value);
		if (skyblockId == null) return vanilla().hash(entry, value, context);
		long h = skyblockId.hashCode();
		if (context.isExact()) {
			h = h * 31 + value.getCount();
		}
		return h;
	}

	@Override
	public boolean equals(ItemStack o1, ItemStack o2, ComparisonContext context) {
		String id1 = skyblockIdOf(o1);
		String id2 = skyblockIdOf(o2);
		if (id1 == null || id2 == null) return vanilla().equals(o1, o2, context);
		if (!id1.equals(id2)) return false;
		return !context.isExact() || o1.getCount() == o2.getCount();
	}

	@Override
	public EntrySerializer<ItemStack> getSerializer() {
		return vanilla().getSerializer();
	}

	@Override
	public Component asFormattedText(EntryStack<ItemStack> entry, ItemStack value) {
		return vanilla().asFormattedText(entry, value);
	}

	@Override
	public Component asFormattedText(EntryStack<ItemStack> entry, ItemStack value, TooltipContext context) {
		return vanilla().asFormattedText(entry, value, context);
	}

	/**
	 * Confirmed generic signature from the jar: Stream<? extends TagKey<?>>. Keeps the real
	 * vanilla tags too (so e.g. "#swords" search still matches) and adds one synthetic
	 * per-item tag under a "skyblock" namespace, so searching/filtering can also target a
	 * specific SkyBlock id without every reskinned-iron-sword-alike colliding under
	 * "minecraft:iron_sword".
	 */
	@Override
	public Stream<? extends TagKey<?>> getTagsFor(EntryStack<ItemStack> entry, ItemStack value) {
		String skyblockId = skyblockIdOf(value);
		if (skyblockId == null) return vanilla().getTagsFor(entry, value);
		TagKey<Item> skyblockTag = TagKey.create(Registries.ITEM,
			Identifier.fromNamespaceAndPath("skyblock", ReiIdentifierPath.normalize(skyblockId)));
		return Stream.concat(vanilla().getTagsFor(entry, value), Stream.of(skyblockTag));
	}

	@Override
	public void fillCrashReport(CrashReport report, CrashReportCategory category, EntryStack<ItemStack> entry) {
		vanilla().fillCrashReport(report, category, entry);
	}
}
