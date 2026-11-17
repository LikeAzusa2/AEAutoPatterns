package com.likeazusa2.aeautopatterns.integration;

import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Data-pack declaration for a recipe family; parsing remains conservative and explicit. */
public record DatapackAdapterSpec(String adapterId, ResourceLocation machineType, int priority,
        List<ResourceLocation> recipeTypes, List<String> inputPaths, List<String> outputPaths,
        List<String> fluidPaths, List<String> conditionPaths) {
    public DatapackAdapterSpec {
        Objects.requireNonNull(adapterId, "adapterId");
        Objects.requireNonNull(machineType, "machineType");
        recipeTypes = recipeTypes == null ? List.of() : List.copyOf(recipeTypes);
        inputPaths = inputPaths == null ? List.of() : List.copyOf(inputPaths);
        outputPaths = outputPaths == null ? List.of() : List.copyOf(outputPaths);
        fluidPaths = fluidPaths == null ? List.of() : List.copyOf(fluidPaths);
        conditionPaths = conditionPaths == null ? List.of() : List.copyOf(conditionPaths);
    }
}
