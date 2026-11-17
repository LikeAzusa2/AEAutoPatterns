package com.likeazusa2.aeautopatterns.integration;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
/** Existing patterns retain their exact definition; new patterns retain their recipe holder. */
public record PatternRecipe(RecipeHolder<?> recipe, ItemStack existing, RecipeAdapter adapter, AdaptedRecipe adapted) {
    public PatternRecipe(RecipeHolder<?> recipe, ItemStack existing, RecipeAdapter adapter) {
        this(recipe, existing, adapter, null);
    }
}
