package com.likeazusa2.aeautopatterns;

import java.util.ArrayList;
import java.util.List;
import appeng.api.parts.PartHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import appeng.core.definitions.AEParts;
import com.likeazusa2.aeautopatterns.integration.SharedCraftingIndex;
import com.likeazusa2.aeautopatterns.logic.DependencyPlanner;
import com.likeazusa2.aeautopatterns.part.AutoPatternTerminalPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AEAutoPatterns.ID)
@PrefixGameTestTemplate(false)
public final class SimilarRecipeBatchGameTests {
    private static final BlockPos TERMINAL = new BlockPos(1, 2, 1);

    @GameTest(template = "empty", timeoutTicks = 600, batch = "similar_recipe_batch")
    public void similarBatchOwnsChoicesAndRefusesStaleToken(GameTestHelper helper) {
        var manager = helper.getLevel().getRecipeManager();
        var original = new ArrayList<RecipeHolder<?>>();
        var extra = List.of(
            holder("similar_root_test", new ShapelessRecipe("", CraftingBookCategory.MISC,
                new ItemStack(Items.DRAGON_BREATH),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.BAKED_POTATO),
                    Ingredient.of(Items.COOKED_RABBIT), Ingredient.of(Items.IRON_INGOT)))),
            holder("similar_baked_potato_test", new SmeltingRecipe("", CookingBookCategory.MISC,
                Ingredient.of(Items.COOKED_RABBIT), new ItemStack(Items.BAKED_POTATO), 0.1f, 200)));

        var terminal = setup(helper);
        terminal.setSubstitution(false, true);
        terminal.toggleProcessing();
        var root = AEItemKey.of(Items.DRAGON_BREATH);
        var source = AEItemKey.of(Items.BAKED_POTATO);
        var candidate = AEItemKey.of(Items.COOKED_RABBIT);
        var ambiguous = AEItemKey.of(Items.IRON_INGOT);
        terminal.setTarget(root);
        terminal.inventory().setStackInSlot(0, AEItems.BLANK_PATTERN.stack(8));

        helper.startSequence()
            .thenWaitUntil(() -> check(terminal.online(), "Terminal must be online"))
            .thenIdle(20)
            .thenExecute(() -> {
                original.addAll(manager.getRecipes());
                var recipes = new ArrayList<>(original);
                recipes.addAll(extra);
                manager.replaceRecipes(recipes);
                SharedCraftingIndex.clear();
                terminal.analyze();
            })
            .thenWaitUntil(() -> check(terminal.state() == AutoPatternTerminalPart.State.WAITING_SELECTION,
                "Cooking choices must wait for selection: " + terminal.state() + " " + terminal.error()))
            .thenExecute(() -> {
                var sourceNode = terminal.nodes().get(source);
                var candidateNode = terminal.nodes().get(candidate);
                var ambiguousNode = terminal.nodes().get(ambiguous);
                check(sourceNode != null, "Source must be visited");
                check(candidateNode != null, "Candidate must be visited");
                check(ambiguousNode != null, "Ambiguous output must be visited");
                check(sourceNode.choice().kind() == DependencyPlanner.Kind.NEEDS_RECIPE,
                    "Source must be an unconfirmed recipe node");
                check(candidateNode.choice().kind() == DependencyPlanner.Kind.NEEDS_RECIPE,
                    "Candidate must be an unconfirmed recipe node");
                check(ambiguousNode.choice().kind() == DependencyPlanner.Kind.NEEDS_RECIPE,
                    "Ambiguous candidate must be an unconfirmed recipe node");

                var sourceChoices = terminal.recipeOptions(source).stream().filter(choice ->
                    choice.payload().recipe() != null
                        && choice.payload().recipe().value() instanceof SmeltingRecipe).toList();
                check(sourceChoices.size() == 2, "Source must expose both smelting choices");
                var sourceChoice = sourceChoices.stream()
                    .filter(choice -> choice.id().equals("minecraft:baked_potato")).findFirst().orElse(null);
                check(sourceChoice != null, "Source must include minecraft:baked_potato");
                var candidateChoice = singleCookingChoice(terminal.recipeOptions(candidate), candidate);
                check(!sourceChoice.id().equals(candidateChoice.id()), "Fixture outputs need distinct recipe IDs");

                var preview = terminal.similarRecipeBatch(source, sourceChoice.id());
                check(preview != null && preview.count() == 2,
                    "Preview must contain source and the only unambiguous family match");
                check(preview.token().length() == 64, "Preview token must be a stable 64-character digest");
                check(preview.targets().stream().noneMatch(target -> target.key().equals(ambiguous)),
                    "Two same-family candidates must leave the ambiguous output unmodified");
                check(targetChoice(preview, source).choiceId().equals(sourceChoice.id()),
                    "Source target must retain the displayed source choice");
                check(targetChoice(preview, candidate).choiceId().equals(candidateChoice.id()),
                    "Similar target must retain its own choice ID");
                check(!targetChoice(preview, candidate).choiceId().equals(sourceChoice.id()),
                    "Similar target must never copy the source recipe ID");

                int before = terminal.revision();
                check(!terminal.applySimilarRecipes(source, sourceChoice.id(), "stale-token"),
                    "A stale token must be refused");
                check(terminal.revision() == before && terminal.state() == AutoPatternTerminalPart.State.WAITING_SELECTION,
                    "Stale refusal must not commit or reanalyze");
                check(terminal.applySimilarRecipes(source, sourceChoice.id(), preview.token()),
                    "Fresh preview token must commit the batch");
                check(terminal.revision() == before + 1 && terminal.state() == AutoPatternTerminalPart.State.INDEXING,
                    "Batch commit must schedule exactly one analysis");
            })
            .thenWaitUntil(() -> check(terminal.state() == AutoPatternTerminalPart.State.WAITING_SELECTION,
                "Reanalysis must finish with the ambiguous node still pending: " + terminal.state() + " " + terminal.error()))
            .thenExecute(() -> {
                try {
                    var sourceNode = terminal.nodes().get(source);
                    var candidateNode = terminal.nodes().get(candidate);
                    var sourceId = sourceNode.choice().id();
                    var candidateId = candidateNode.choice().id();
                    check(sourceNode.choice().kind() == DependencyPlanner.Kind.GENERATE,
                        "Source must use its own selected recipe");
                    check(candidateNode.choice().kind() == DependencyPlanner.Kind.GENERATE,
                        "Similar output must use its own selected recipe");
                    check(!sourceId.equals(candidateId), "Each output must keep a distinct recipe ID");
                    check(sourceId.equals("minecraft:baked_potato"), "Source smelting recipe must remain selected");
                    check(candidateId.equals("minecraft:cooked_rabbit"), "Candidate smelting recipe must be selected independently");
                    check(terminal.nodes().get(ambiguous).choice().kind() == DependencyPlanner.Kind.NEEDS_RECIPE,
                        "Ambiguous same-family candidate must remain unmodified");
                } finally {
                    manager.replaceRecipes(original);
                    SharedCraftingIndex.clear();
                }
            })
            .thenSucceed();
    }

    private AutoPatternTerminalPart setup(GameTestHelper helper) {
        PartHelper.setPart(helper.getLevel(), helper.absolutePos(TERMINAL), null, null,
            AEParts.GLASS_CABLE.item(appeng.api.util.AEColor.TRANSPARENT));
        helper.setBlock(TERMINAL.below(), AEBlocks.CREATIVE_ENERGY_CELL.block());
        return PartHelper.setPart(helper.getLevel(), helper.absolutePos(TERMINAL),
            net.minecraft.core.Direction.NORTH, null, ModContent.TERMINAL.get());
    }

    private static RecipeHolder<?> holder(String path, Recipe<?> recipe) {
        return new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath(AEAutoPatterns.ID, path), recipe);
    }

    private static DependencyPlanner.Choice<AEKey, com.likeazusa2.aeautopatterns.integration.PatternRecipe>
            singleCookingChoice(
            List<DependencyPlanner.Choice<AEKey, com.likeazusa2.aeautopatterns.integration.PatternRecipe>> choices,
            AEKey expected) {
        var matches = choices.stream().filter(choice ->
            choice.payload().recipe() != null
                && choice.payload().recipe().value() instanceof SmeltingRecipe).toList();
        check(matches.size() == 1, "Expected exactly one smelting choice for " + expected);
        return matches.getFirst();
    }

    private static AutoPatternTerminalPart.SimilarRecipeTarget targetChoice(
            AutoPatternTerminalPart.SimilarRecipeBatch preview, AEKey key) {
        check(preview.targets().stream().anyMatch(target -> target.key().equals(key)),
            "Preview must contain " + key);
        return preview.targets().stream().filter(target -> target.key().equals(key)).findFirst().orElseThrow();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
