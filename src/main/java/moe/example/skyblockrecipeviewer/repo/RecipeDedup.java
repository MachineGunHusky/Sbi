package moe.example.skyblockrecipeviewer.repo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.github.moulberry.repo.data.NEUCraftingRecipe;
import io.github.moulberry.repo.data.NEUForgeRecipe;
import io.github.moulberry.repo.data.NEUIngredient;
import io.github.moulberry.repo.data.NEUKatUpgradeRecipe;
import io.github.moulberry.repo.data.NEUMobDropRecipe;

/**
 * Collapses exact-duplicate recipes that can show up on a single {@code NEUItem}'s own
 * {@code getRecipes()} list back down to one.
 *
 * <h2>Why an item's own recipe list can have exact duplicates in the first place</h2>
 * The NEU repo item format originally described a single crafting recipe with a flat set of
 * "A1".."C3" grid-slot fields directly on the item. A later repo change introduced a separate
 * "recipes" JSON array specifically so an item could carry more than one recipe (the repo's
 * own commit message: "New recipes should be added in the recipes json field... This also
 * adds support for having multiple recipes for a single item"). An item that only ever needed
 * its one original recipe still carries it under *both* the old flat fields and the new array
 * once anything re-saves that item's entry through a newer repo-editing tool, describing the
 * exact same recipe twice. RepoParser reads both formats for backward compatibility, so it
 * faithfully - and correctly, from its own point of view - hands back two distinct NEURecipe
 * objects that just happen to be identical. Neither {@code NEURecipe} nor its subclasses
 * implement content-based equals()/hashCode(), so nothing collapses those automatically
 * anywhere upstream of this class - which is why every recipe in REI was showing up twice.
 *
 * <h2>Why per-item, not globally</h2>
 * {@link NeuRepoManager}'s recipe getters call this once per {@code NEUItem}, before ever
 * flatMapping everything into one combined list - dedup only ever needs to compare the
 * handful of recipes that were already on the *same* item to begin with, which keeps the
 * signature functions below simple: they don't need to fold the source item's id in to avoid
 * an unrelated item's recipe accidentally colliding with this one's signature.
 */
public final class RecipeDedup {
	private RecipeDedup() {
	}

	/** Keeps the first recipe seen for each distinct signature, preserving original order. */
	public static <T> List<T> dedupe(List<T> recipes, Function<T, String> signature) {
		if (recipes == null) return List.of();
		if (recipes.size() < 2) return recipes;
		Map<String, T> bySignature = new LinkedHashMap<>();
		for (T recipe : recipes) {
			bySignature.putIfAbsent(signature.apply(recipe), recipe);
		}
		return new ArrayList<>(bySignature.values());
	}

	public static String craftingSignature(NEUCraftingRecipe recipe) {
		StringBuilder sb = new StringBuilder("crafting|").append(ingredientSig(recipe.getOutput()));
		NEUIngredient[] inputs = recipe.getInputs();
		if (inputs != null) {
			for (NEUIngredient input : inputs) {
				sb.append('|').append(ingredientSig(input));
			}
		}
		return sb.toString();
	}

	public static String forgeSignature(NEUForgeRecipe recipe) {
		StringBuilder sb = new StringBuilder("forge|")
			.append(ingredientSig(recipe.getOutputStack()))
			.append('|').append(recipe.getDuration());
		List<NEUIngredient> inputs = recipe.getInputs();
		if (inputs != null) {
			for (NEUIngredient input : inputs) {
				sb.append('|').append(ingredientSig(input));
			}
		}
		return sb.toString();
	}

	public static String katUpgradeSignature(NEUKatUpgradeRecipe recipe) {
		StringBuilder sb = new StringBuilder("katupgrade|")
			.append(ingredientSig(recipe.getInput()))
			.append('|').append(ingredientSig(recipe.getOutput()));
		List<NEUIngredient> items = recipe.getItems();
		if (items != null) {
			for (NEUIngredient item : items) {
				sb.append('|').append(ingredientSig(item));
			}
		}
		return sb.toString();
	}

	public static String mobDropSignature(NEUMobDropRecipe recipe) {
		StringBuilder sb = new StringBuilder("mobdrop|").append(recipe.getName())
			.append('|').append(recipe.getLevel()).append('|').append(recipe.getPanorama())
			.append('|').append(recipe.getRender()).append('|').append(recipe.getExtra());
		if (recipe.getDrops() != null) {
			for (NEUMobDropRecipe.Drop drop : recipe.getDrops()) {
				sb.append('|').append(ingredientSig(drop.getDropItem()))
					.append('|').append(drop.getChance()).append('|').append(drop.getExtra());
			}
		}
		return sb.toString();
	}

	private static String ingredientSig(NEUIngredient ingredient) {
		if (ingredient == null) return "-";
		String id = ingredient.getItemId();
		return (id == null ? "" : id) + "x" + ingredient.getAmount();
	}
}
