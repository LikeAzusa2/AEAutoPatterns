package com.likeazusa2.aeautopatterns.integration;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.fluids.FluidStack;

/** Narrow reflection used only by explicitly named, upstream-verified recipe families. */
final class RecipeReflection {
    private static final String MEKANISM_KEY = "me.ramidzkh.mekae2.ae2.MekanismKey";

    private RecipeReflection() {}

    static Object invoke(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(target.getClass().getName() + "." + name + "()", e);
        }
    }

    static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getField(name);
            return field.get(target);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException(target.getClass().getName() + "." + name, e);
        }
    }

    static List<?> list(Object target, String name) {
        Object value = invoke(target, name);
        if (value instanceof List<?> list) return list;
        throw new IllegalStateException(target.getClass().getName() + "." + name + "() is not a list");
    }

    static ResourceLocation recipeType(RecipeHolder<?> holder) {
        var key = BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType());
        if (key == null) throw new IllegalStateException("unregistered recipe type: " + holder.id());
        return key;
    }

    static ItemStack resultItem(RecipeHolder<?> holder, net.minecraft.world.level.Level level) {
        return holder.value().getResultItem(level.registryAccess());
    }

    static List<StackSpec> ingredient(Object ingredient, boolean fixedItemAmountOne)
            throws ReflectiveRecipeFamily.RecipeRejectedException {
        List<?> representations;
        if (ingredient instanceof Ingredient itemIngredient) {
            representations = Arrays.asList(itemIngredient.getItems());
        } else {
            representations = list(ingredient, "getRepresentations");
        }
        if (representations.isEmpty()) throw reject("ingredient has no resolved representations");

        var byKey = new LinkedHashMap<AEKey, Long>();
        for (Object representation : representations) {
            AEKey key = key(representation);
            long amount = fixedItemAmountOne || ingredient instanceof Ingredient
                    ? 1 : neededAmount(ingredient, representation);
            if (amount <= 0) throw reject("ingredient amount is not positive");
            Long previous = byKey.putIfAbsent(key, amount);
            if (previous != null && previous != amount)
                throw reject("an alternative has a different required amount");
        }
        var entries = new ArrayList<>(byKey.entrySet());
        AEKey primary = entries.getFirst().getKey();
        long amount = entries.getFirst().getValue();
        var alternatives = entries.stream().skip(1).map(Map.Entry::getKey).toList();
        return List.of(new StackSpec(primary, amount, StackRole.CONSUMED, alternatives));
    }

    static StackSpec fixedStack(Object stack) throws ReflectiveRecipeFamily.RecipeRejectedException {
        AEKey key = key(stack);
        long amount = amount(stack);
        if (amount <= 0) throw reject("output amount is not positive");
        return new StackSpec(key, amount, StackRole.RETURNED);
    }

    static AEKey key(Object stack) throws ReflectiveRecipeFamily.RecipeRejectedException {
        if (stack instanceof ItemStack item && !item.isEmpty()) {
            AEItemKey key = AEItemKey.of(item);
            if (key != null) return key;
        }
        if (stack instanceof FluidStack fluid && !fluid.isEmpty()) {
            AEFluidKey key = AEFluidKey.of(fluid);
            if (key != null) return key;
        }
        AEKey chemical = chemicalKey(stack);
        if (chemical != null) return chemical;
        throw reject("unsupported stack representation " + (stack == null ? "null" : stack.getClass().getName()));
    }

    static long amount(Object stack) {
        if (stack instanceof ItemStack item) return item.getCount();
        if (stack instanceof FluidStack fluid) return fluid.getAmount();
        Object value = invoke(stack, "getAmount");
        return value instanceof Number number ? number.longValue() : 0;
    }

    static long neededAmount(Object ingredient, Object representation) {
        try {
            Method method = ingredient.getClass().getMethod("getNeededAmount", representation.getClass());
            Object value = method.invoke(ingredient, representation);
            return value instanceof Number number ? number.longValue() : 0;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(ingredient.getClass().getName() + ".getNeededAmount()", e);
        }
    }

    static AEKey chemicalKey(Object stack) {
        if (stack == null) return null;
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> keyClass = Class.forName(MEKANISM_KEY, false,
                    loader == null ? RecipeReflection.class.getClassLoader() : loader);
            for (Method method : keyClass.getMethods()) {
                if (!method.getName().equals("of") || method.getParameterCount() != 1) continue;
                if (!method.getParameterTypes()[0].isAssignableFrom(stack.getClass())) continue;
                Object value = method.invoke(null, stack);
                return value instanceof AEKey key ? key : null;
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
        return null;
    }

    static ReflectiveRecipeFamily.RecipeRejectedException reject(String message) {
        return new ReflectiveRecipeFamily.RecipeRejectedException(message);
    }
}
