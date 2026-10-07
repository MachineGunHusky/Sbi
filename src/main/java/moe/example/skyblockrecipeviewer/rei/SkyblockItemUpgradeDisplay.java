package moe.example.skyblockrecipeviewer.rei;

import java.util.List;
import java.util.Optional;

import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.DisplaySerializer;
import me.shedaniel.rei.api.common.display.basic.BasicDisplay;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import net.minecraft.resources.Identifier;

/** A single consumable item upgrade as an REI display. */
public final class SkyblockItemUpgradeDisplay extends BasicDisplay {
	private static final DisplaySerializer<SkyblockItemUpgradeDisplay> SERIALIZER =
		RuntimeOnlyDisplaySerializer.create("SkyblockItemUpgradeDisplay");

	private final SkyblockItemUpgradeRecipe recipe;

	public SkyblockItemUpgradeDisplay(SkyblockItemUpgradeRecipe recipe,
			List<EntryIngredient> inputs, List<EntryIngredient> outputs) {
		super(inputs, outputs);
		this.recipe = recipe;
	}

	public SkyblockItemUpgradeRecipe getRecipe() {
		return recipe;
	}

	@Override
	public CategoryIdentifier<?> getCategoryIdentifier() {
		return SkyblockItemUpgradeCategory.ID;
	}

	@Override
	public Optional<Identifier> getDisplayLocation() {
		return Optional.empty();
	}

	@Override
	public DisplaySerializer<SkyblockItemUpgradeDisplay> getSerializer() {
		return SERIALIZER;
	}
}
