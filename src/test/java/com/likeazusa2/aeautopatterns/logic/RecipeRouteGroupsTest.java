package com.likeazusa2.aeautopatterns.logic;

import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeRouteGroupsTest {
    private static final ResourceLocation IRON = id("test:iron");
    private static final ResourceLocation COPPER = id("test:copper");
    private static final ResourceLocation GOLD = id("test:gold");

    @Test void groupsHaveStableUniqueTokensAndKeepRecipeOwnershipUnique() {
        var routes = new RecipeRouteGroups();
        var first = routes.create("Smelting A");
        var second = routes.create("Smelting B");
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first, UUID.fromString(first).toString());
        assertNotEquals(first, second);
        assertTrue(routes.addRecipes(first, List.of(IRON, COPPER)));
        assertFalse(routes.addRecipes(second, List.of(COPPER, GOLD)));
        assertEquals(List.of(IRON, COPPER), routes.group(first).orElseThrow().recipeIds());
        assertEquals(List.of(), routes.group(second).orElseThrow().recipeIds());
        assertTrue(routes.addRecipes(second, List.of(GOLD)));
        assertEquals(first, routes.groupForRecipe(COPPER).orElseThrow().groupId());
    }

    @Test void explicitActivationSurvivesRemovingAllGroupsAndProviderConfiguration() {
        var routes = new RecipeRouteGroups();
        assertFalse(routes.routingConfigured());
        var id = routes.create("  Furnace line  ");
        assertTrue(routes.routingConfigured());
        assertEquals("Furnace line", routes.group(id).orElseThrow().name());
        assertTrue(routes.delete(id));
        assertTrue(routes.routingConfigured());
        assertTrue(routes.groups().isEmpty());
        var restored = new RecipeRouteGroups();
        restored.readNbt(routes.writeNbt());
        assertTrue(restored.routingConfigured());
        assertTrue(restored.groups().isEmpty());

        var legacy = new RecipeRouteGroups();
        var other = legacy.create("Buffer");
        assertFalse(legacy.toggleProvider(other, " "));
        assertTrue(legacy.toggleProvider(other, "provider:one"));
        assertTrue(legacy.delete(other));
        assertTrue(legacy.routingConfigured());
    }

    @Test void providerOrderAndExpandedToggleArePreserved() {
        var routes = new RecipeRouteGroups();
        var id = routes.create("Macerator");
        assertTrue(routes.addRecipes(id, List.of(IRON)));
        assertTrue(routes.toggleProviders(id, List.of("provider:a", "provider:b", "provider:a")));
        assertTrue(routes.group(id).orElseThrow().active());
        assertEquals(List.of("provider:a", "provider:b"), routes.group(id).orElseThrow().providerIds());
        assertTrue(routes.toggleProviders(id, List.of("provider:a", "provider:c")));
        assertEquals(List.of("provider:a", "provider:b", "provider:c"),
            routes.group(id).orElseThrow().providerIds());
        assertTrue(routes.toggleProviders(id, List.of("provider:a", "provider:b")));
        assertEquals(List.of("provider:c"), routes.group(id).orElseThrow().providerIds());
    }

    @Test void recipesCanMoveBetweenGroupsWithoutDuplication() {
        var routes = new RecipeRouteGroups();
        var first = routes.create("First");
        var second = routes.create("Second");
        assertTrue(routes.addRecipes(first, List.of(IRON, COPPER)));
        assertTrue(routes.moveRecipes(second, List.of(COPPER, GOLD)));
        assertEquals(List.of(IRON), routes.group(first).orElseThrow().recipeIds());
        assertEquals(List.of(COPPER, GOLD), routes.group(second).orElseThrow().recipeIds());
        assertEquals(second, routes.groupForRecipe(COPPER).orElseThrow().groupId());
    }

    @Test void renameAndDeleteRejectUnknownOrInvalidGroups() {
        var routes = new RecipeRouteGroups();
        var id = routes.create("Old");
        assertTrue(routes.rename(id, "New"));
        assertEquals("New", routes.group(id).orElseThrow().name());
        assertFalse(routes.rename(id, " "));
        assertFalse(routes.rename("missing", "Other"));
        assertFalse(routes.delete("missing"));
    }

    @Test void processingRecipeIdUsesAdaptedAuthorityThenHolderAndNeverNativeRecipes() {
        assertEquals(IRON, RecipeRouteGroups.processingRecipeId(IRON, COPPER, true));
        assertEquals(COPPER, RecipeRouteGroups.processingRecipeId(null, COPPER, true));
        assertNull(RecipeRouteGroups.processingRecipeId(IRON, COPPER, false));
        assertNull(RecipeRouteGroups.processingRecipeId(null, null, true));
    }

    @Test void nbtRoundTripRejectsDuplicateRecipeOwnershipButKeepsActivation() {
        var routes = new RecipeRouteGroups();
        var first = routes.create("First");
        var second = routes.create("Second");
        assertTrue(routes.addRecipes(first, List.of(IRON, COPPER)));
        assertTrue(routes.addRecipes(second, List.of(GOLD)));
        assertTrue(routes.toggleProviders(first, List.of("provider:b", "provider:a")));

        var restored = new RecipeRouteGroups();
        restored.readNbt(routes.writeNbt());
        assertTrue(restored.routingConfigured());
        assertEquals(routes.groups(), restored.groups());

        var conflicting = routes.writeNbt();
        var groups = conflicting.getList("groups", net.minecraft.nbt.Tag.TAG_COMPOUND);
        var duplicate = ((net.minecraft.nbt.CompoundTag) groups.get(1)).copy();
        duplicate.putString("groupId", UUID.randomUUID().toString());
        duplicate.put("recipes", new net.minecraft.nbt.ListTag());
        duplicate.getList("recipes", net.minecraft.nbt.Tag.TAG_STRING)
            .add(net.minecraft.nbt.StringTag.valueOf(IRON.toString()));
        groups.add(duplicate);
        restored.readNbt(conflicting);
        assertTrue(restored.groups().stream()
            .flatMap(group -> group.recipeIds().stream())
            .filter(IRON::equals)
            .count() == 1);
    }

    private static ResourceLocation id(String value) {
        return ResourceLocation.parse(value);
    }
}
