package com.likeazusa2.aeautopatterns.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

/**
 * Exact Mekanism basic recipe classes. Chemical families are enabled only when
 * Applied Mekanistics has registered its AE key type.
 */
public final class MekanismChemicalRecipeAdapter extends ReflectiveModRecipeAdapter {
    private static final String ITEM_CRUSHING_CLASS =
            "mekanism.api.recipes.basic.BasicCrushingRecipe";
    private static final String ITEM_ENRICHING_CLASS =
            "mekanism.api.recipes.basic.BasicEnrichingRecipe";
    private static final String CRYSTALLIZER_CLASS =
            "mekanism.api.recipes.basic.BasicChemicalCrystallizerRecipe";
    private static final String DISSOLUTION_CLASS =
            "mekanism.api.recipes.basic.BasicChemicalDissolutionRecipe";
    private static final String INFUSER_CLASS =
            "mekanism.api.recipes.basic.BasicChemicalInfuserRecipe";
    private static final String OXIDIZER_CLASS =
            "mekanism.api.recipes.basic.BasicChemicalOxidizerRecipe";
    private static final String ACTIVATING_CLASS =
            "mekanism.api.recipes.basic.BasicActivatingRecipe";
    private static final String CENTRIFUGING_CLASS =
            "mekanism.api.recipes.basic.BasicCentrifugingRecipe";

    public MekanismChemicalRecipeAdapter(Level level) {
        this(level, true);
    }

    public MekanismChemicalRecipeAdapter(Level level, boolean includeChemicals) {
        super("aeautopatterns:mekanism-processing", 60, level, families(includeChemicals));
    }

    private static List<ReflectiveRecipeFamily> families(boolean includeChemicals) {
        var families = new ArrayList<ReflectiveRecipeFamily>();
        families.add(family("mekanism:crushing", "mekanism:crusher",
                ITEM_CRUSHING_CLASS, MekanismChemicalRecipeAdapter::itemToItem));
        families.add(family("mekanism:enriching", "mekanism:enrichment_chamber",
                ITEM_ENRICHING_CLASS, MekanismChemicalRecipeAdapter::itemToItem));
        if (includeChemicals) {
            families.add(family("mekanism:crystallizing", "mekanism:chemical_crystallizer",
                    CRYSTALLIZER_CLASS, MekanismChemicalRecipeAdapter::chemicalToItem));
            families.add(family("mekanism:dissolution", "mekanism:chemical_dissolution_chamber",
                    DISSOLUTION_CLASS, MekanismChemicalRecipeAdapter::dissolution));
            families.add(family("mekanism:chemical_infusing", "mekanism:chemical_infuser",
                    INFUSER_CLASS, MekanismChemicalRecipeAdapter::infuser));
            families.add(family("mekanism:oxidizing", "mekanism:chemical_oxidizer",
                    OXIDIZER_CLASS, MekanismChemicalRecipeAdapter::itemToChemical));
            families.add(family("mekanism:activating", "mekanism:solar_neutron_activator",
                    ACTIVATING_CLASS, MekanismChemicalRecipeAdapter::chemicalToChemical));
            families.add(family("mekanism:centrifuging", "mekanism:isotopic_centrifuge",
                    CENTRIFUGING_CLASS, MekanismChemicalRecipeAdapter::chemicalToChemical));
        }
        return List.copyOf(families);
    }

    private static ReflectiveRecipeFamily family(String type, String machine, String className,
            ReflectiveRecipeFamily.Parser parser) {
        ResourceLocation recipeType = ResourceLocation.parse(type);
        return new ReflectiveRecipeFamily(recipeType, ResourceLocation.parse(machine),
                Set.of(recipeType), Set.of(className), parser);
    }

    private static RecipeDraft itemToItem(Level level, RecipeHolder<?> holder)
            throws ReflectiveRecipeFamily.RecipeRejectedException {
        var inputs = new ArrayList<>(RecipeReflection.ingredient(
                RecipeReflection.invoke(holder.value(), "getInput"), false));
        return new RecipeDraft(inputs, outputList(holder, "getOutputDefinition"),
                List.of(), requirements(holder));
    }

    private static RecipeDraft chemicalToItem(Level level, RecipeHolder<?> holder)
            throws ReflectiveRecipeFamily.RecipeRejectedException {
        var inputs = new ArrayList<>(RecipeReflection.ingredient(
                RecipeReflection.invoke(holder.value(), "getInput"), false));
        return new RecipeDraft(inputs, outputList(holder, "getOutputDefinition"),
                List.of(), requirements(holder));
    }

    private static RecipeDraft itemToChemical(Level level, RecipeHolder<?> holder)
            throws ReflectiveRecipeFamily.RecipeRejectedException {
        var inputs = new ArrayList<>(RecipeReflection.ingredient(
                RecipeReflection.invoke(holder.value(), "getInput"), false));
        return new RecipeDraft(inputs, outputList(holder, "getOutputDefinition"),
                List.of(), requirements(holder));
    }

    private static RecipeDraft chemicalToChemical(Level level, RecipeHolder<?> holder)
            throws ReflectiveRecipeFamily.RecipeRejectedException {
        var inputs = new ArrayList<>(RecipeReflection.ingredient(
                RecipeReflection.invoke(holder.value(), "getInput"), false));
        return new RecipeDraft(inputs, outputList(holder, "getOutputDefinition"),
                List.of(), requirements(holder));
    }

    private static RecipeDraft infuser(Level level, RecipeHolder<?> holder)
            throws ReflectiveRecipeFamily.RecipeRejectedException {
        var inputs = new ArrayList<StackSpec>();
        inputs.addAll(RecipeReflection.ingredient(RecipeReflection.invoke(holder.value(), "getLeftInput"), false));
        inputs.addAll(RecipeReflection.ingredient(RecipeReflection.invoke(holder.value(), "getRightInput"), false));
        return new RecipeDraft(inputs, outputList(holder, "getOutputDefinition"),
                List.of(), requirements(holder));
    }

    private static RecipeDraft dissolution(Level level, RecipeHolder<?> holder)
            throws ReflectiveRecipeFamily.RecipeRejectedException {
        Object recipe = holder.value();
        if (Boolean.TRUE.equals(RecipeReflection.invoke(recipe, "perTickUsage")))
            throw RecipeReflection.reject("per-tick chemical use cannot be represented as one AE operation");
        var inputs = new ArrayList<StackSpec>();
        inputs.addAll(RecipeReflection.ingredient(RecipeReflection.invoke(recipe, "getItemInput"), false));
        inputs.addAll(RecipeReflection.ingredient(RecipeReflection.invoke(recipe, "getChemicalInput"), false));
        return new RecipeDraft(inputs, outputList(holder, "getOutputDefinition"),
                List.of(), requirements(holder));
    }

    private static List<StackSpec> outputList(RecipeHolder<?> holder, String method)
            throws ReflectiveRecipeFamily.RecipeRejectedException {
        var definitions = RecipeReflection.list(holder.value(), method);
        if (definitions.size() != 1)
            throw RecipeReflection.reject("dynamic or multiple output definitions are not supported");
        return List.of(RecipeReflection.fixedStack(definitions.getFirst()));
    }

    private static MachineRequirements requirements(RecipeHolder<?> holder) {
        ResourceLocation type = RecipeReflection.recipeType(holder);
        ResourceLocation machine = switch (type.toString()) {
            case "mekanism:crushing" -> ResourceLocation.parse("mekanism:crusher");
            case "mekanism:enriching" -> ResourceLocation.parse("mekanism:enrichment_chamber");
            case "mekanism:crystallizing" -> ResourceLocation.parse("mekanism:chemical_crystallizer");
            case "mekanism:dissolution" -> ResourceLocation.parse("mekanism:chemical_dissolution_chamber");
            case "mekanism:chemical_infusing" -> ResourceLocation.parse("mekanism:chemical_infuser");
            case "mekanism:oxidizing" -> ResourceLocation.parse("mekanism:chemical_oxidizer");
            case "mekanism:activating" -> ResourceLocation.parse("mekanism:solar_neutron_activator");
            case "mekanism:centrifuging" -> ResourceLocation.parse("mekanism:isotopic_centrifuge");
            default -> throw new IllegalStateException("Unexpected Mekanism recipe type: " + type);
        };
        return MachineRequirements.none(machine);
    }
}
