package com.likeazusa2.aeautopatterns.integration;

import java.util.*;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.*;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import com.likeazusa2.aeautopatterns.logic.DependencyPlanner;

/**
 * Generic AE processing encoder for integrations that already parsed their machine recipe.
 * It intentionally does not execute machine conditions or probabilistic outputs.
 */
public final class GenericProcessingAdapter implements RecipeAdapter {
    private final String id;
    private final int priority;
    private final Map<AEKey, List<AdaptedRecipe>> byOutput = new HashMap<>();

    public GenericProcessingAdapter(String id, int priority, Collection<AdaptedRecipe> recipes) {
        this.id = Objects.requireNonNull(id);
        this.priority = priority;
        for (var recipe : recipes) for (var output : recipe.outputs())
            byOutput.computeIfAbsent(output.key(), ignored -> new ArrayList<>()).add(recipe);
        byOutput.values().forEach(list -> list.sort(Comparator.comparing(r -> r.recipeId().toString())));
    }

    @Override public String adapterId() { return id; }
    @Override public int priority() { return priority; }
    @Override public Set<AdapterCapability> capabilities() {
        var result=EnumSet.of(AdapterCapability.MULTI_INPUT,AdapterCapability.MULTI_OUTPUT,
            AdapterCapability.VALIDATION,AdapterCapability.AE_PROCESSING_ENCODING,
            AdapterCapability.PROBABILISTIC_OUTPUTS,AdapterCapability.MACHINE_CONDITIONS,
            AdapterCapability.CATALYSTS,AdapterCapability.CONTAINERS,AdapterCapability.RETURNED_ITEMS,
            AdapterCapability.ALTERNATIVES);
        if(byOutput.keySet().stream().anyMatch(AEFluidKey::is)) result.add(AdapterCapability.FLUIDS);
        if(byOutput.keySet().stream().anyMatch(k -> k instanceof AEItemKey)) result.add(AdapterCapability.ITEMS);
        return Set.copyOf(result);
    }
    @Override public List<DependencyPlanner.Choice<AEKey, PatternRecipe>> find(AEKey output) {
        var result = new ArrayList<DependencyPlanner.Choice<AEKey, PatternRecipe>>();
        for (var recipe : byOutput.getOrDefault(output, List.of())) {
            var primary = recipe.outputs().getFirst();
            if (!primary.key().equals(output)) continue;
            var groups = recipe.inputs().stream().map(input -> {
                var values = new ArrayList<AEKey>(); values.add(input.key()); values.addAll(input.alternatives());
                return values.stream().distinct().toList();
            }).toList();
            result.add(new DependencyPlanner.Choice<>(recipe.recipeId().toString(), DependencyPlanner.Kind.GENERATE,
                groups, new PatternRecipe(null, ItemStack.EMPTY, this, recipe)));
        }
        return result;
    }

    @Override public ItemStack encode(PatternRecipe payload, List<AEKey> inputs, AEKey expected, Level level) {
        var recipe = Objects.requireNonNull(payload.adapted(), "missing adapted recipe");
        if (inputs.size() != recipe.inputs().size()) throw new IllegalStateException("Invalid adapted input count");
        var encodedInputs = new ArrayList<GenericStack>();
        for (int i = 0; i < inputs.size(); i++) {
            var spec = recipe.inputs().get(i);
            if (!inputs.get(i).equals(spec.key()) && !spec.alternatives().contains(inputs.get(i)))
                throw new IllegalStateException("Adapted input no longer matches");
            encodedInputs.add(new GenericStack(inputs.get(i), spec.amount()));
        }
        var outputs = recipe.outputs().stream().map(s -> new GenericStack(s.key(), s.amount())).toList();
        if (outputs.stream().noneMatch(s -> s.what().equals(expected))) throw new IllegalStateException("Adapted output changed");
        var encoded = PatternDetailsHelper.encodeProcessingPattern(encodedInputs, outputs);
        var decoded = PatternDetailsHelper.decodePattern(encoded, level);
        if (decoded == null || !decoded.getPrimaryOutput().what().equals(expected)
                || decoded.getOutputs().size() != outputs.size()) throw new IllegalStateException("AE processing pattern validation failed");
        var lore = new ArrayList<Component>();
        lore.add(Component.literal(recipe.recipeId().toString()).withStyle(ChatFormatting.AQUA));
        lore.add(Component.literal("Adapter: " + recipe.adapterId()).withStyle(ChatFormatting.GRAY));
        if (!recipe.requirements().conditions().isEmpty()) lore.add(Component.literal("Conditions: " + String.join(", ", recipe.requirements().conditions())).withStyle(ChatFormatting.YELLOW));
        for (var chance : recipe.probabilisticOutputs()) lore.add(Component.literal("Chance: " + chance.key().getDisplayName().getString() + " x" + chance.amount() + " (" + (chance.chance() * 100f) + "%)").withStyle(ChatFormatting.GOLD));
        encoded.set(DataComponents.LORE, new ItemLore(lore));
        return encoded;
    }

    @Override public String describe(PatternRecipe recipe) {
        var adapted = recipe.adapted();
        return adapted == null ? "" : adapted.adapterId() + ":" + adapted.recipeId();
    }
}
