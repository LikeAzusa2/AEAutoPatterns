package com.likeazusa2.aeautopatterns.integration;

import net.minecraft.world.level.Level;

/** ServiceLoader entry point for third-party machine integrations. */
public interface RecipeAdapterProvider {
    String adapterId();
    int priority();
    RecipeAdapter create(Level level, RecipeAdapter.Settings settings);
}
