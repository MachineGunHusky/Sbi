package moe.example.skyblockrecipeviewer.rei;

import java.util.List;
import java.util.Optional;

import io.github.moulberry.repo.data.NEUMobDropRecipe;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.DisplaySerializer;
import me.shedaniel.rei.api.common.display.basic.BasicDisplay;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import net.minecraft.resources.Identifier;

/**
 * A single mob's drop table as an REI display. No input entries (a mob isn't an item) - just
 * every item that mob can drop, as outputs.
 */
public class SkyblockMobDropDisplay extends BasicDisplay {

	private static final DisplaySerializer<SkyblockMobDropDisplay> SERIALIZER =
		RuntimeOnlyDisplaySerializer.create("SkyblockMobDropDisplay");

	private final NEUMobDropRecipe recipe;
	/**
	 * Parallel to {@link #getOutputEntries()} - element i is the raw "chance" string (e.g.
	 * "100%", "2%", "0.00004%" - confirmed straight off a real repo mob file, already
	 * formatted for display, no numeric parsing needed) for drop i, or null if that particular
	 * drop has no chance at all. Dungeon rewards may lack probability data; other drops may
	 * instead describe a hunting condition in {@link #getDropExtras()}.
	 */
	private final List<String> dropChances;
	/** Parallel to {@link #getOutputEntries()} - element i is drop i's own "extra" lines (may be null/empty). */
	private final List<List<String>> dropExtras;

	public SkyblockMobDropDisplay(NEUMobDropRecipe recipe, List<EntryIngredient> inputs,
			List<EntryIngredient> outputs, List<String> dropChances,
			List<List<String>> dropExtras) {
		super(inputs, outputs);
		this.recipe = recipe;
		this.dropChances = dropChances;
		this.dropExtras = dropExtras;
	}

	public NEUMobDropRecipe getRecipe() {
		return recipe;
	}

	public List<String> getDropChances() {
		return dropChances;
	}

	public List<List<String>> getDropExtras() {
		return dropExtras;
	}

	@Override
	public CategoryIdentifier<?> getCategoryIdentifier() {
		return SkyblockMobDropCategory.ID;
	}

	@Override
	public Optional<Identifier> getDisplayLocation() {
		return Optional.empty();
	}

	@Override
	public DisplaySerializer<SkyblockMobDropDisplay> getSerializer() {
		return SERIALIZER;
	}
}
