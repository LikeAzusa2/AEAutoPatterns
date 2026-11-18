package com.likeazusa2.aeautopatterns.integration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import com.likeazusa2.aeautopatterns.logic.DependencyPlanner;

/**
 * Shared AE processing encoder for integrations that already parsed an authoritative recipe.
 * Only deterministic outputs are encoded. Probabilities and machine conditions are diagnostics.
 */
public final class GenericProcessingAdapter implements RecipeAdapter {
    private static final int MAX_AE_INPUTS = 9;
    private static final int MAX_AE_OUTPUTS = 9;

    private final String id;
    private final int priority;
    private final List<AdaptedRecipe> recipes;
    private final List<PatternRecipe> catalog;
    private final Map<AEKey, List<AdaptedRecipe>> byOutput = new LinkedHashMap<>();

    public GenericProcessingAdapter(String id, int priority, Collection<AdaptedRecipe> recipes) {
        this.id = Objects.requireNonNull(id);
        this.priority = priority;
        var normalized = new ArrayList<AdaptedRecipe>();
        var recipeIds = new HashSet<String>();
        for (var recipe : recipes) {
            var entry = normalizeForEncoding(Objects.requireNonNull(recipe, "recipe"));
            if (!recipeIds.add(entry.adapterId() + "|" + entry.recipeId()))
                throw new IllegalArgumentException("duplicate adapted recipe: " + entry.recipeId());
            normalized.add(entry);
        }
        normalized.sort(Comparator.comparing(r -> r.recipeId().toString()));
        this.recipes = List.copyOf(normalized);
        this.catalog = this.recipes.stream()
                .map(recipe -> new PatternRecipe(holderOf(recipe), ItemStack.EMPTY, this, recipe))
                .toList();
        for (var recipe : this.recipes) for (var output : recipe.outputs())
            byOutput.computeIfAbsent(output.key(), ignored -> new ArrayList<>()).add(recipe);
        byOutput.values().forEach(list -> list.sort(Comparator.comparing(r -> r.recipeId().toString())));
    }

    @Override public String adapterId() { return id; }
    @Override public int priority() { return priority; }

    @Override public Set<AdapterCapability> capabilities() {
        var result = EnumSet.of(AdapterCapability.VALIDATION, AdapterCapability.AE_PROCESSING_ENCODING);
        if (recipes.stream().anyMatch(r -> r.inputs().stream().anyMatch(i -> !i.alternatives().isEmpty())))
            result.add(AdapterCapability.ALTERNATIVES);
        if (recipes.stream().anyMatch(r -> r.inputs().size() > 1)) result.add(AdapterCapability.MULTI_INPUT);
        if (recipes.stream().anyMatch(r -> r.outputs().size() > 1)) result.add(AdapterCapability.MULTI_OUTPUT);
        if (recipes.stream().anyMatch(r -> !r.probabilisticOutputs().isEmpty()))
            result.add(AdapterCapability.PROBABILISTIC_OUTPUTS);
        if (recipes.stream().anyMatch(r -> !r.requirements().conditions().isEmpty()))
            result.add(AdapterCapability.MACHINE_CONDITIONS);
        if (recipes.stream().flatMap(r -> r.inputs().stream()).anyMatch(i -> i.role() == StackRole.CATALYST))
            result.add(AdapterCapability.CATALYSTS);
        if (recipes.stream().flatMap(r -> r.inputs().stream()).anyMatch(i -> i.role() == StackRole.CONTAINER))
            result.add(AdapterCapability.CONTAINERS);
        if (recipes.stream().flatMap(r -> r.inputs().stream()).anyMatch(i -> i.role() == StackRole.RETURNED))
            result.add(AdapterCapability.RETURNED_ITEMS);
        if (recipes.stream().flatMap(r -> r.inputs().stream()).anyMatch(i -> i.key() instanceof AEItemKey)
                || recipes.stream().flatMap(r -> r.outputs().stream()).anyMatch(o -> o.key() instanceof AEItemKey))
            result.add(AdapterCapability.ITEMS);
        if (recipes.stream().flatMap(r -> r.inputs().stream()).anyMatch(i -> i.key() instanceof AEFluidKey)
                || recipes.stream().flatMap(r -> r.outputs().stream()).anyMatch(o -> o.key() instanceof AEFluidKey))
            result.add(AdapterCapability.FLUIDS);
        return Set.copyOf(result);
    }

    @Override public List<DependencyPlanner.Choice<AEKey, PatternRecipe>> find(AEKey output) {
        var result = new ArrayList<DependencyPlanner.Choice<AEKey, PatternRecipe>>();
        for (var recipe : byOutput.getOrDefault(output, List.of())) {
            var groups = recipe.inputs().stream().map(input -> {
                var values = new ArrayList<AEKey>();
                values.add(input.key());
                values.addAll(input.alternatives());
                return List.copyOf(values.stream().distinct().toList());
            }).toList();
            result.add(new DependencyPlanner.Choice<>(recipe.recipeId().toString(),
                    DependencyPlanner.Kind.GENERATE, groups,
                    new PatternRecipe(holderOf(recipe), ItemStack.EMPTY, this, recipe)));
        }
        return result;
    }

    @Override public boolean hasRecipe(AEKey output) { return byOutput.containsKey(output); }

    @Override public List<PatternRecipe> processingRecipes() { return catalog; }

    @Override public ItemStack encode(PatternRecipe payload, List<AEKey> inputs, AEKey expected, Level level) {
        var recipe = Objects.requireNonNull(payload.adapted(), "missing adapted recipe");
        verifySource(recipe, level);
        if (inputs.size() != recipe.inputs().size())
            throw new IllegalStateException("Invalid adapted input count");

        var encodedInputs = new ArrayList<GenericStack>();
        var expectedInputs = new LinkedHashMap<AEKey, Long>();
        for (int i = 0; i < inputs.size(); i++) {
            var spec = recipe.inputs().get(i);
            AEKey selected = inputs.get(i);
            if (!selected.equals(spec.key()) && !spec.alternatives().contains(selected))
                throw new IllegalStateException("Adapted input no longer matches");
            encodedInputs.add(new GenericStack(selected, spec.amount()));
            expectedInputs.merge(selected, spec.amount(), GenericProcessingAdapter::addExact);
        }

        var orderedOutputs = new ArrayList<StackSpec>();
        for (var output : recipe.outputs()) if (output.key().equals(expected)) orderedOutputs.add(output);
        if (orderedOutputs.isEmpty()) throw new IllegalStateException("Adapted output changed");
        for (var output : recipe.outputs()) if (!output.key().equals(expected)) orderedOutputs.add(output);
        var encodedOutputs = orderedOutputs.stream().map(s -> new GenericStack(s.key(), s.amount())).toList();

        var encoded = PatternDetailsHelper.encodeProcessingPattern(encodedInputs, encodedOutputs);
        var decoded = PatternDetailsHelper.decodePattern(encoded, level);
        if (decoded == null) throw new IllegalStateException("AE processing pattern validation failed");
        validateDecoded(decoded, expectedInputs, orderedOutputs, expected);

        var lore = new ArrayList<Component>();
        lore.add(Component.literal(recipe.recipeId().toString())
                .withStyle(ChatFormatting.AQUA).withStyle(style -> style.withItalic(false)));
        lore.add(Component.literal("Adapter: " + recipe.adapterId()).withStyle(ChatFormatting.GRAY));
        lore.add(Component.literal("Machine: " + recipe.requirements().translationKey())
                .withStyle(ChatFormatting.GRAY));
        if (!recipe.requirements().conditions().isEmpty())
            lore.add(Component.literal("Conditions: " + String.join(", ", recipe.requirements().conditions()))
                    .withStyle(ChatFormatting.YELLOW));
        for (var chance : recipe.probabilisticOutputs())
            lore.add(Component.literal("Chance (not encoded): "
                    + chance.key().getDisplayName().getString() + " x" + chance.amount()
                    + " (" + (chance.chance() * 100f) + "%)").withStyle(ChatFormatting.GOLD));
        encoded.set(DataComponents.LORE, new ItemLore(lore));
        return encoded;
    }

    @Override public String describe(PatternRecipe recipe) {
        var adapted = recipe.adapted();
        return adapted == null ? "" : adapted.adapterId() + ":" + adapted.recipeId();
    }

    static AdaptedRecipe normalizeForEncoding(AdaptedRecipe recipe) {
        if (recipe.inputs().isEmpty())
            throw new IllegalArgumentException("adapted recipe has no input: " + recipe.recipeId());
        var inputs = new ArrayList<StackSpec>();
        for (var input : recipe.inputs()) {
            Objects.requireNonNull(input.key(), "input key");
            if (input.amount() <= 0) throw new IllegalArgumentException("input amount must be positive");
            if (input.alternatives().contains(input.key())
                    || new HashSet<>(input.alternatives()).size() != input.alternatives().size())
                throw new IllegalArgumentException("invalid input alternatives: " + recipe.recipeId());
            int existing = findKey(inputs, input.key());
            if (existing < 0) {
                inputs.add(input);
            } else {
                var previous = inputs.get(existing);
                if (previous.role() != input.role() || !previous.alternatives().equals(input.alternatives()))
                    throw new IllegalArgumentException("ambiguous repeated input: " + recipe.recipeId());
                inputs.set(existing, new StackSpec(previous.key(),
                        addExact(previous.amount(), input.amount()), previous.role(), previous.alternatives()));
            }
        }
        if (inputs.size() > MAX_AE_INPUTS)
            throw new IllegalArgumentException("too many inputs for AE processing pattern: " + recipe.recipeId());

        var outputs = new ArrayList<StackSpec>();
        for (var output : recipe.outputs()) {
            Objects.requireNonNull(output.key(), "output key");
            if (output.amount() <= 0) throw new IllegalArgumentException("output amount must be positive");
            int existing = findKey(outputs, output.key());
            if (existing < 0) {
                outputs.add(output);
            } else {
                var previous = outputs.get(existing);
                outputs.set(existing, new StackSpec(previous.key(),
                        addExact(previous.amount(), output.amount()), previous.role(), previous.alternatives()));
            }
        }
        if (outputs.isEmpty()) throw new IllegalArgumentException("adapted recipe needs a deterministic output");
        if (outputs.size() > MAX_AE_OUTPUTS)
            throw new IllegalArgumentException("too many outputs for AE processing pattern: " + recipe.recipeId());
        return new AdaptedRecipe(recipe.adapterId(), recipe.recipeId(), recipe.kind(),
                inputs, outputs, recipe.probabilisticOutputs(), recipe.requirements(), recipe.source());
    }

    private static int findKey(List<StackSpec> specs, AEKey key) {
        for (int i = 0; i < specs.size(); i++) if (specs.get(i).key().equals(key)) return i;
        return -1;
    }

    private static long addExact(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("stack amount overflow", e);
        }
    }

    private static net.minecraft.world.item.crafting.RecipeHolder<?> holderOf(AdaptedRecipe recipe) {
        return recipe.source() instanceof RecipeSnapshot snapshot ? snapshot.holder() : null;
    }

    private static void verifySource(AdaptedRecipe recipe, Level level) {
        if (recipe.source() instanceof RecipeSnapshot snapshot) snapshot.verify(level, recipe);
    }

    private static void validateDecoded(IPatternDetails decoded, Map<AEKey, Long> expectedInputs,
            List<StackSpec> expectedOutputs, AEKey expected) {
        if (!decoded.getPrimaryOutput().what().equals(expected))
            throw new IllegalStateException("AE processing primary output changed");

        var actualInputs = new HashMap<AEKey, Long>();
        for (var input : decoded.getInputs()) {
            var possible = input.getPossibleInputs();
            if (possible.length != 1 || possible[0].amount() != 1 || input.getMultiplier() <= 0)
                throw new IllegalStateException("AE processing inputs are not exact");
            actualInputs.merge(possible[0].what(), input.getMultiplier(), GenericProcessingAdapter::addExact);
        }
        if (!actualInputs.equals(expectedInputs))
            throw new IllegalStateException("AE processing inputs changed");

        var expectedOutputAmounts = new HashMap<AEKey, Long>();
        for (var output : expectedOutputs)
            expectedOutputAmounts.merge(output.key(), output.amount(), GenericProcessingAdapter::addExact);
        var actualOutputAmounts = new HashMap<AEKey, Long>();
        for (var output : decoded.getOutputs())
            actualOutputAmounts.merge(output.what(), output.amount(), GenericProcessingAdapter::addExact);
        if (!actualOutputAmounts.equals(expectedOutputAmounts))
            throw new IllegalStateException("AE processing outputs changed");
    }
}
