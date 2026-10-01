package moe.example.skyblockrecipeviewer.rei;

import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.DisplaySerializer;
import me.shedaniel.rei.api.common.display.basic.BasicDisplay;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Optional;

/** REI information display used for our own SkyBlock wiki button. */
public final class SkyblockInfoDisplay extends BasicDisplay {
	private static final DisplaySerializer<SkyblockInfoDisplay> SERIALIZER =
		RuntimeOnlyDisplaySerializer.create("SkyblockInfoDisplay");

	public SkyblockInfoDisplay(EntryIngredient item) {
		super(List.of(item), List.of());
	}

	@Override
	public CategoryIdentifier<?> getCategoryIdentifier() {
		return SkyblockInfoCategory.ID;
	}

	@Override
	public Optional<Identifier> getDisplayLocation() {
		return Optional.empty();
	}

	@Override
	public DisplaySerializer<SkyblockInfoDisplay> getSerializer() {
		return SERIALIZER;
	}
}
