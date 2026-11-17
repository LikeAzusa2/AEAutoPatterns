package com.likeazusa2.aeautopatterns.integration;

import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Human-readable machine constraints; AE does not enforce these conditions. */
public record MachineRequirements(ResourceLocation machineType, List<String> conditions) {
    public MachineRequirements {
        Objects.requireNonNull(machineType, "machineType");
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }
    public static MachineRequirements none(ResourceLocation machineType) { return new MachineRequirements(machineType, List.of()); }
}
