package com.likeazusa2.aeautopatterns.integration;

import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Complete, server-authoritative description used by generic machine adapters. */
public record AdaptedRecipe(String adapterId, ResourceLocation recipeId, RecipeKind kind,
        List<StackSpec> inputs, List<StackSpec> outputs, List<ChanceOutput> probabilisticOutputs,
        MachineRequirements requirements, Object source) {
    public AdaptedRecipe {
        Objects.requireNonNull(adapterId, "adapterId");
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(kind, "kind");
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        probabilisticOutputs = probabilisticOutputs == null ? List.of() : List.copyOf(probabilisticOutputs);
        requirements = requirements == null ? MachineRequirements.none(recipeId) : requirements;
        if (outputs.isEmpty()) throw new IllegalArgumentException("adapted recipe needs an output");
    }
    public boolean hasWarnings() { return !probabilisticOutputs.isEmpty() || !requirements.conditions().isEmpty(); }
}
