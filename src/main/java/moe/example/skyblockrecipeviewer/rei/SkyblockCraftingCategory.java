package moe.example.skyblockrecipeviewer.rei;

import java.util.ArrayList;
import java.util.List;

import dev.architectury.event.EventResult;
import me.shedaniel.math.Point;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.gui.widgets.Slot;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.gui.widgets.Widgets;
import me.shedaniel.rei.api.client.registry.category.visibility.CategoryVisibilityPredicate;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes;
import me.shedaniel.rei.plugin.common.BuiltinPlugin;
import moe.example.skyblockrecipeviewer.SkyblockRecipeViewer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class SkyblockCraftingCategory implements DisplayCategory<SkyblockCraftingDisplay> {

	public static final CategoryIdentifier<SkyblockCraftingDisplay> ID =
		CategoryIdentifier.of(Identifier.fromNamespaceAndPath(SkyblockRecipeViewer.MOD_ID, "crafting"));

	/**
	 * Vanilla item IDs/crafting grids are meaningless on Hypixel SkyBlock (items are
	 * reskinned/renamed server-side and most vanilla recipes are disabled entirely), so the
	 * vanilla "Crafting" category REI adds by default would just clutter the recipe book -
	 * hide it rather than remove it, since other mods may still want it visible.
	 */
	public static CategoryVisibilityPredicate hideVanillaCrafting() {
		return category -> category.getCategoryIdentifier().equals(BuiltinPlugin.CRAFTING)
			? EventResult.interruptFalse()
			: EventResult.pass();
	}

	@Override
	public CategoryIdentifier<? extends SkyblockCraftingDisplay> getCategoryIdentifier() {
		return ID;
	}

	@Override
	public Component getTitle() {
		return Component.literal("SkyBlock Crafting");
	}

	@Override
	public Renderer getIcon() {
		return EntryStack.of(VanillaEntryTypes.ITEM, new ItemStack(Items.CRAFTING_TABLE));
	}

	@Override
	public int getDisplayWidth(SkyblockCraftingDisplay display) {
		return 140;
	}

	@Override
	public int getDisplayHeight() {
		return 66;
	}

	@Override
	public List<Widget> setupDisplay(SkyblockCraftingDisplay display, Rectangle bounds) {
		Point origin = new Point(bounds.getCenterX() - 58, bounds.getCenterY() - 27);
		List<Widget> widgets = new ArrayList<>();
		widgets.add(Widgets.createRecipeBase(bounds));

		List<EntryIngredient> inputs = display.getInputEntries();
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 3; col++) {
				int index = row * 3 + col;
				Slot slot = Widgets.createSlot(new Point(origin.x + 1 + col * 18, origin.y + 1 + row * 18));
				if (index < inputs.size()) {
					slot.entries(inputs.get(index));
				}
				widgets.add(slot);
			}
		}

		widgets.add(Widgets.createArrow(new Point(origin.x + 60, origin.y + 18)));

		Slot outputSlot = Widgets.createSlot(new Point(origin.x + 96, origin.y + 19));
		if (!display.getOutputEntries().isEmpty()) {
			outputSlot.entries(display.getOutputEntries().get(0));
		}
		outputSlot.disableBackground();
		widgets.add(outputSlot);

		return widgets;
	}
}
