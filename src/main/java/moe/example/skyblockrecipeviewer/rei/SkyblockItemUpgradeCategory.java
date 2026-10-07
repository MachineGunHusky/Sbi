package moe.example.skyblockrecipeviewer.rei;

import java.util.ArrayList;
import java.util.List;

import me.shedaniel.math.Point;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.gui.widgets.Slot;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.gui.widgets.WidgetWithBounds;
import me.shedaniel.rei.api.client.gui.widgets.Widgets;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes;
import moe.example.skyblockrecipeviewer.SkyblockRecipeViewer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** REI category for upgrades applied by consuming another item. */
public final class SkyblockItemUpgradeCategory implements DisplayCategory<SkyblockItemUpgradeDisplay> {
	public static final CategoryIdentifier<SkyblockItemUpgradeDisplay> ID =
		CategoryIdentifier.of(Identifier.fromNamespaceAndPath(SkyblockRecipeViewer.MOD_ID, "item_upgrades"));

	@Override
	public CategoryIdentifier<? extends SkyblockItemUpgradeDisplay> getCategoryIdentifier() {
		return ID;
	}

	@Override
	public Component getTitle() {
		return Component.literal("SkyBlock Item Upgrades");
	}

	@Override
	public Renderer getIcon() {
		return EntryStack.of(VanillaEntryTypes.ITEM, new ItemStack(Items.SMITHING_TABLE));
	}

	@Override
	public int getDisplayWidth(SkyblockItemUpgradeDisplay display) {
		return 150;
	}

	@Override
	public int getDisplayHeight() {
		return 60;
	}

	@Override
	public List<Widget> setupDisplay(SkyblockItemUpgradeDisplay display, Rectangle bounds) {
		List<Widget> widgets = new ArrayList<>();
		widgets.add(Widgets.createRecipeBase(bounds));

		List<EntryIngredient> inputs = display.getInputEntries();
		List<EntryIngredient> outputs = display.getOutputEntries();
		int left = bounds.getX() + 6;
		int top = bounds.getY() + 6;
		if (!inputs.isEmpty()) {
			Slot itemSlot = Widgets.createSlot(new Point(left, top)).markInput();
			itemSlot.entries(inputs.get(0));
			widgets.add(itemSlot);
		}
		if (inputs.size() > 1) {
			Slot upgradeSlot = Widgets.createSlot(new Point(left + 18, top + 18)).markInput();
			upgradeSlot.entries(inputs.get(1));
			widgets.add(upgradeSlot);
		}

		WidgetWithBounds arrow = Widgets.createArrow(new Point(bounds.getX() + 62, top));
		widgets.add(Widgets.withTooltip(arrow, List.of(Component.literal(display.getRecipe().description()))));

		if (!outputs.isEmpty()) {
			Slot outputSlot = Widgets.createSlot(new Point(bounds.getMaxX() - 24, top)).markOutput();
			outputSlot.entries(outputs.get(0));
			widgets.add(outputSlot);
		}
		return widgets;
	}
}
