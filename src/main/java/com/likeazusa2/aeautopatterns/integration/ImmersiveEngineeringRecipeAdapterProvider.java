package com.likeazusa2.aeautopatterns.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/** Explicit Immersive Engineering crusher support without linking optional classes. */
public final class ImmersiveEngineeringRecipeAdapterProvider implements RecipeAdapterProvider {
    private static final String CRUSHER_CLASS =
            "blusunrize.immersiveengineering.api.crafting.CrusherRecipe";

    @Override public String adapterId() { return "aeautopatterns:immersiveengineering-crusher"; }
    @Override public int priority() { return 30; }

    @Override public RecipeAdapter create(Level level, RecipeAdapter.Settings settings) {
        if (!settings.allowProcessing() || !ModList.get().isLoaded("immersiveengineering")) return null;
        return new ReflectiveModRecipeAdapter(adapterId(), priority(), level, List.of(
                new ReflectiveRecipeFamily(ResourceLocation.parse("immersiveengineering:crusher"),
                        ResourceLocation.parse("immersiveengineering:crusher"),
                        Set.of(ResourceLocation.parse("immersiveengineering:crusher")), Set.of(CRUSHER_CLASS),
                        ImmersiveEngineeringRecipeAdapterProvider::parse)));
    }

    private static RecipeDraft parse(Level level, RecipeHolder<?> holder)
            throws ReflectiveRecipeFamily.RecipeRejectedException {
        Object recipe = holder.value();
        Object input = RecipeReflection.field(recipe, "input");
        var inputs = new ArrayList<>(RecipeReflection.ingredient(input, true));

        Object primaryOutputObject = RecipeReflection.invoke(RecipeReflection.field(recipe, "output"), "get");
        if (!(primaryOutputObject instanceof ItemStack primary) || primary.isEmpty())
            throw RecipeReflection.reject("crusher primary output is dynamic or empty");
        var outputs = new ArrayList<StackSpec>();
        outputs.add(RecipeReflection.fixedStack(primary));

        var probabilistic = new ArrayList<ChanceOutput>();
        var conditions = new ArrayList<String>();
        Object rawSecondaries = RecipeReflection.field(recipe, "secondaryOutputs");
        if (!(rawSecondaries instanceof List<?> secondaries))
            throw RecipeReflection.reject("crusher secondaries are not a fixed list");
        for (Object secondary : secondaries) {
            Object stackObject = RecipeReflection.invoke(RecipeReflection.invoke(secondary, "stack"), "get");
            if (!(stackObject instanceof ItemStack stack) || stack.isEmpty())
                throw RecipeReflection.reject("crusher secondary output is dynamic or empty");
            float chance = ((Number) RecipeReflection.invoke(secondary, "chance")).floatValue();
            Object rawConditions = RecipeReflection.invoke(secondary, "conditions");
            if (!(rawConditions instanceof List<?> secondaryConditions))
                throw RecipeReflection.reject("crusher secondary conditions are not a fixed list");
            if (!secondaryConditions.isEmpty()) {
                for (Object condition : secondaryConditions)
                    conditions.add("immersiveengineering:condition="
                            + condition.getClass().getName());
                continue;
            }
            if (chance >= 1f) {
                outputs.add(RecipeReflection.fixedStack(stack));
            } else if (chance > 0f) {
                probabilistic.add(new ChanceOutput(RecipeReflection.key(stack), stack.getCount(), chance));
            }
        }

        return new RecipeDraft(inputs, outputs, probabilistic,
                new MachineRequirements(ResourceLocation.parse("immersiveengineering:crusher"), conditions));
    }
}
