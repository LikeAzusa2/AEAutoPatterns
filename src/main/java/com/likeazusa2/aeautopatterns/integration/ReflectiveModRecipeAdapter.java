package com.likeazusa2.aeautopatterns.integration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import com.likeazusa2.aeautopatterns.logic.DependencyPlanner;

/**
 * Incremental adapter for a closed allow-list of exact upstream recipe classes.
 * It never guesses a mod recipe from package names or unrelated item getters.
 */
public class ReflectiveModRecipeAdapter implements RecipeAdapter {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("aeautopatterns.analysis.explicit-machines");

    private final String id;
    private final int priority;
    private final Level level;
    private final List<ReflectiveRecipeFamily> families;
    private final Set<String> recipeNamespaces;
    private final Iterator<RecipeHolder<?>> pending;
    private final List<AdaptedRecipe> discovered = new ArrayList<>();
    private final Set<ResourceLocation> discoveredIds = new HashSet<>();
    private final Set<AEKey> unverified = new HashSet<>();
    private GenericProcessingAdapter delegate;

    public ReflectiveModRecipeAdapter(String id, int priority, Level level,
            List<ReflectiveRecipeFamily> families) {
        this.id = Objects.requireNonNull(id);
        this.priority = priority;
        this.level = Objects.requireNonNull(level);
        this.families = List.copyOf(families);
        this.recipeNamespaces = this.families.stream()
                .flatMap(family -> family.recipeTypes().stream())
                .map(ResourceLocation::getNamespace).collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.pending = level.getRecipeManager().getRecipes().iterator();
        if (this.families.isEmpty()) throw new IllegalArgumentException("no explicit recipe families");
    }

    @Override public String adapterId() { return id; }
    @Override public int priority() { return priority; }

    @Override public Set<AdapterCapability> capabilities() {
        return delegate == null ? Set.of(AdapterCapability.ITEMS, AdapterCapability.VALIDATION)
                : delegate.capabilities();
    }

    @Override public boolean indexStep(int budget) {
        for (int i = 0; i < Math.max(1, budget) && pending.hasNext(); i++) {
            RecipeHolder<?> holder = pending.next();
            try {
                ResourceLocation type = RecipeReflection.recipeType(holder);
                ReflectiveRecipeFamily family = familyFor(holder, type);
                if (family == null) {
                    if (recipeNamespaces.contains(type.getNamespace())) markUnverified(holder);
                    continue;
                }
                var draft = family.parser().parse(level, holder);
                var snapshot = new ParsedRecipeSnapshot(holder, family);
                acceptRecipeCandidate(discovered, discoveredIds, new AdaptedRecipe(id, holder.id(), RecipeKind.MACHINE,
                        draft.inputs(), draft.outputs(), draft.probabilisticOutputs(),
                        draft.requirements(), snapshot));
            } catch (ReflectiveRecipeFamily.RecipeRejectedException | RuntimeException | LinkageError e) {
                markUnverified(holder);
                LOG.debug("Rejected optional recipe {}: {}", holder.id(), e.toString());
            }
        }
        if (!pending.hasNext() && delegate == null)
            delegate = new GenericProcessingAdapter(id, priority, discovered);
        return delegate != null;
    }

    @Override public List<DependencyPlanner.Choice<AEKey, PatternRecipe>> find(AEKey output) {
        return delegate == null ? List.of() : delegate.find(output);
    }

    @Override public boolean hasRecipe(AEKey output) {
        return delegate != null && delegate.hasRecipe(output);
    }

    @Override public boolean unsupported(AEKey output) { return unverified.contains(output); }

    @Override public List<PatternRecipe> processingRecipes() {
        return delegate == null ? List.of() : delegate.processingRecipes();
    }

    @Override public ItemStack encode(PatternRecipe recipe, List<AEKey> inputs, AEKey expected, Level level) {
        if (delegate == null) throw new IllegalStateException("Recipe index is incomplete");
        return delegate.encode(recipe, inputs, expected, level);
    }

    private ReflectiveRecipeFamily familyFor(RecipeHolder<?> holder, ResourceLocation type) {
        String className = holder.value().getClass().getName();
        for (var family : families)
            if (family.recipeTypes().contains(type) && family.exactClasses().contains(className))
                return family;
        return null;
    }

    static void acceptRecipeCandidate(List<AdaptedRecipe> accepted, Set<ResourceLocation> acceptedIds,
            AdaptedRecipe candidate) throws ReflectiveRecipeFamily.RecipeRejectedException {
        AdaptedRecipe normalized;
        try {
            normalized = GenericProcessingAdapter.normalizeForEncoding(candidate);
        } catch (RuntimeException e) {
            throw new ReflectiveRecipeFamily.RecipeRejectedException(
                    "Recipe cannot fit an AE processing pattern: " + candidate.recipeId(), e);
        }
        if (!acceptedIds.add(normalized.recipeId()))
            throw new ReflectiveRecipeFamily.RecipeRejectedException(
                    "Duplicate adapted recipe id: " + normalized.recipeId());
        accepted.add(normalized);
    }

    static void verifyRecipeData(String adapterId, ResourceLocation recipeId,
            AdaptedRecipe expected, AdaptedRecipe reparsed) {
        if (!adapterId.equals(expected.adapterId()) || !recipeId.equals(expected.recipeId())
                || expected.kind() != RecipeKind.MACHINE)
            throw new IllegalStateException("Recipe payload identity changed: " + recipeId);
        if (!adapterId.equals(reparsed.adapterId()) || !recipeId.equals(reparsed.recipeId())
                || reparsed.kind() != RecipeKind.MACHINE)
            throw new IllegalStateException("Authoritative recipe identity changed: " + recipeId);
        if (!expected.inputs().equals(reparsed.inputs())
                || !expected.outputs().equals(reparsed.outputs())
                || !expected.probabilisticOutputs().equals(reparsed.probabilisticOutputs())
                || !expected.requirements().machineType().equals(reparsed.requirements().machineType())
                || !expected.requirements().conditions().equals(reparsed.requirements().conditions()))
            throw new IllegalStateException("Authoritative recipe data changed: " + recipeId);
    }

    private void markUnverified(RecipeHolder<?> holder) {
        try {
            ItemStack output = RecipeReflection.resultItem(holder, level);
            if (!output.isEmpty()) unverified.add(AEItemKey.of(output));
        } catch (RuntimeException | LinkageError ignored) {
            // A broken optional recipe must not break indexing of other families.
        }
    }

    private final class ParsedRecipeSnapshot implements RecipeSnapshot {
        private final RecipeHolder<?> holder;
        private final ReflectiveRecipeFamily family;

        private ParsedRecipeSnapshot(RecipeHolder<?> holder, ReflectiveRecipeFamily family) {
            this.holder = holder;
            this.family = family;
        }

        @Override public RecipeHolder<?> holder() { return holder; }

        @Override public void verify(Level currentLevel, AdaptedRecipe expected) {
            RecipeHolder<?> current;
            try {
                current = currentLevel.getRecipeManager().byKey(holder.id()).orElse(null);
            } catch (RuntimeException | LinkageError e) {
                throw new IllegalStateException("Authoritative recipe lookup failed: " + holder.id(), e);
            }
            if (current == null || current.value().getClass() != holder.value().getClass()
                    || !isCurrentFamily(current))
                throw new IllegalStateException("Authoritative recipe changed: " + holder.id());
            RecipeDraft fresh;
            try {
                fresh = family.parser().parse(currentLevel, current);
            } catch (ReflectiveRecipeFamily.RecipeRejectedException e) {
                throw new IllegalStateException("Authoritative recipe is no longer supported: " + holder.id(), e);
            } catch (RuntimeException | LinkageError e) {
                throw new IllegalStateException("Authoritative recipe could not be revalidated: " + holder.id(), e);
            }
            AdaptedRecipe reparsed;
            try {
                reparsed = GenericProcessingAdapter.normalizeForEncoding(
                        new AdaptedRecipe(id, holder.id(), RecipeKind.MACHINE,
                                fresh.inputs(), fresh.outputs(), fresh.probabilisticOutputs(),
                                fresh.requirements(), this));
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "Authoritative recipe is no longer encodable: " + holder.id(), e);
            }
            verifyRecipeData(id, holder.id(), expected, reparsed);
        }

        private boolean isCurrentFamily(RecipeHolder<?> current) {
            try {
                return family.recipeTypes().contains(RecipeReflection.recipeType(current));
            } catch (RuntimeException | LinkageError e) {
                throw new IllegalStateException("Authoritative recipe type changed: " + holder.id(), e);
            }
        }
    }
}
