package com.likeazusa2.aeautopatterns.menu;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import appeng.api.stacks.GenericStack;
import com.likeazusa2.aeautopatterns.integration.PatternRecipe;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;

/** Menu description of an already supported server recipe. */
record ProcessingRecipeView(ResourceLocation id, ResourceLocation machine, List<GenericStack> inputs,
        List<GenericStack> outputs, String searchText) {
    static ProcessingRecipeView of(PatternRecipe recipe, HolderLookup.Provider registries, List<AEKey> selected) {
        if (recipe == null) return null;
        var adapted = recipe.adapted();
        if (adapted != null) {
            var inputs = new java.util.ArrayList<GenericStack>();
            for (int i = 0; i < adapted.inputs().size(); i++) {
                var input = adapted.inputs().get(i);
                if (input.isConsumed()) inputs.add(new GenericStack(
                    selected.size() == adapted.inputs().size() ? selected.get(i) : input.key(), input.amount()));
            }
            var outputs = adapted.outputs().stream().map(out -> new GenericStack(out.key(), out.amount())).toList();
            var search = adapted.inputs().stream().flatMap(input -> java.util.stream.Stream.concat(
                java.util.stream.Stream.of(input.key()), input.alternatives().stream())).map(ProcessingRecipeView::searchKey)
                .collect(Collectors.joining(" "));
            return create(adapted.recipeId(), adapted.requirements().machineType(), List.copyOf(inputs), outputs, search);
        }
        if (recipe.recipe() == null || !(recipe.recipe().value() instanceof AbstractCookingRecipe cooking)) return null;
        var variants = cooking.getIngredients().getFirst().getItems();
        var output = cooking.getResultItem(registries);
        if (variants.length == 0 || output.isEmpty()) return null;
        var search = java.util.Arrays.stream(variants).map(AEItemKey::of).map(ProcessingRecipeView::searchKey)
            .collect(Collectors.joining(" "));
        return create(recipe.recipe().id(), BuiltInRegistries.RECIPE_TYPE.getKey(cooking.getType()),
            List.of(new GenericStack(selected.isEmpty() ? AEItemKey.of(variants[0]) : selected.getFirst(), 1)),
            List.of(new GenericStack(AEItemKey.of(output), output.getCount())), search);
    }

    private static ProcessingRecipeView create(ResourceLocation id, ResourceLocation machine, List<GenericStack> inputs,
            List<GenericStack> outputs, String inputSearch) {
        String search = machine + " " + machineFallback(machine) + " " + inputSearch + " "
            + outputs.stream().map(stack -> searchKey(stack.what())).collect(Collectors.joining(" "));
        return new ProcessingRecipeView(id, machine, inputs, outputs, search.toLowerCase(Locale.ROOT));
    }

    private static String searchKey(AEKey key) {
        return key.getDisplayName().getString() + (key instanceof AEItemKey item
            ? " " + BuiltInRegistries.ITEM.getKey(item.getItem()) : "");
    }

    static String machineKey(ResourceLocation machine) {
        return "gui.aeautopatterns.routes.machine." + machine.getNamespace() + "." + machine.getPath().replace('/', '.');
    }

    static String machineFallback(ResourceLocation machine) {
        return switch (machine.toString()) {
            case "minecraft:smelting" -> "Minecraft · Furnace";
            case "minecraft:blasting" -> "Minecraft · Blast Furnace";
            case "minecraft:smoking" -> "Minecraft · Smoker";
            case "minecraft:campfire_cooking" -> "Minecraft · Campfire";
            case "create:crushing" -> "Create · Crushing Wheels";
            case "create:mixing" -> "Create · Mechanical Mixer";
            case "immersiveengineering:crusher" -> "Immersive Engineering · Crusher";
            case "mekanism:enriching" -> "Mekanism · Enrichment Chamber";
            case "mekanism:crushing" -> "Mekanism · Crusher";
            default -> title(machine.getNamespace()) + " · " + title(machine.getPath());
        };
    }

    private static String title(String id) {
        return java.util.Arrays.stream(id.split("[_/.-]+"))
            .filter(word -> !word.isEmpty()).map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
            .collect(Collectors.joining(" "));
    }

    CompoundTag describe(HolderLookup.Provider registries) {
        var row = new CompoundTag();
        row.putString("name", outputs.getFirst().what().getDisplayName().getString());
        row.putString("machineKey", machineKey(machine));
        row.putString("machineFallback", machineFallback(machine));
        row.put("inputs", stacks(inputs, registries));
        row.put("outputs", stacks(outputs, registries));
        return row;
    }

    private static ListTag stacks(List<GenericStack> stacks, HolderLookup.Provider registries) {
        var tags = new ListTag();
        for (var stack : stacks) {
            var tag = new CompoundTag();
            tag.putLong("amount", stack.amount());
            tag.putString("formattedAmount", stack.what().formatAmount(stack.amount(), AmountFormat.FULL));
            tag.putString("name", stack.what().getDisplayName().getString());
            tag.putString("nameComponent", Component.Serializer.toJson(stack.what().getDisplayName(), registries));
            if (stack.what() instanceof AEItemKey item) tag.put("stack", item.toStack().save(registries));
            tags.add(tag);
        }
        return tags;
    }
}
