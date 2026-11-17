package com.likeazusa2.aeautopatterns.integration;

import java.util.List;
import java.util.Objects;
import appeng.api.stacks.AEKey;

/** A precisely sized item or fluid stack used by an adapted recipe. */
public record StackSpec(AEKey key, long amount, StackRole role, List<AEKey> alternatives) {
    public StackSpec {
        Objects.requireNonNull(key, "key");
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        role = Objects.requireNonNull(role, "role");
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
    }
    public StackSpec(AEKey key, long amount, StackRole role) { this(key, amount, role, List.of()); }
    public boolean isConsumed() { return role == StackRole.CONSUMED; }
}
