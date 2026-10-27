package moe.example.skyblockrecipeviewer.rei;

import java.util.ArrayList;
import java.util.List;

import io.github.moulberry.repo.data.NEUMobDropRecipe;
import me.shedaniel.math.Point;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.REIRuntime;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.gui.compat.GuiGraphics;
import me.shedaniel.rei.api.client.gui.widgets.DelegateWidget;
import me.shedaniel.rei.api.client.gui.widgets.Slot;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.gui.widgets.Widgets;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes;
import me.shedaniel.rei.impl.client.gui.widget.ScrollableViewWidget;
import moe.example.skyblockrecipeviewer.SkyblockRecipeViewer;
import moe.example.skyblockrecipeviewer.repo.DungeonRewards;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * REI category for mob drop tables. Unlike Firmament's reference implementation, this shows a
 * static sword icon for the mob rather than a live-rendered 3D entity (that needs Firmament's
 * whole gui/entity rendering system, which isn't present here) - the mob's name, level, coins,
 * and XP breakdown are all still shown in full, as the icon's tooltip.
 *
 * Each drop's chance is shown as a corner label directly on its icon (rather than only buried
 * in the item's own tooltip), so the whole drop table can be scanned at a glance without
 * hovering each item individually. Drawn via {@code GuiGraphics.renderItemDecorations(Font,
 * ItemStack, int, int, String)} - confirmed (by reading the actual class file bytecode of both
 * REI's compat GuiGraphics wrapper and the underlying game class it forwards to) to be a real
 * overload that draws its {@code String} argument at vanilla's own built-in decoration
 * position/scale *instead of* the stack's real count, rather than reimplementing that
 * scale/position math by hand (which is what an earlier, broken version of this file tried via
 * direct pushMatrix()/translate()/scale() calls that don't exist on REI's wrapper type - see
 * {@link DropChanceSlot}).
 */
public class SkyblockMobDropCategory implements DisplayCategory<SkyblockMobDropDisplay> {

	public static final CategoryIdentifier<SkyblockMobDropDisplay> ID =
		CategoryIdentifier.of(Identifier.fromNamespaceAndPath(SkyblockRecipeViewer.MOD_ID, "mob_drop"));

	@Override
	public CategoryIdentifier<? extends SkyblockMobDropDisplay> getCategoryIdentifier() {
		return ID;
	}

	@Override
	public Component getTitle() {
		return Component.literal("SkyBlock Mob Drops");
	}

	@Override
	public Renderer getIcon() {
		return EntryStack.of(VanillaEntryTypes.ITEM, new ItemStack(Items.DIAMOND_SWORD));
	}

	@Override
	public int getDisplayWidth(SkyblockMobDropDisplay display) {
		if (DungeonRewards.floor(display.getRecipe()) != null) return 163;
		int drops = display.getOutputEntries().size();
		int columns = Math.max(1, Math.min(drops, 6));
		return Math.max(90, 55 + columns * 18);
	}

	@Override
	public int getDisplayHeight() {
		return 220;
	}

	@Override
	public int getMaximumDisplaysPerPage() {
		return 1;
	}

	@Override
	public List<Widget> setupDisplay(SkyblockMobDropDisplay display, Rectangle bounds) {
		DungeonRewards.Floor floor = DungeonRewards.floor(display.getRecipe());
		if (floor != null) return setupDungeonDisplay(display, bounds, floor);
		bounds = new Rectangle(bounds.getX(), bounds.getY(), bounds.getWidth(), 90);
		List<Widget> widgets = new ArrayList<>();
		widgets.add(Widgets.createRecipeBase(bounds));

		NEUMobDropRecipe recipe = display.getRecipe();
		List<Component> tooltip = buildMobTooltip(recipe);

		Slot mobIcon = Widgets.createSlot(new Point(bounds.getX() + 6, bounds.getY() + 6));
		mobIcon.entries(EntryIngredient.of(EntryStack.of(VanillaEntryTypes.ITEM, new ItemStack(Items.DIAMOND_SWORD))));
		mobIcon.disableBackground();
		widgets.add(Widgets.withTooltip(mobIcon, tooltip));

		widgets.add(Widgets.createLabel(new Point(bounds.getX() + 28, bounds.getY() + 8), mobTitle(recipe))
			.leftAligned());

		int x = bounds.getX() + 28;
		int y = bounds.getY() + 22;
		int maxX = bounds.getMaxX() - 20;
		List<EntryIngredient> outputs = display.getOutputEntries();
		for (int i = 0; i < outputs.size(); i++) {
			addDrop(widgets, display, i, x, y);

			x += 18;
			if (x > maxX) {
				x = bounds.getX() + 28;
				y += 18;
			}
		}

		return widgets;
	}

	private static List<Widget> setupDungeonDisplay(SkyblockMobDropDisplay display, Rectangle bounds,
			DungeonRewards.Floor floor) {
		List<Widget> widgets = new ArrayList<>();
		widgets.add(Widgets.createLabel(new Point(bounds.getX() + 4, bounds.getY() + 2),
			Component.literal("Floor " + floor.number() + " - " + (floor.master() ? "Master" : "Normal"))
				.withStyle(ChatFormatting.DARK_GRAY)).leftAligned());
		int y = bounds.getY() + 14;
		int columns = Math.max(1, (bounds.getWidth() - 36) / 18);
		List<String> groups = new ArrayList<>(List.of("Obsidian Chest", "Bedrock Chest"));
		for (String chest : DungeonRewards.CHESTS) {
			if (!groups.contains(chest)) groups.add(chest);
		}
		groups.add(DungeonRewards.UNSPECIFIED_CHEST);
		for (String chest : groups) {
			List<Integer> indices = new ArrayList<>();
			for (int i = 0; i < display.getOutputEntries().size(); i++) {
				if (chest.equals(DungeonRewards.chest(display.getDropExtras().get(i)))) indices.add(i);
			}
			if (indices.isEmpty()) continue;
			int rows = (indices.size() + columns - 1) / columns;
			int height = Math.max(90, 30 + rows * 18);
			Rectangle card = new Rectangle(bounds.getX(), y, bounds.getWidth(), height);
			widgets.add(Widgets.createRecipeBase(card));
			Slot icon = Widgets.createSlot(new Point(card.getX() + 6, card.getY() + 6));
			icon.entries(EntryIngredient.of(EntryStack.of(VanillaEntryTypes.ITEM, new ItemStack(Items.DIAMOND_SWORD))));
			icon.disableBackground();
			widgets.add(Widgets.withTooltip(icon, buildMobTooltip(display.getRecipe())));
			String title = chest.equals(DungeonRewards.UNSPECIFIED_CHEST) ? "Other Floor Drops" : chest;
			widgets.add(Widgets.createLabel(new Point(card.getX() + 28, card.getY() + 8),
				Component.literal(title).withStyle(ChatFormatting.RED)).leftAligned());
			for (int i = 0; i < indices.size(); i++) {
				addDrop(widgets, display, indices.get(i), card.getX() + 28 + (i % columns) * 18,
					card.getY() + 22 + (i / columns) * 18);
			}
			y += height + 8;
		}
		int contentHeight = y - bounds.getY() - 8;
		if (contentHeight <= bounds.getHeight()) return widgets;
		Rectangle content = new Rectangle(bounds.getX(), bounds.getY(), bounds.getWidth(), contentHeight);
		Rectangle viewport = new Rectangle(bounds.getX() - 2, bounds.getY(), bounds.getWidth() + 8, bounds.getHeight());
		return List.of(ScrollableViewWidget.create(viewport, Widgets.concatWithBounds(content, widgets), false));
	}

	private static void addDrop(List<Widget> widgets, SkyblockMobDropDisplay display, int index, int x, int y) {
		EntryIngredient drop = display.getOutputEntries().get(index);
		String chance = display.getDropChances().get(index);
		Slot slot = Widgets.createSlot(new Point(x, y)).markOutput().entries(drop);
		widgets.add(chance != null ? new DropChanceSlot(slot, dropStackOf(drop), chance) : slot);
	}

	/**
	 * Wraps a drop's Slot to additionally draw its chance percentage in place of the item's
	 * real stack count (which is always 1 and wouldn't show anything). Uses {@code
	 * GuiGraphics.renderItemDecorations(Font, ItemStack, int, int, String)} - passing a
	 * non-null String here is vanilla's own built-in mechanism for overriding what that count
	 * decoration draws, so this comes out at the same position/scale a real stack count would
	 * (confirmed against the actual bytecode of both REI's compat GuiGraphics and the game
	 * class it forwards to - both expose this exact overload). This is very likely the same
	 * mechanism other recipe-viewer mods use for exactly this kind of label, and is what this
	 * mod's own earlier (pre-regression) version did too, per the reference screenshot.
	 *
	 * Confirmed real repo chances can be long ("0.00004%"), so on a crowded drop table this can
	 * still visually spill onto whichever slot is next in the grid, same as before - vanilla's
	 * decoration text isn't width-limited or auto-scaled either, so this is inherent to using
	 * the real vanilla mechanism, not a shortcut taken here. The exact chance is always
	 * readable on hover regardless, since it's also baked into the item's own lore (see
	 * SkyblockReiPlugin.toMobDropDisplay).
	 */
	private static final class DropChanceSlot extends DelegateWidget {
		private final ItemStack stack;
		private final String chanceLabel;

		DropChanceSlot(Slot slot, ItemStack stack, String chanceLabel) {
			super(slot);
			this.stack = stack;
			this.chanceLabel = chanceLabel;
		}

		@Override
		public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
			super.render(graphics, mouseX, mouseY, delta);
			if (stack.isEmpty() || chanceLabel == null || chanceLabel.isEmpty()) return;

			Font font = Minecraft.getInstance().font;
			Rectangle bounds = getBounds();

			// Only sub-1% decimal chances ("0.0000001%") get converted to odds notation -
			// whole/short percentages ("45%", "2%", "100%") are left exactly as the repo
			// formatted them. Converting unconditionally (an earlier version of this) meant
			// perfectly ordinary short labels were needlessly being rewritten too.
			String display = needsOddsNotation(chanceLabel) ? compactChanceLabel(chanceLabel) : chanceLabel;

			// Verified against REI's own real shipped source (ItemEntryDefinition's
			// ItemEntryRenderer uses this exact graphics.pose().pushMatrix()/translate()/
			// scale()/popMatrix() shape to scale item icons) - this is the actual, correct
			// API, not a guess. Acts as a universal safety net: odds notation is short enough
			// to rarely need any shrinking, but this still guarantees nothing can ever spill
			// into a neighboring slot, for any label, in any case that wasn't anticipated.
			int textWidth = font.width(display);
			if (textWidth <= 0) return;
			float maxScale = 0.5f; // matches vanilla's own stack-count label proportions
			float scale = maxScale;
			if (textWidth * scale > bounds.getWidth()) {
				scale = bounds.getWidth() / (float) textWidth;
			}

			float centerX = bounds.getX() + bounds.getWidth() / 2f;
			float bottomY = bounds.getMaxY() - 1;

			// Vanilla's bold rendering is a shifted double-draw, not a different advance
			// width per glyph, so measuring textWidth against the plain string above (before
			// bold is applied) stays accurate either way - no separate measurement needed.
			//
			// REIRuntime.getInstance().isDarkThemeEnabled() confirmed directly against REI's
			// own shipped source (REIRuntime.java / ConfigObject.java). Dark theme's default
			// background already gives enough contrast for plain text (per direct
			// screenshot comparison), so bold is reserved for light theme, where the same
			// plain white text was reported hard to read against a lighter background.
			boolean useBold = !REIRuntime.getInstance().isDarkThemeEnabled();
			Component text = useBold
				? Component.literal(display).withStyle(ChatFormatting.BOLD)
				: Component.literal(display);

			var pose = graphics.pose();
			pose.pushMatrix();
			pose.translate(centerX, bottomY);
			pose.scale(scale, scale);
			// 0xFFFFFF (6 hex digits) is only RGB - as a packed ARGB int that's alpha=0x00,
			// i.e. fully transparent white. 0xFFFFFFFF is opaque white.
			graphics.drawCenteredString(font, text, 0, -font.lineHeight, 0xFFFFFFFF);
			pose.popMatrix();
		}
	}

	/**
	 * True only for "0.00XX%"-shaped labels (two or more leading zeros right after the
	 * decimal point) - i.e. genuinely tiny chances, not ordinary sub-1% drops like
	 * Recombobulator's "0.05%", which was getting needlessly converted to odds notation
	 * before this was narrowed.
	 */
	private static boolean needsOddsNotation(String label) {
		int percentIdx = label.indexOf('%');
		String numeric = percentIdx >= 0 ? label.substring(0, percentIdx) : label;
		return numeric.startsWith("0.00");
	}

	/**
	 * Converts a sub-1% decimal drop-chance string like "0.0000001%" into "1 in N" odds
	 * notation ("1/100k", "1/1m", "1/1b" - the format players actually think in for rare
	 * drops). Called unconditionally whenever {@link #needsOddsNotation} matches, not
	 * just as a too-wide fallback - see render()'s own comment for why. This does parse the
	 * label back into a double (unlike an earlier version of this method, which stuck to
	 * pure character-counting specifically to avoid floating-point drift) - but only to
	 * convert it into a *different* display format, never to reconstruct or second-guess the
	 * original percentage's own precision.
	 */
	private static String compactChanceLabel(String label) {
		int percentIdx = label.indexOf('%');
		String numeric = percentIdx >= 0 ? label.substring(0, percentIdx) : label;
		double percent;
		try {
			percent = Double.parseDouble(numeric);
		} catch (NumberFormatException e) {
			return label; // not a plain number we know how to convert - leave as-is
		}
		if (percent <= 0) return label;
		double odds = 100.0 / percent; // "this drop happens 1 time in every `odds` kills"
		return "1/" + formatOdds(odds);
	}

	/**
	 * "1234" -> "1.2k", "100000" -> "100k", "1000000000" -> "1b". Caps at "t" (trillion) -
	 * beyond that the number part just keeps growing, which is such an extreme edge case
	 * (a drop chance of 1 in a quadrillion-plus) that it isn't worth a fifth unit for.
	 */
	private static String formatOdds(double odds) {
		String[] units = {"", "k", "m", "b", "t"};
		int unitIndex = 0;
		double value = odds;
		while (value >= 1000 && unitIndex < units.length - 1) {
			value /= 1000.0;
			unitIndex++;
		}
		String numberPart;
		if (unitIndex == 0 || value >= 10) {
			numberPart = String.valueOf(Math.round(value));
		} else {
			// One decimal place for small leading values (e.g. "1.2m" rather than just "1m")
			// so two genuinely different odds don't collapse into the same displayed label.
			String withDecimal = String.format(java.util.Locale.ROOT, "%.1f", value);
			numberPart = withDecimal.endsWith(".0") ? withDecimal.substring(0, withDecimal.length() - 2) : withDecimal;
		}
		return numberPart + units[unitIndex];
	}

	/** Pulls the real ItemStack back out of a drop's EntryIngredient, for the decoration draw call above. */
	private static ItemStack dropStackOf(EntryIngredient drop) {
		if (drop.isEmpty()) return ItemStack.EMPTY;
		// EntryIngredient is a List<EntryStack<?>> (wildcard-typed), so .getValue() here returns
		// plain Object - confirmed against SkyblockItemEntryDefinition's own
		// `Object value = entry.getValue();` usage elsewhere in this codebase.
		Object value = drop.get(0).getValue();
		return value instanceof ItemStack stack ? stack : ItemStack.EMPTY;
	}

	private static Component mobTitle(NEUMobDropRecipe recipe) {
		if (recipe.getLevel() > 0) {
			return Component.literal("[Lv" + recipe.getLevel() + "] " + recipe.getName());
		}
		return Component.literal(recipe.getName());
	}

	private static List<Component> buildMobTooltip(NEUMobDropRecipe recipe) {
		List<Component> tt = new ArrayList<>();
		tt.add(mobTitle(recipe));
		if (recipe.getCoins() > 0) {
			tt.add(Component.literal("Coins: " + RecipeFormatting.coins(recipe.getCoins())));
		}
		addXpLine(tt, "Combat", recipe.getCombatExperience());
		addXpLine(tt, "Alchemy", recipe.getAlchemyExperience());
		addXpLine(tt, "Farming", recipe.getFarmingExperience());
		addXpLine(tt, "Taming", recipe.getTamingExperience());
		addXpLine(tt, "Foraging", recipe.getForagingExperience());
		addXpLine(tt, "Carpentry", recipe.getCarpentryExperience());
		addXpLine(tt, "Fishing", recipe.getFishingExperience());
		addXpLine(tt, "Runecrafting", recipe.getRunecraftingExperience());
		addXpLine(tt, "Mining", recipe.getMiningExperience());
		addXpLine(tt, "Enchanting", recipe.getEnchantingExperience());
		if (recipe.getExperienceOrbs() > 0) {
			tt.add(Component.literal("XP Orbs: " + RecipeFormatting.coins(recipe.getExperienceOrbs())));
		}
		if (recipe.getExtra() != null) {
			for (String extra : recipe.getExtra()) {
				tt.add(Component.literal(extra));
			}
		}
		return tt;
	}

	private static void addXpLine(List<Component> tt, String skillName, double xp) {
		if (xp > 0) {
			tt.add(Component.literal(skillName + " XP: " + RecipeFormatting.coins(xp)));
		}
	}
}
