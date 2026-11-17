package com.likeazusa2.aeautopatterns.integration;

import java.util.Objects;
import appeng.api.stacks.AEKey;

/** Probabilistic output; deliberately excluded from deterministic AE outputs. */
public record ChanceOutput(AEKey key, long amount, float chance) {
    public ChanceOutput {
        Objects.requireNonNull(key, "key");
        if (amount <= 0 || Float.isNaN(chance) || chance < 0 || chance > 1)
            throw new IllegalArgumentException("invalid probabilistic output");
    }
}
