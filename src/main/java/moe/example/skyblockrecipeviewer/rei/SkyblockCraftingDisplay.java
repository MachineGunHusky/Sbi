package moe.example.skyblockrecipeviewer.rei;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.MapCodec;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.DisplaySerializer;
import me.shedaniel.rei.api.common.display.basic.BasicDisplay;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

public class SkyblockCraftingDisplay extends BasicDisplay {
	private final List<RecipeCostTooltip.Cost> costs;

	// This display is (re)built purely client-side from the downloaded item repo on every
	// REI plugin reload - it's never saved to disk or sent over the network, so the
	// serializer just needs to satisfy Display's abstract getSerializer() without ever
	// actually being asked to encode/decode anything (isPersistent = false).
	private static final DisplaySerializer<SkyblockCraftingDisplay> SERIALIZER = DisplaySerializer.of(
		MapCodec.unit(() -> {
			throw new UnsupportedOperationException(
				"SkyblockCraftingDisplay is generated at runtime and is not persisted or networked.");
		}),
		StreamCodec.of(
			(buf, value) -> {
				throw new UnsupportedOperationException(
					"SkyblockCraftingDisplay is generated at runtime and is not persisted or networked.");
			},
			buf -> {
				throw new UnsupportedOperationException(
					"SkyblockCraftingDisplay is generated at runtime and is not persisted or networked.");
			}),
		false);

	public SkyblockCraftingDisplay(List<EntryIngredient> inputs, List<EntryIngredient> outputs,
			List<RecipeCostTooltip.Cost> costs) {
		super(inputs, outputs);
		this.costs = List.copyOf(costs);
	}

	List<RecipeCostTooltip.Cost> getCosts() {
		return costs;
	}

	@Override
	public CategoryIdentifier<?> getCategoryIdentifier() {
		return SkyblockCraftingCategory.ID;
	}

	@Override
	public Optional<Identifier> getDisplayLocation() {
		return Optional.empty();
	}

	@Override
	public DisplaySerializer<SkyblockCraftingDisplay> getSerializer() {
		return SERIALIZER;
	}
}
