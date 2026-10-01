package moe.example.skyblockrecipeviewer.rei;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import io.github.moulberry.repo.data.NEUCraftingRecipe;
import io.github.moulberry.repo.data.NEUIngredient;
import me.shedaniel.rei.api.client.registry.display.DynamicDisplayGenerator;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.entry.EntryStack;
import moe.example.skyblockrecipeviewer.repo.NeuRepoManager;
import moe.example.skyblockrecipeviewer.repo.PetAcquisitionStore;
import moe.example.skyblockrecipeviewer.repo.SkyblockItemResolver;

/**
 * Fixes recipes not appearing when joining a multiplayer (Hypixel) server, while joining a
 * singleplayer world worked fine.
 *
 * The root cause: registerDisplays (a REI plugin-reload callback) only runs at specific
 * points - game launch, an explicit "Reload Plugins" click, and, incidentally, singleplayer
 * world join (which triggers a client-side resource/data-pack reload REI also happens to
 * hook into). Joining a remote multiplayer server doesn't fire that same reload, so if the
 * repo/skin data wasn't already loaded by the time the *last* reload happened, recipes never
 * get a chance to register - no matter how long you've been connected to Hypixel since.
 *
 * DynamicDisplayGenerator sidesteps the whole reload lifecycle: getRecipeFor/getUsageFor are
 * called on demand, at the moment a player actually looks something up (hover an item, press
 * R/U, or search), not during a batch reload pass. By then the repo has virtually always
 * finished loading - and even if it somehow hasn't, the very next lookup will just work,
 * with no reload needed.
 *
 * This is a direct port of the architecture CosmicPings (an older-MC-version SkyBlock/REI
 * mod, also NEU-repo-backed) actually ships with - its registerDisplays does *only* this,
 * with no upfront batch registration at all; NeuRepoRepository.get().load(false).join() is
 * called fresh on every single getRecipeFor/getUsageFor call. This keeps our existing eager
 * registerFromRepo path too (it still helps once a reload has happened, e.g. covers browsing
 * the category tab directly - see generate() below), rather than removing it, since it was
 * already working correctly for the singleplayer/normal-reload case.
 */
public final class SkyblockCraftingDisplayGenerator implements DynamicDisplayGenerator<SkyblockCraftingDisplay> {

	public static final SkyblockCraftingDisplayGenerator INSTANCE = new SkyblockCraftingDisplayGenerator();

	private SkyblockCraftingDisplayGenerator() {
	}

	@Override
	public Optional<List<SkyblockCraftingDisplay>> getRecipeFor(EntryStack<?> entry) {
		String skyblockId = skyblockIdOf(entry);
		if (skyblockId == null) return Optional.empty();
		if (PetAcquisitionStore.isClassified(skyblockId) && !PetAcquisitionStore.hasMethod(skyblockId, PetAcquisitionStore.Method.CRAFT)) {
			return Optional.empty();
		}
		return nonEmpty(buildDisplays(recipe -> matches(recipe.getOutput(), skyblockId)));
	}

	@Override
	public Optional<List<SkyblockCraftingDisplay>> getUsageFor(EntryStack<?> entry) {
		String skyblockId = skyblockIdOf(entry);
		if (skyblockId == null) return Optional.empty();
		if (PetAcquisitionStore.isClassified(skyblockId) && !PetAcquisitionStore.hasMethod(skyblockId, PetAcquisitionStore.Method.CRAFT)) {
			return Optional.empty();
		}
		return nonEmpty(buildDisplays(recipe -> anyInputMatches(recipe, skyblockId)));
	}

	/**
	 * An empty list wrapped in Optional.of() is not the same thing to REI as Optional.empty()
	 * here - REI uses whether each category's DynamicDisplayGenerator returned a *present*
	 * Optional (regardless of whether the list inside is empty) to decide whether that
	 * category gets a tab at all on a focused item's recipe/usage screen. Always returning
	 * Optional.of(possibly-empty-list) is what was showing every category's tab on every
	 * item, whether or not that item actually had anything in it - e.g. a sword getting a
	 * "SkyBlock Essence" tab despite no armor-only essence upgrades applying to it.
	 */
	private static <T> Optional<List<T>> nonEmpty(List<T> list) {
		return list.isEmpty() ? Optional.empty() : Optional.of(list);
	}

	/**
	 * REI calls getRecipeFor/getUsageFor *and* generate() together on every single view
	 * request, not just plain "browse the category tab with nothing focused" ones (confirmed
	 * from REI's own ViewsImpl bytecode - generateLiveDisplays() calls all three in sequence
	 * unconditionally) - so returning the full list here unconditionally meant every focused
	 * "recipe for X"/"usage of X" lookup got getRecipeFor's correctly-filtered result *plus*
	 * this generator's entire category dumped in after it, which is exactly the "lists
	 * everything, just puts the item you were after first" symptom. getRecipesFor()/
	 * getUsagesFor() being empty is what actually distinguishes a genuine unfocused browse
	 * from a focused lookup (which REI populates with the looked-up entry before calling any
	 * of these three) - only then does returning everything belong here.
	 */
	@Override
	public Optional<List<SkyblockCraftingDisplay>> generate(ViewSearchBuilder builder) {
		if (!builder.getRecipesFor().isEmpty() || !builder.getUsagesFor().isEmpty()) return Optional.empty();
		return Optional.of(buildDisplays(recipe -> true));
	}

	/**
	 * Delegates to SkyblockItemEntryDefinition.getSkyblockId(EntryStack), which - unlike this
	 * method's old body - also resolves real gameplay items sitting in the player's own
	 * inventory (a plain VanillaEntryTypes.ITEM entry, not one of our own), so R/U work there
	 * too, not just on items in REI's own panel.
	 */
	private static String skyblockIdOf(EntryStack<?> entry) {
		return SkyblockItemEntryDefinition.getSkyblockId(entry);
	}

	private static boolean matches(NEUIngredient ingredient, String skyblockId) {
		if (SkyblockItemResolver.isEmptySlot(ingredient)) return false;
		return skyblockId.equals(ingredient.getItemId());
	}

	private static boolean anyInputMatches(NEUCraftingRecipe recipe, String skyblockId) {
		NEUIngredient[] inputs = recipe.getInputs();
		if (inputs == null) return false;
		for (NEUIngredient ingredient : inputs) {
			if (matches(ingredient, skyblockId)) return true;
		}
		return false;
	}

	private static List<SkyblockCraftingDisplay> buildDisplays(Predicate<NEUCraftingRecipe> filter) {
		NeuRepoManager.RepositoryData data = NeuRepoManager.getInstance().getRepositoryDataOrNull();
		if (data == null) return List.of();

		List<SkyblockCraftingDisplay> displays = new ArrayList<>();
		for (NEUCraftingRecipe recipe : data.craftingRecipes()) {
			if (!filter.test(recipe)) continue;
			SkyblockCraftingDisplay display = SkyblockReiPlugin.toDisplay(data.repository(), recipe);
			if (display != null) displays.add(display);
		}
		return displays;
	}
}
