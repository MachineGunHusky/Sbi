package moe.example.skyblockrecipeviewer.rei;

import java.util.ArrayList;
import java.util.List;

import me.shedaniel.math.Point;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.gui.compat.GuiGraphics;
import me.shedaniel.rei.api.client.gui.widgets.DelegateWidget;
import me.shedaniel.rei.api.client.gui.widgets.Slot;
import me.shedaniel.rei.api.client.gui.widgets.Tooltip;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.gui.widgets.Widgets;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes;
import moe.example.skyblockrecipeviewer.SkyblockRecipeViewer;
import moe.example.skyblockrecipeviewer.repo.reforge.ReforgeData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * REI category for reforge-STONE reforges (~80 of them) - deliberately tiny (just an icon) so
 * REI's own grid layout packs many stones next to each other on one page, rather than one huge
 * mostly-empty page per stone the way {@link SkyblockReforgeCategory} still does for the
 * handful of free Blacksmith reforges (few enough that a detailed page each makes sense there).
 *
 * A typical stone's stat table spans 5-7 rarities - far too much to show all at once on a card
 * this size (or even as a normal static tooltip without it turning into a wall of text). Instead
 * of a static tooltip, hovering a stone shows ONE rarity at a time, cycled with the Left/Right
 * arrow keys - see {@link PaginatedStoneSlot}.
 */
public class SkyblockReforgeStoneCategory implements DisplayCategory<SkyblockReforgeStoneDisplay> {

	public static final CategoryIdentifier<SkyblockReforgeStoneDisplay> ID =
		CategoryIdentifier.of(Identifier.fromNamespaceAndPath(SkyblockRecipeViewer.MOD_ID, "reforge_stone"));

	@Override
	public CategoryIdentifier<? extends SkyblockReforgeStoneDisplay> getCategoryIdentifier() {
		return ID;
	}

	@Override
	public Component getTitle() {
		return Component.literal("SkyBlock Reforge Stones");
	}

	@Override
	public Renderer getIcon() {
		return EntryStack.of(VanillaEntryTypes.ITEM, new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
	}

	@Override
	public int getDisplayWidth(SkyblockReforgeStoneDisplay display) {
		// Just wide enough for a name that's occasionally a bit long (e.g. "Reinforced");
		// REI wraps/truncates longer ones. Kept tight since the whole point is packing many
		// of these onto one page.
		return 70;
	}

	@Override
	public int getDisplayHeight() {
		return 36;
	}

	@Override
	public List<Widget> setupDisplay(SkyblockReforgeStoneDisplay display, Rectangle bounds) {
		List<Widget> widgets = new ArrayList<>();
		widgets.add(Widgets.createRecipeBase(bounds));

		ReforgeData reforge = display.getReforge();
		int left = bounds.getX() + (bounds.getWidth() - 18) / 2;
		int top = bounds.getY() + 4;

		List<EntryIngredient> inputs = display.getInputEntries();
		ItemStack stoneStack = inputs.isEmpty() || inputs.get(0).isEmpty()
			? new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE)
			// EntryIngredient is a List<EntryStack<?>> (wildcard-typed), so .getValue() here
			// returns plain Object - confirmed against SkyblockItemEntryDefinition's own
			// `Object value = entry.getValue();` usage, rather than assuming an unverified
			// .castValue() convenience method exists.
			: (ItemStack) inputs.get(0).get(0).getValue();

		List<String> rarities = !display.getHighlightRarities().isEmpty()
			? display.getHighlightRarities()
			: ReforgeStatFormatting.orderedRarities(reforge);
		if (rarities.isEmpty()) rarities = List.of(ReforgeData.RARITY_ANY);

		Slot innerSlot = Widgets.createSlot(new Point(left, top))
			.entries(EntryIngredient.of(EntryStack.of(VanillaEntryTypes.ITEM, stoneStack)))
			// Tooltips normally shown automatically per-slot are replaced entirely by
			// PaginatedStoneSlot's own dynamic one below (which re-includes the item's real
			// name/lore alongside the current rarity's stats), so the default one would just
			// be redundant/conflicting.
			.disableTooltips();
		widgets.add(new PaginatedStoneSlot(innerSlot, stoneStack, reforge, rarities));

		widgets.add(Widgets.createLabel(new Point(bounds.getCenterX(), top + 22), Component.literal(reforge.reforgeName())));

		return widgets;
	}

	/**
	 * Wraps a reforge-stone Slot to show ONE rarity's stats at a time instead of all of them
	 * at once (which for a 5-7 rarity stone would be an unreadable wall of text at this card
	 * size), cycled with the Left/Right arrow keys while hovering.
	 */
	private static final class PaginatedStoneSlot extends DelegateWidget {
		private final ItemStack stoneStack;
		private final ReforgeData reforge;
		private final List<String> rarities;
		private int pageIndex = 0;

		PaginatedStoneSlot(Slot slot, ItemStack stoneStack, ReforgeData reforge, List<String> rarities) {
			super(slot);
			this.stoneStack = stoneStack;
			this.reforge = reforge;
			this.rarities = rarities;
		}

		@Override
		public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
			super.render(graphics, mouseX, mouseY, delta);
			if (containsMouse(mouseX, mouseY)) {
				Tooltip.create(buildTooltipLines()).queue();
			}
		}

		private List<Component> buildTooltipLines() {
			List<Component> lines = new ArrayList<>();
			lines.add(stoneStack.getHoverName().copy());
			ItemLore existingLore = stoneStack.get(DataComponents.LORE);
			if (existingLore != null) lines.addAll(existingLore.lines());
			lines.add(Component.empty());

			String rarity = rarities.get(pageIndex);
			lines.add(Component.literal(reforge.reforgeName() + " Reforge Stats:").withStyle(ChatFormatting.GRAY));
			lines.add(Component.literal(rarity)
				.withStyle(s -> s.withColor(ReforgeStatFormatting.colorFor(rarity)).withBold(true)));
			for (String statLine : ReforgeStatFormatting.statsLines(reforge, rarity)) {
				lines.add(Component.literal("  " + statLine).withStyle(ChatFormatting.GRAY));
			}
			if (rarities.size() > 1) {
				lines.add(Component.literal("\u25C4 " + (pageIndex + 1) + "/" + rarities.size() + " \u25BA "
					+ "(arrow keys to cycle)").withStyle(ChatFormatting.DARK_GRAY));
			}
			return lines;
		}

		@Override
		public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
			if (rarities.size() > 1 && containsMouse(Widget.mouse())) {
				int key = event.key();
				if (key == 262) { // GLFW_KEY_RIGHT
					pageIndex = (pageIndex + 1) % rarities.size();
					return true;
				} else if (key == 263) { // GLFW_KEY_LEFT
					pageIndex = (pageIndex - 1 + rarities.size()) % rarities.size();
					return true;
				}
			}
			return super.keyPressed(event);
		}
	}
}
