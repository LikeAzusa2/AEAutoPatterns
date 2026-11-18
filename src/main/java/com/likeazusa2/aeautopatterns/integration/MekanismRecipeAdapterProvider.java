package com.likeazusa2.aeautopatterns.integration;

import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/** Explicit Mekanism item processing plus Applied Mekanistics chemical conversion. */
public final class MekanismRecipeAdapterProvider implements RecipeAdapterProvider {
    @Override public String adapterId() { return "aeautopatterns:mekanism-processing"; }
    @Override public int priority() { return 60; }

    @Override public RecipeAdapter create(Level level, RecipeAdapter.Settings settings) {
        if (!settings.allowProcessing() || !ModList.get().isLoaded("mekanism")) return null;
        return new MekanismChemicalRecipeAdapter(level, ModList.get().isLoaded("appmek"));
    }
}
