package com.likeazusa2.aeautopatterns.integration;

import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

/** Exact recipe type/class allow-list and parser for one optional mod machine family. */
public record ReflectiveRecipeFamily(ResourceLocation familyId, ResourceLocation machineType,
        Set<ResourceLocation> recipeTypes, Set<String> exactClasses, Parser parser) {
    public ReflectiveRecipeFamily {
        Objects.requireNonNull(familyId, "familyId");
        Objects.requireNonNull(machineType, "machineType");
        recipeTypes = recipeTypes == null ? Set.of() : Set.copyOf(recipeTypes);
        exactClasses = exactClasses == null ? Set.of() : Set.copyOf(exactClasses);
        Objects.requireNonNull(parser, "parser");
        if (recipeTypes.isEmpty() || exactClasses.isEmpty())
            throw new IllegalArgumentException("recipe family must declare exact type and class");
    }

    @FunctionalInterface
    public interface Parser {
        RecipeDraft parse(Level level, RecipeHolder<?> holder) throws RecipeRejectedException;
    }

    public static final class RecipeRejectedException extends Exception {
        public RecipeRejectedException(String message) { super(message); }
        public RecipeRejectedException(String message, Throwable cause) { super(message, cause); }
    }
}
