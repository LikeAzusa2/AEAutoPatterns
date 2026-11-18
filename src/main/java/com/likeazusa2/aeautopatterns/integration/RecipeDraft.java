package com.likeazusa2.aeautopatterns.integration;

import java.util.List;
import java.util.Objects;

/** Parsed deterministic recipe data before it is bound to an authoritative recipe holder. */
public record RecipeDraft(List<StackSpec> inputs, List<StackSpec> outputs,
        List<ChanceOutput> probabilisticOutputs, MachineRequirements requirements) {
    public RecipeDraft {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        probabilisticOutputs = probabilisticOutputs == null ? List.of() : List.copyOf(probabilisticOutputs);
        requirements = Objects.requireNonNull(requirements, "requirements");
    }
}
