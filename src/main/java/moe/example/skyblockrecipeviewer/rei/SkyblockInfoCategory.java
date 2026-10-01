package moe.example.skyblockrecipeviewer.rei;

import me.shedaniel.math.Point;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.gui.widgets.Widgets;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.util.EntryStacks;
import moe.example.skyblockrecipeviewer.SkyblockRecipeViewer;
import moe.example.skyblockrecipeviewer.repo.SkyblockWikiManager;
import net.minecraft.util.Util;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SkyblockInfoCategory implements DisplayCategory<SkyblockInfoDisplay> {
	private static final int OFFSET = 10;
	private static final int REI_SLOT_HEIGHT = 18;
	private static final EntryStack<ItemStack> ICON = EntryStacks.of(new ItemStack(Items.KNOWLEDGE_BOOK));

	public static final CategoryIdentifier<SkyblockInfoDisplay> ID =
		CategoryIdentifier.of(Identifier.fromNamespaceAndPath(SkyblockRecipeViewer.MOD_ID, "skyblock_info"));

	@Override
	public CategoryIdentifier<? extends SkyblockInfoDisplay> getCategoryIdentifier() {
		return ID;
	}

	@Override
	public Component getTitle() {
		return Component.literal("SkyBlock Info");
	}

	@Override
	public Renderer getIcon() {
		return ICON;
	}

	@Override
	public List<Widget> setupDisplay(SkyblockInfoDisplay display, Rectangle bounds) {
		List<Widget> widgets = new ArrayList<>();
		EntryStack<?> entry = display.getInputEntries().getFirst().getFirst();
		widgets.add(Widgets.createRecipeBase(bounds));
		widgets.add(Widgets.createSlot(
			new Point(bounds.getCenterX() - 9 + 1, bounds.y + 1 + OFFSET / 2))
			.entry(entry));

		String id = SkyblockItemEntryDefinition.getSkyblockId(entry);
		if (id == null) return widgets;

		Optional<String> wikiUrl = SkyblockWikiManager.getInstance().getWikiUrl(id);
		if (wikiUrl.isEmpty()) return widgets;

		LinearLayout layout = LinearLayout.vertical();
		layout.setPosition(bounds.x + OFFSET, bounds.y + OFFSET + REI_SLOT_HEIGHT);

		layout.addChild(Button.builder(Component.literal("Open Wiki"), button -> {
			Util.getPlatform().openUri(wikiUrl.get());
		}).build());

		layout.visitWidgets(child -> widgets.add(Widgets.wrapVanillaWidget(child)));
		layout.arrangeElements();
		return widgets;
	}

	@Override
	public int getDisplayHeight() {
		return REI_SLOT_HEIGHT + 2 * OFFSET + Button.DEFAULT_HEIGHT;
	}

	@Override
	public int getDisplayWidth(SkyblockInfoDisplay display) {
		return 170;
	}
}
