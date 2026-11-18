package com.likeazusa2.aeautopatterns.integration;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import com.likeazusa2.aeautopatterns.logic.DependencyPlanner;

/** Reports known mod outputs as unsupported without guessing machine recipe semantics. */
final class UnverifiedModRecipeAdapter implements RecipeAdapter {
    private final String modId;
    private final String id;
    private final int priority;
    private final Level level;
    private final Iterator<RecipeHolder<?>> pending;
    private final Set<AEKey> unverified = new HashSet<>();
    private boolean indexed;

    UnverifiedModRecipeAdapter(String modId, String id, int priority, Level level) {
        this.modId = modId;
        this.id = id;
        this.priority = priority;
        this.level = level;
        this.pending = level.getRecipeManager().getRecipes().iterator();
    }

    @Override public String adapterId() { return id; }
    @Override public int priority() { return priority; }
    @Override public List<DependencyPlanner.Choice<AEKey, PatternRecipe>> find(AEKey output) {
        return List.of();
    }

    @Override public boolean indexStep(int budget) {
        for (int i = 0; i < Math.max(1, budget) && pending.hasNext(); i++) {
            RecipeHolder<?> holder = pending.next();
            try {
                if (!RecipeReflection.recipeType(holder).getNamespace().equals(modId)) continue;
                ItemStack output = RecipeReflection.resultItem(holder, level);
                if (!output.isEmpty()) unverified.add(AEItemKey.of(output));
            } catch (RuntimeException ignored) {
                // Unsupported optional recipes are diagnostics-only.
            }
        }
        indexed = !pending.hasNext();
        return indexed;
    }

    @Override public boolean unsupported(AEKey output) { return indexed && unverified.contains(output); }

    @Override public net.minecraft.world.item.ItemStack encode(PatternRecipe recipe, List<AEKey> inputs,
            AEKey expected, Level level) {
        throw new IllegalStateException("Unverified machine recipes cannot be encoded");
    }
}
