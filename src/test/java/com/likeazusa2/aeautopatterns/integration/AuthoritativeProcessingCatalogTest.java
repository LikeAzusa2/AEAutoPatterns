package com.likeazusa2.aeautopatterns.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import com.likeazusa2.aeautopatterns.logic.DependencyPlanner;

class AuthoritativeProcessingCatalogTest {
    private static final AEKeyType TYPE = new AEKeyType(
            ResourceLocation.parse("aeautopatterns:test"), TestKey.class,
            Component.literal("Test")) {
        @Override public MapCodec<? extends AEKey> codec() { return null; }
        @Override public AEKey readFromPacket(RegistryFriendlyByteBuf input) { return null; }
    };

    @Test void catalogPreservesExactAmountsAndDeterministicCoproducts() {
        var input = key("input");
        var secondaryInput = key("secondary_input");
        var dust = key("dust");
        var slag = key("slag");
        var bonus = key("bonus");
        var adapted = new AdaptedRecipe("test:machine", ResourceLocation.parse("test:mixed"),
                RecipeKind.MACHINE,
                List.of(new StackSpec(input, 2, StackRole.CONSUMED),
                        new StackSpec(secondaryInput, 3, StackRole.CONSUMED)),
                List.of(new StackSpec(dust, 1, StackRole.RETURNED),
                        new StackSpec(dust, 2, StackRole.RETURNED),
                        new StackSpec(slag, 4, StackRole.RETURNED)),
                List.of(new ChanceOutput(bonus, 1, 0.25f)),
                new MachineRequirements(ResourceLocation.parse("test:machine"), List.of("test:heated")),
                null);

        var catalog = GenericProcessingAdapter.normalizeForEncoding(adapted);
        assertEquals(2, catalog.inputs().size());
        assertEquals(2, catalog.inputs().getFirst().amount());
        assertEquals(3, catalog.inputs().get(1).amount());
        assertEquals(3, catalog.outputs().getFirst().amount());
        assertEquals(4, catalog.outputs().get(1).amount());
        assertEquals(1, catalog.probabilisticOutputs().size());
        assertEquals("gui.aeautopatterns.routes.machine.test.machine",
                catalog.requirements().translationKey());
    }

    @Test void compositeWaitsForIndexingAndDeduplicatesRecipeIdsWithoutCatalogBootstrap() {
        var first = new TestRecipeAdapter("test:first", 10,
                List.of(simpleRecipe("test:first", "test:shared")));
        var second = new TestRecipeAdapter("test:second", 5,
                List.of(simpleRecipe("test:second", "test:shared")));
        var composite = new CompositeRecipeAdapter(List.of(first, second));
        assertTrue(composite.processingRecipes().isEmpty());
        while (!composite.indexStep(10)) {
            // Boundary indexing is intentionally incremental.
        }
        assertEquals(1, composite.processingRecipes().size());
    }

    @Test void rejectsAmbiguousRepeatedInputsInsteadOfCombiningDifferentAlternatives() {
        var input = key("input");
        var adapted = new AdaptedRecipe("test:machine", ResourceLocation.parse("test:ambiguous"),
                RecipeKind.MACHINE,
                List.of(new StackSpec(input, 1, StackRole.CONSUMED),
                        new StackSpec(input, 1, StackRole.CONSUMED, List.of(key("alternative")))),
                List.of(new StackSpec(key("output"), 1, StackRole.RETURNED)),
                List.of(), MachineRequirements.none(ResourceLocation.parse("test:machine")), null);

        assertThrows(IllegalArgumentException.class,
                () -> new GenericProcessingAdapter("test:machine", 0, List.of(adapted)));
    }

    @Test void rejectsCatalogsThatCannotFitAnAeProcessingPattern() {
        var inputs = IntStream.range(0, 10)
                .mapToObj(i -> new StackSpec(key("input_" + i), 1, StackRole.CONSUMED))
                .toList();
        var adapted = new AdaptedRecipe("test:machine", ResourceLocation.parse("test:oversized"),
                RecipeKind.MACHINE, inputs,
                List.of(new StackSpec(key("output"), 1, StackRole.RETURNED)),
                List.of(), MachineRequirements.none(ResourceLocation.parse("test:machine")), null);

        assertThrows(IllegalArgumentException.class,
                () -> new GenericProcessingAdapter("test:machine", 0, List.of(adapted)));
    }

    @Test void perRecipeNormalizationRejectsOnlyTheInvalidCandidate() throws Exception {
        var accepted = new ArrayList<AdaptedRecipe>();
        Set<ResourceLocation> acceptedIds = new HashSet<>();
        var first = simpleRecipe("test:machine", "test:first");
        ReflectiveModRecipeAdapter.acceptRecipeCandidate(accepted, acceptedIds, first);

        var invalid = new AdaptedRecipe("test:machine", ResourceLocation.parse("test:invalid"),
                RecipeKind.MACHINE,
                IntStream.range(0, 10)
                        .mapToObj(i -> new StackSpec(key("oversized_" + i), 1, StackRole.CONSUMED))
                        .toList(),
                List.of(new StackSpec(key("invalid_output"), 1, StackRole.RETURNED)),
                List.of(), MachineRequirements.none(ResourceLocation.parse("test:machine")), null);
        assertThrows(ReflectiveRecipeFamily.RecipeRejectedException.class,
                () -> ReflectiveModRecipeAdapter.acceptRecipeCandidate(accepted, acceptedIds, invalid));

        var second = simpleRecipe("test:machine", "test:second");
        ReflectiveModRecipeAdapter.acceptRecipeCandidate(accepted, acceptedIds, second);
        assertEquals(List.of(first.recipeId(), second.recipeId()),
                accepted.stream().map(AdaptedRecipe::recipeId).toList());
    }

    @Test void authoritativeRevalidationUsesTheSameNormalizedShape() {
        var input = key("input");
        var raw = new AdaptedRecipe("test:machine", ResourceLocation.parse("test:authoritative"),
                RecipeKind.MACHINE,
                List.of(new StackSpec(input, 1, StackRole.CONSUMED),
                        new StackSpec(input, 2, StackRole.CONSUMED)),
                List.of(new StackSpec(key("output"), 1, StackRole.RETURNED)),
                List.of(), MachineRequirements.none(ResourceLocation.parse("test:machine")), null);
        var normalized = GenericProcessingAdapter.normalizeForEncoding(raw);
        assertEquals(1, normalized.inputs().size());
        assertEquals(3, normalized.inputs().getFirst().amount());
        assertDoesNotThrow(() -> ReflectiveModRecipeAdapter.verifyRecipeData(
                "test:machine", normalized.recipeId(), normalized, normalized));

        var changed = new AdaptedRecipe(normalized.adapterId(), normalized.recipeId(), normalized.kind(),
                List.of(new StackSpec(input, 4, StackRole.CONSUMED)), normalized.outputs(),
                normalized.probabilisticOutputs(), normalized.requirements(), normalized.source());
        assertThrows(IllegalStateException.class, () -> ReflectiveModRecipeAdapter.verifyRecipeData(
                "test:machine", normalized.recipeId(), normalized, changed));
    }

    private static AdaptedRecipe simpleRecipe(String adapterId, String recipeId) {
        return new AdaptedRecipe(adapterId, ResourceLocation.parse(recipeId), RecipeKind.MACHINE,
                List.of(new StackSpec(key("input"), 1, StackRole.CONSUMED)),
                List.of(new StackSpec(key("output"), 1, StackRole.RETURNED)),
                List.of(), MachineRequirements.none(ResourceLocation.parse("test:machine")), null);
    }

    private static TestKey key(String id) {
        return new TestKey(id);
    }

    private record TestRecipeAdapter(String adapterId, int priority, List<AdaptedRecipe> recipes)
            implements RecipeAdapter {
        @Override public List<DependencyPlanner.Choice<AEKey, PatternRecipe>> find(AEKey output) {
            return List.of();
        }

        @Override public ItemStack encode(PatternRecipe recipe, List<AEKey> inputs,
                AEKey expected, Level level) {
            throw new UnsupportedOperationException("catalog-only test adapter");
        }

        @Override public List<PatternRecipe> processingRecipes() {
            return recipes.stream()
                    .map(recipe -> new PatternRecipe(null, null, this, recipe))
                    .toList();
        }
    }

    private static final class TestKey extends AEKey {
        private final String id;

        private TestKey(String id) { this.id = id; }

        @Override public AEKeyType getType() { return TYPE; }
        @Override public AEKey dropSecondary() { return this; }
        @Override public CompoundTag toTag(HolderLookup.Provider registries) { return new CompoundTag(); }
        @Override public Object getPrimaryKey() { return id; }
        @Override public ResourceLocation getId() { return ResourceLocation.parse("test:" + id); }
        @Override public void writeToPacket(RegistryFriendlyByteBuf data) {}
        @Override protected Component computeDisplayName() { return Component.literal(id); }
        @Override public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {}
        @Override public boolean hasComponents() { return false; }
        @Override public boolean equals(Object other) {
            return other instanceof TestKey key && id.equals(key.id);
        }
        @Override public int hashCode() { return Objects.hash(id); }
    }
}
