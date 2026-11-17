package com.likeazusa2.aeautopatterns.integration;
import java.util.List;
import java.util.EnumSet;
import java.util.Set;
import appeng.api.stacks.AEKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import com.likeazusa2.aeautopatterns.logic.DependencyPlanner;
/** Server-side extension point for future machine recipe adapters. */
public interface RecipeAdapter {
    /** Settings shared by built-in and third-party adapters. */
    record Settings(boolean allowProcessing, boolean substituteItems, boolean substituteFluids) {
        public static Settings defaults() { return new Settings(false, false, true); }
    }
    default String adapterId() { return getClass().getName(); }
    default int priority() { return 0; }
    default Set<AdapterCapability> capabilities() { return Set.of(AdapterCapability.ITEMS, AdapterCapability.VALIDATION); }
    default boolean indexStep(int budget) { return true; }
    default boolean hasRecipe(AEKey output) { return !find(output).isEmpty(); }
    default boolean unsupported(AEKey output) { return false; }
    List<DependencyPlanner.Choice<AEKey,PatternRecipe>> find(AEKey output);
    ItemStack encode(PatternRecipe recipe, List<AEKey> inputs, AEKey expected, Level level);
    default String describe(PatternRecipe recipe) { return recipe.adapted() == null ? "" : recipe.adapted().recipeId().toString(); }
}
