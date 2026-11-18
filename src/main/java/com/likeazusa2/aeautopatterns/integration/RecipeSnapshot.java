package com.likeazusa2.aeautopatterns.integration;

import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

/** A parsed recipe that can re-read and verify its source before encoding. */
public interface RecipeSnapshot {
    RecipeHolder<?> holder();
    void verify(Level level, AdaptedRecipe expected);
}
