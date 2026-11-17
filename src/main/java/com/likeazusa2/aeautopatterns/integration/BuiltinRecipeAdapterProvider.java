package com.likeazusa2.aeautopatterns.integration;

import net.minecraft.world.level.Level;

/** Documents the built-in provider contract; the registry constructs this adapter directly. */
public final class BuiltinRecipeAdapterProvider implements RecipeAdapterProvider {
    @Override public String adapterId() { return "aeautopatterns:vanilla"; }
    @Override public int priority() { return 0; }
    @Override public RecipeAdapter create(Level level, RecipeAdapter.Settings settings) {
        return new CraftingRecipeAdapter(level, settings.allowProcessing(), settings.substituteItems(), settings.substituteFluids());
    }
}
