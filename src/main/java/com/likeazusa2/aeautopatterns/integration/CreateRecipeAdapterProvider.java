package com.likeazusa2.aeautopatterns.integration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.fluids.FluidStack;

/** Explicit Create crushing and mixing support; no Create classes are linked at runtime. */
public final class CreateRecipeAdapterProvider implements RecipeAdapterProvider {
    private static final String CRUSHING_CLASS =
            "com.simibubi.create.content.kinetics.crusher.CrushingRecipe";
    private static final String MIXING_CLASS =
            "com.simibubi.create.content.kinetics.mixer.MixingRecipe";

    @Override public String adapterId() { return "aeautopatterns:create-processing"; }
    @Override public int priority() { return 40; }

    @Override public RecipeAdapter create(Level level, RecipeAdapter.Settings settings) {
        if (!settings.allowProcessing() || !ModList.get().isLoaded("create")) return null;
        return new ReflectiveModRecipeAdapter(adapterId(), priority(), level, List.of(
                new ReflectiveRecipeFamily(ResourceLocation.parse("create:crushing"),
                        ResourceLocation.parse("create:crushing"),
                        Set.of(ResourceLocation.parse("create:crushing")), Set.of(CRUSHING_CLASS),
                        CreateRecipeAdapterProvider::crushing),
                new ReflectiveRecipeFamily(ResourceLocation.parse("create:mixing"),
                        ResourceLocation.parse("create:mixing"),
                        Set.of(ResourceLocation.parse("create:mixing")), Set.of(MIXING_CLASS),
                        CreateRecipeAdapterProvider::mixing)));
    }

    private static RecipeDraft crushing(Level level,
            net.minecraft.world.item.crafting.RecipeHolder<?> holder)
            throws ReflectiveRecipeFamily.RecipeRejectedException {
        return parse(holder, "create:crushing");
    }

    private static RecipeDraft mixing(Level level,
            net.minecraft.world.item.crafting.RecipeHolder<?> holder)
            throws ReflectiveRecipeFamily.RecipeRejectedException {
        return parse(holder, "create:mixing");
    }

    private static RecipeDraft parse(net.minecraft.world.item.crafting.RecipeHolder<?> holder,
            String machineId) throws ReflectiveRecipeFamily.RecipeRejectedException {
        if (!automationAllowed(holder))
            throw RecipeReflection.reject("Create marks this recipe as manual-only or automation-ignored");
        Object recipe = holder.value();
        var inputs = new ArrayList<StackSpec>();
        for (Object ingredient : RecipeReflection.list(recipe, "getIngredients"))
            inputs.addAll(RecipeReflection.ingredient(ingredient, true));
        for (Object ingredient : RecipeReflection.list(recipe, "getFluidIngredients"))
            inputs.add(fluidIngredient(ingredient));

        var outputs = new ArrayList<StackSpec>();
        var probabilistic = new ArrayList<ChanceOutput>();
        for (Object result : RecipeReflection.list(recipe, "getRollableResults")) {
            Object stackValue = RecipeReflection.invoke(result, "getStack");
            if (!(stackValue instanceof ItemStack stack) || stack.isEmpty())
                throw RecipeReflection.reject("item output is empty");
            float chance = ((Number) RecipeReflection.invoke(result, "getChance")).floatValue();
            if (chance >= 1f) {
                outputs.add(RecipeReflection.fixedStack(stack));
            } else if (chance > 0f) {
                probabilistic.add(new ChanceOutput(RecipeReflection.key(stack), stack.getCount(), chance));
            }
        }
        for (Object fluidValue : RecipeReflection.list(recipe, "getFluidResults")) {
            if (!(fluidValue instanceof FluidStack fluid) || fluid.isEmpty())
                throw RecipeReflection.reject("fluid output is empty");
            outputs.add(RecipeReflection.fixedStack(fluid));
        }
        if (outputs.isEmpty()) throw RecipeReflection.reject("no deterministic output");

        var conditions = new ArrayList<String>();
        Object heat = RecipeReflection.invoke(recipe, "getRequiredHeat");
        String heatName = String.valueOf(RecipeReflection.invoke(heat, "getSerializedName"));
        if (!"none".equals(heatName)) conditions.add("create:heat_requirement=" + heatName);

        return new RecipeDraft(inputs, outputs, probabilistic,
                new MachineRequirements(ResourceLocation.parse(machineId), conditions));
    }

    private static boolean automationAllowed(net.minecraft.world.item.crafting.RecipeHolder<?> holder)
            throws ReflectiveRecipeFamily.RecipeRejectedException {
        try {
            Class<?> types = Class.forName("com.simibubi.create.AllRecipeTypes");
            var method = types.getMethod("shouldIgnoreInAutomation",
                    net.minecraft.world.item.crafting.RecipeHolder.class);
            return !Boolean.TRUE.equals(method.invoke(null, holder));
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new ReflectiveRecipeFamily.RecipeRejectedException(
                    "Cannot verify Create automation eligibility", e);
        }
    }

    private static StackSpec fluidIngredient(Object ingredient)
            throws ReflectiveRecipeFamily.RecipeRejectedException {
        Object value = RecipeReflection.invoke(ingredient, "getFluids");
        if (!(value instanceof FluidStack[] fluids) || fluids.length == 0)
            throw RecipeReflection.reject("fluid ingredient has no resolved representations");
        long amount = ((Number) RecipeReflection.invoke(ingredient, "amount")).longValue();
        if (amount <= 0) throw RecipeReflection.reject("fluid ingredient amount is not positive");

        var byKey = new LinkedHashMap<AEKey, Long>();
        for (FluidStack fluid : fluids) {
            if (fluid.isEmpty()) continue;
            AEFluidKey key = AEFluidKey.of(fluid);
            if (key != null) byKey.putIfAbsent(key, amount);
        }
        if (byKey.isEmpty()) throw RecipeReflection.reject("fluid ingredient has no supported keys");
        var entries = new ArrayList<>(byKey.entrySet());
        return new StackSpec(entries.getFirst().getKey(), amount, StackRole.CONSUMED,
                entries.stream().skip(1).map(Map.Entry::getKey).toList());
    }
}
