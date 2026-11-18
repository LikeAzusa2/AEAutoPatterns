package com.likeazusa2.aeautopatterns;

import java.util.List;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import appeng.core.definitions.AEParts;
import com.likeazusa2.aeautopatterns.integration.ProcessingRecipeAdapter;
import com.likeazusa2.aeautopatterns.logic.PatternDeployment;
import com.likeazusa2.aeautopatterns.part.AutoPatternTerminalPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AEAutoPatterns.ID)
@PrefixGameTestTemplate(false)
public final class RoutedProcessingDeploymentGameTests {
    private static final BlockPos TERMINAL = new BlockPos(1, 2, 1);
    private static final AEItemKey BAKED_POTATO = AEItemKey.of(Items.BAKED_POTATO);

    @GameTest(template = "empty", timeoutTicks = 500)
    public void fullFirstRouteGroupDoesNotFallThroughToAnotherGroup(GameTestHelper helper) {
        var terminal = setup(helper);
        helper.setBlock(TERMINAL.east(), AEBlocks.PATTERN_PROVIDER.block());
        helper.setBlock(TERMINAL.east(2), AEBlocks.PATTERN_PROVIDER.block());
        var first = (appeng.blockentity.crafting.PatternProviderBlockEntity)
            helper.getBlockEntity(TERMINAL.east());
        var other = (appeng.blockentity.crafting.PatternProviderBlockEntity)
            helper.getBlockEntity(TERMINAL.east(2));
        var firstId = PatternDeployment.identity(first);
        var otherId = PatternDeployment.identity(other);
        var filler = PatternDetailsHelper.encodeProcessingPattern(
            List.of(new GenericStack(AEItemKey.of(Items.RAW_IRON), 1)),
            List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1)));

        helper.startSequence()
            .thenWaitUntil(() -> check(terminal.online() && terminal.uploadTargets().size() == 2,
                "Two routed providers must be online"))
            .thenExecute(() -> {
                terminal.setTarget(BAKED_POTATO);
                terminal.toggleProcessing();
                terminal.setAutoUpload(true);
                terminal.setAutoContinueProviders(true);
                terminal.inventory().setStackInSlot(0, AEItems.BLANK_PATTERN.stack(3));
                fill(first, filler);
                terminal.analyze();
            })
            .thenWaitUntil(() -> check(terminal.state() == AutoPatternTerminalPart.State.WAITING_SELECTION,
                "Cooking routes require an explicit recipe: " + terminal.state() + " " + terminal.error()))
            .thenExecute(() -> {
                var choice = terminal.recipeOptions(BAKED_POTATO).getFirst();
                var recipeId = ResourceLocation.parse(choice.id());
                var firstGroup = terminal.createRecipeRouteGroup("First smelter");
                var otherGroup = terminal.createRecipeRouteGroup("Unrelated smelter");
                check(firstGroup != null && otherGroup != null, "Route groups must be created");
                check(terminal.addRecipesToRouteGroup(firstGroup, List.of(recipeId)),
                    "Selected recipe must bind to the first group");
                check(terminal.toggleRouteGroupProvider(firstGroup, firstId),
                    "First provider must bind to its group");
                check(terminal.toggleRouteGroupProvider(otherGroup, otherId),
                    "Other provider must bind to a different group");
                terminal.chooseRecipe(BAKED_POTATO, choice.id());
            })
            .thenWaitUntil(() -> {
                var status = terminal.routeStatus();
                check(terminal.state() == AutoPatternTerminalPart.State.READY,
                    "Routed processing plan must be ready: " + terminal.state() + " " + terminal.error());
                check(status.state() == com.likeazusa2.aeautopatterns.logic.RecipeRouteGroups.RouteState.READY,
                    "Selected route must be ready");
                check(status.providerIds().equals(List.of(firstId)),
                    "Route status must contain only the selected group's provider");
            })
            .thenExecute(terminal::startOrResume)
            .thenWaitUntil(() -> check(terminal.state() == AutoPatternTerminalPart.State.FULL,
                "Full routed provider must pause: " + terminal.state() + " " + terminal.error()))
            .thenExecute(() -> {
                check(terminal.inventory().getStackInSlot(0).getCount() == 3,
                    "A full route destination must not consume a blank");
                check(allSlotsMatch(first, filler), "Full first provider must retain every filler pattern");
                check(patternCount(other) == 0,
                    "A provider in another route group must not receive the pattern");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 500)
    public void routedInsertAndDuplicateConsumeAtMostOneBlank(GameTestHelper helper) {
        var terminal = setup(helper);
        helper.setBlock(TERMINAL.east(), AEBlocks.PATTERN_PROVIDER.block());
        var provider = (appeng.blockentity.crafting.PatternProviderBlockEntity)
            helper.getBlockEntity(TERMINAL.east());
        var providerId = PatternDeployment.identity(provider);

        helper.startSequence()
            .thenWaitUntil(() -> check(terminal.online() && terminal.uploadTargets().size() == 1,
                "Routed provider must be online"))
            .thenExecute(() -> {
                terminal.setTarget(BAKED_POTATO);
                terminal.toggleProcessing();
                terminal.setAutoUpload(true);
                terminal.inventory().setStackInSlot(0, AEItems.BLANK_PATTERN.stack(1));
                terminal.analyze();
            })
            .thenWaitUntil(() -> check(terminal.state() == AutoPatternTerminalPart.State.WAITING_SELECTION,
                "Cooking routes require an explicit recipe: " + terminal.state() + " " + terminal.error()))
            .thenExecute(() -> routeSelectedRecipe(terminal, providerId))
            .thenWaitUntil(() -> check(terminal.state() == AutoPatternTerminalPart.State.READY,
                "Routed processing plan must be ready: " + terminal.state() + " " + terminal.error()))
            .thenExecute(terminal::startOrResume)
            .thenWaitUntil(() -> check(terminal.state() == AutoPatternTerminalPart.State.COMPLETE,
                "Routed processing insertion must complete: " + terminal.state() + " " + terminal.error()))
            .thenExecute(() -> {
                check(terminal.inventory().getStackInSlot(0).isEmpty(),
                    "One inserted routed pattern must consume exactly one blank");
                check(patternCount(provider) == 1, "Provider must contain exactly one pattern");
                var pattern = firstPattern(provider);
                terminal.inventory().setStackInSlot(0, AEItems.BLANK_PATTERN.stack(1));
                var duplicate = PatternDeployment.deployOrdered(terminal.node().getGrid(),
                    List.of(providerId), true, pattern, helper.getLevel());
                check(duplicate.result() == PatternDeployment.Result.DUPLICATE,
                    "Repeated routed deployment must be recognized as duplicate");
                check(duplicate.providerId().equals(providerId),
                    "Duplicate result must identify the routed provider");
                check(terminal.inventory().getStackInSlot(0).getCount() == 1,
                    "Duplicate routed deployment must not consume a blank");
                check(patternCount(provider) == 1, "Duplicate deployment must not add another pattern");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public void sameOutputDifferentRecipeIdsRemainDistinctPatterns(GameTestHelper helper) {
        var terminal = setup(helper);
        helper.setBlock(TERMINAL.east(), AEBlocks.PATTERN_PROVIDER.block());
        var provider = (appeng.blockentity.crafting.PatternProviderBlockEntity)
            helper.getBlockEntity(TERMINAL.east());
        var providerId = PatternDeployment.identity(provider);

        helper.startSequence()
            .thenWaitUntil(() -> check(terminal.online() && terminal.uploadTargets().size() == 1,
                "Provider must be online"))
            .thenExecute(() -> {
                var adapter = new ProcessingRecipeAdapter(helper.getLevel());
                while (!adapter.indexStep(64)) {
                    // Process the full recipe catalog without running Gradle or a client.
                }
                var choices = adapter.find(BAKED_POTATO);
                check(choices.size() >= 2, "Baked potato fixture needs multiple recipe IDs");
                var firstInputs = choices.get(0).ingredients().stream().map(List::getFirst).toList();
                var secondInputs = choices.get(1).ingredients().stream().map(List::getFirst).toList();
                var first = adapter.encode(choices.get(0).payload(), firstInputs, BAKED_POTATO,
                    helper.getLevel());
                var second = adapter.encode(choices.get(1).payload(), secondInputs, BAKED_POTATO,
                    helper.getLevel());
                var firstId = choices.get(0).payload().recipe().id();
                var secondId = choices.get(1).payload().recipe().id();
                check(!firstId.equals(secondId), "Fixture recipe IDs must differ");
                var firstDecoded = PatternDetailsHelper.decodePattern(first, helper.getLevel());
                var secondDecoded = PatternDetailsHelper.decodePattern(second, helper.getLevel());
                check(firstDecoded != null && secondDecoded != null, "Encoded patterns must decode");
                check(firstDecoded.getPrimaryOutput().what().equals(secondDecoded.getPrimaryOutput().what()),
                    "Fixture recipes must produce the same output");
                check(first.get(DataComponents.LORE).lines().getFirst().getString().contains(firstId.toString()),
                    "First pattern must retain its recipe ID");
                check(second.get(DataComponents.LORE).lines().getFirst().getString().contains(secondId.toString()),
                    "Second pattern must retain its recipe ID");
                check(!ItemStack.isSameItemSameComponents(first, second),
                    "Different recipe IDs must remain distinct encoded patterns");

                var grid = terminal.node().getGrid();
                check(PatternDeployment.deployOrdered(grid, List.of(providerId), true, first,
                    helper.getLevel()).result() == PatternDeployment.Result.INSERTED,
                    "First recipe ID must deploy");
                check(PatternDeployment.deployOrdered(grid, List.of(providerId), true, second,
                    helper.getLevel()).result() == PatternDeployment.Result.INSERTED,
                    "Second recipe ID must deploy alongside the first");
                check(PatternDeployment.deployOrdered(grid, List.of(providerId), true, first,
                    helper.getLevel()).result() == PatternDeployment.Result.DUPLICATE,
                    "Only the exact repeated recipe-ID pattern is duplicate");
                check(patternCount(provider) == 2,
                    "Both recipe IDs for the same output must be retained");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 500)
    public void unassignedProcessingRouteUsesTerminalBuffer(GameTestHelper helper) {
        var terminal = setup(helper);

        helper.startSequence()
            .thenWaitUntil(() -> check(terminal.online(), "Terminal must be online"))
            .thenExecute(() -> {
                check(terminal.createRecipeRouteGroup("Unrelated route") != null,
                    "Routing must be configured");
                terminal.setTarget(BAKED_POTATO);
                terminal.toggleProcessing();
                terminal.setAutoUpload(true);
                terminal.inventory().setStackInSlot(0, AEItems.BLANK_PATTERN.stack(2));
                terminal.analyze();
            })
            .thenWaitUntil(() -> check(terminal.state() == AutoPatternTerminalPart.State.WAITING_SELECTION,
                "Cooking route requires a selection: " + terminal.state() + " " + terminal.error()))
            .thenExecute(() -> terminal.chooseRecipe(BAKED_POTATO,
                terminal.recipeOptions(BAKED_POTATO).getFirst().id()))
            .thenWaitUntil(() -> check(terminal.state() == AutoPatternTerminalPart.State.READY,
                "Unassigned processing plan must be ready: " + terminal.state() + " " + terminal.error()))
            .thenExecute(() -> check(terminal.routeStatus().state()
                    == com.likeazusa2.aeautopatterns.logic.RecipeRouteGroups.RouteState.UNASSIGNED,
                "Recipe must have no authoritative route group"))
            .thenExecute(terminal::startOrResume)
            .thenWaitUntil(() -> check(terminal.state() == AutoPatternTerminalPart.State.COMPLETE,
                "Unassigned processing pattern must be buffered: " + terminal.state() + " " + terminal.error()))
            .thenExecute(() -> {
                check(terminal.inventory().getStackInSlot(0).getCount() == 1,
                    "Unassigned route fallback must consume exactly one blank");
                check(AEItems.PROCESSING_PATTERN.is(terminal.inventory().getStackInSlot(1)),
                    "Unassigned processing pattern must be retained in the output buffer");
                check(terminal.uploadStatus().contains("output buffer"),
                    "Unassigned route fallback must report local buffering");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 500)
    public void routeGroupWithoutProvidersUsesTerminalBuffer(GameTestHelper helper) {
        var terminal = setup(helper);

        helper.startSequence()
            .thenWaitUntil(() -> check(terminal.online(), "Terminal must be online"))
            .thenExecute(() -> {
                terminal.setTarget(BAKED_POTATO);
                terminal.toggleProcessing();
                terminal.setAutoUpload(true);
                terminal.inventory().setStackInSlot(0, AEItems.BLANK_PATTERN.stack(2));
                terminal.analyze();
            })
            .thenWaitUntil(() -> check(terminal.state() == AutoPatternTerminalPart.State.WAITING_SELECTION,
                "Cooking route requires a selection: " + terminal.state() + " " + terminal.error()))
            .thenExecute(() -> {
                var choice = terminal.recipeOptions(BAKED_POTATO).getFirst();
                var group = terminal.createRecipeRouteGroup("Empty route");
                check(group != null, "Route group must be created");
                check(terminal.addRecipesToRouteGroup(group, List.of(ResourceLocation.parse(choice.id()))),
                    "Selected recipe must bind to the route group");
                terminal.chooseRecipe(BAKED_POTATO, choice.id());
            })
            .thenWaitUntil(() -> check(terminal.state() == AutoPatternTerminalPart.State.READY,
                "Empty route group processing plan must be ready: " + terminal.state() + " " + terminal.error()))
            .thenExecute(() -> check(terminal.routeStatus().state()
                    == com.likeazusa2.aeautopatterns.logic.RecipeRouteGroups.RouteState.NO_PROVIDERS,
                "Route group must have no eligible provider"))
            .thenExecute(terminal::startOrResume)
            .thenWaitUntil(() -> check(terminal.state() == AutoPatternTerminalPart.State.COMPLETE,
                "Providerless route group pattern must be buffered: " + terminal.state() + " " + terminal.error()))
            .thenExecute(() -> {
                check(terminal.inventory().getStackInSlot(0).getCount() == 1,
                    "Providerless route group fallback must consume exactly one blank");
                check(AEItems.PROCESSING_PATTERN.is(terminal.inventory().getStackInSlot(1)),
                    "Providerless route group processing pattern must be retained in the output buffer");
                check(terminal.uploadStatus().contains("output buffer"),
                    "Providerless route group fallback must report local buffering");
            })
            .thenSucceed();
    }

    private AutoPatternTerminalPart setup(GameTestHelper helper) {
        appeng.api.parts.PartHelper.setPart(helper.getLevel(), helper.absolutePos(TERMINAL), null, null,
            AEParts.GLASS_CABLE.item(appeng.api.util.AEColor.TRANSPARENT));
        helper.setBlock(TERMINAL.below(), AEBlocks.CREATIVE_ENERGY_CELL.block());
        return appeng.api.parts.PartHelper.setPart(helper.getLevel(), helper.absolutePos(TERMINAL),
            net.minecraft.core.Direction.NORTH, null, ModContent.TERMINAL.get());
    }

    private static void routeSelectedRecipe(AutoPatternTerminalPart terminal, String providerId) {
        var choice = terminal.recipeOptions(BAKED_POTATO).getFirst();
        var group = terminal.createRecipeRouteGroup("Routed cooking");
        check(group != null, "Route group must be created");
        check(terminal.addRecipesToRouteGroup(group, List.of(ResourceLocation.parse(choice.id()))),
            "Selected recipe must bind to the route group");
        check(terminal.toggleRouteGroupProvider(group, providerId),
            "Provider must bind to the route group");
        terminal.chooseRecipe(BAKED_POTATO, choice.id());
    }

    private static void fill(appeng.blockentity.crafting.PatternProviderBlockEntity provider,
                             ItemStack pattern) {
        var inventory = provider.getTerminalPatternInventory();
        for (int i = 0; i < inventory.size(); i++) {
            inventory.setItemDirect(i, pattern.copy());
        }
    }

    private static ItemStack firstPattern(appeng.blockentity.crafting.PatternProviderBlockEntity provider) {
        var inventory = provider.getTerminalPatternInventory();
        for (int i = 0; i < inventory.size(); i++) {
            if (!inventory.getStackInSlot(i).isEmpty()) return inventory.getStackInSlot(i).copy();
        }
        throw new GameTestAssertException("Expected provider to contain a pattern");
    }

    private static int patternCount(appeng.blockentity.crafting.PatternProviderBlockEntity provider) {
        var inventory = provider.getTerminalPatternInventory();
        int count = 0;
        for (int i = 0; i < inventory.size(); i++) {
            if (!inventory.getStackInSlot(i).isEmpty()) count++;
        }
        return count;
    }

    private static boolean allSlotsMatch(appeng.blockentity.crafting.PatternProviderBlockEntity provider,
                                         ItemStack expected) {
        var inventory = provider.getTerminalPatternInventory();
        for (int i = 0; i < inventory.size(); i++) {
            var stack = inventory.getStackInSlot(i);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, expected)) return false;
        }
        return true;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
