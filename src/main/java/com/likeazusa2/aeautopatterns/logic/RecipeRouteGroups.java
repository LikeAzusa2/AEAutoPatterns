package com.likeazusa2.aeautopatterns.logic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * Per-terminal recipe-to-provider routing. Recipes are stored once on their group;
 * providers refer to the exact terminal-visible ids and are never copied per recipe.
 */
public final class RecipeRouteGroups {
    public static final int MAX_GROUPS = 64;
    public static final int MAX_RECIPES_PER_GROUP = 512;
    public static final int MAX_PROVIDERS_PER_GROUP = 256;
    public static final int MAX_NAME_LENGTH = 80;

    public enum RouteState {
        LEGACY, BUFFERED, NOT_PROCESSING, UNASSIGNED, NO_PROVIDERS, READY
    }

    public record GroupView(String groupId, String name, List<ResourceLocation> recipeIds,
                            List<String> providerIds, boolean active) {
        public GroupView {
            recipeIds = recipeIds == null ? List.of() : List.copyOf(recipeIds);
            providerIds = providerIds == null ? List.of() : List.copyOf(providerIds);
        }
    }

    public record RouteStatus(boolean routingConfigured, boolean uploadEnabled, ResourceLocation recipeId,
                              String groupId, String groupName, RouteState state, String message,
                              List<String> providerIds) {
        public RouteStatus {
            providerIds = providerIds == null ? List.of() : List.copyOf(providerIds);
        }
    }

    private static final class Group {
        private final String id;
        private String name;
        private final LinkedHashSet<ResourceLocation> recipes = new LinkedHashSet<>();
        private final LinkedHashSet<String> providers = new LinkedHashSet<>();

        private Group(String id, String name) {
            this.id = id;
            this.name = name;
        }

        private GroupView view() {
            return new GroupView(id, name, List.copyOf(recipes), List.copyOf(providers),
                !recipes.isEmpty() && !providers.isEmpty());
        }
    }

    private final List<Group> groups = new ArrayList<>();
    private final Map<ResourceLocation, String> recipeOwners = new HashMap<>();
    private boolean routingConfigured;

    /** Explicit activation survives deletion of all groups. */
    public boolean routingConfigured() {
        return routingConfigured;
    }

    public List<GroupView> groups() {
        return groups.stream().map(Group::view).toList();
    }

    public Optional<GroupView> group(String groupId) {
        return find(groupId).map(Group::view);
    }

    public Optional<GroupView> groupForRecipe(ResourceLocation recipeId) {
        if (recipeId == null) return Optional.empty();
        var owner = recipeOwners.get(recipeId);
        return owner == null ? Optional.empty() : group(owner);
    }

    /** Returns null when the name is invalid or the model is full. */
    public String create(String name) {
        var normalized = normalizeName(name);
        if (normalized == null || groups.size() >= MAX_GROUPS) return null;
        var id = UUID.randomUUID().toString();
        groups.add(new Group(id, normalized));
        routingConfigured = true;
        return id;
    }

    public boolean rename(String groupId, String name) {
        var normalized = normalizeName(name);
        var group = find(groupId).orElse(null);
        if (group == null || normalized == null) return false;
        if (group.name.equals(normalized)) return true;
        group.name = normalized;
        return true;
    }

    /** Deleting the final group intentionally leaves routing configured. */
    public boolean delete(String groupId) {
        var group = find(groupId).orElse(null);
        if (group == null) return false;
        groups.remove(group);
        for (var recipe : group.recipes) recipeOwners.remove(recipe);
        return true;
    }

    /** Atomic: an ownership conflict leaves the model unchanged. */
    public boolean addRecipes(String groupId, Collection<ResourceLocation> recipeIds) {
        var group = find(groupId).orElse(null);
        var requested = distinctRecipes(recipeIds);
        if (group == null || requested.isEmpty()) return false;
        var added = new ArrayList<ResourceLocation>();
        for (var recipe : requested) {
            if (group.recipes.contains(recipe)) continue;
            var owner = recipeOwners.get(recipe);
            if (owner != null && !owner.equals(groupId)) return false;
            if (group.recipes.size() + added.size() >= MAX_RECIPES_PER_GROUP) return false;
            added.add(recipe);
        }
        group.recipes.addAll(added);
        for (var recipe : added) recipeOwners.put(recipe, groupId);
        routingConfigured = true;
        return true;
    }

    public boolean removeRecipes(String groupId, Collection<ResourceLocation> recipeIds) {
        var group = find(groupId).orElse(null);
        var requested = distinctRecipes(recipeIds);
        if (group == null || requested.isEmpty()) return false;
        var removed = false;
        for (var recipe : requested) {
            if (!group.recipes.remove(recipe)) continue;
            recipeOwners.remove(recipe);
            removed = true;
        }
        return removed;
    }

    /** Atomically moves recipes from their current owners into the target group. */
    public boolean moveRecipes(String targetGroupId, Collection<ResourceLocation> recipeIds) {
        var target = find(targetGroupId).orElse(null);
        var requested = distinctRecipes(recipeIds);
        if (target == null || requested.isEmpty()) return false;
        int newRecipes = 0;
        for (var recipe : requested) {
            var owner = recipeOwners.get(recipe);
            if (owner != null && !owner.equals(targetGroupId)) {
                if (find(owner).isEmpty()) return false;
            } else if (owner == null && !target.recipes.contains(recipe)) {
                newRecipes++;
            }
        }
        if (target.recipes.size() + newRecipes > MAX_RECIPES_PER_GROUP) return false;
        for (var recipe : requested) {
            var owner = recipeOwners.get(recipe);
            if (owner == null || owner.equals(targetGroupId)) continue;
            find(owner).ifPresent(group -> group.recipes.remove(recipe));
        }
        target.recipes.addAll(requested);
        for (var recipe : requested) recipeOwners.put(recipe, targetGroupId);
        routingConfigured = true;
        return true;
    }

    public boolean toggleProvider(String groupId, String providerId) {
        return providerId == null ? false : toggleProviders(groupId, List.of(providerId));
    }

    /**
     * Toggles an expanded provider set as one unit. New ids append in caller order.
     */
    public boolean toggleProviders(String groupId, Collection<String> providerIds) {
        var group = find(groupId).orElse(null);
        var requested = distinctProviders(providerIds);
        if (group == null || requested.isEmpty()) return false;
        if (group.providers.containsAll(requested)) {
            group.providers.removeAll(requested);
        } else {
            int missing = 0;
            for (var provider : requested) if (!group.providers.contains(provider)) missing++;
            if (group.providers.size() + missing > MAX_PROVIDERS_PER_GROUP) return false;
            group.providers.addAll(requested);
        }
        routingConfigured = true;
        return true;
    }

    public void readNbt(CompoundTag tag) {
        groups.clear();
        recipeOwners.clear();
        routingConfigured = tag != null && tag.getBoolean("configured");
        if (tag == null) return;
        for (var raw : tag.getList("groups", Tag.TAG_COMPOUND)) {
            if (groups.size() >= MAX_GROUPS) break;
            var stored = (CompoundTag) raw;
            var id = stableId(stored.getString("groupId"));
            var name = normalizeName(stored.getString("name"));
            if (id == null || name == null || find(id).isPresent()) continue;
            var group = new Group(id, name);
            for (var recipeRaw : stored.getList("recipes", Tag.TAG_STRING)) {
                if (group.recipes.size() >= MAX_RECIPES_PER_GROUP) break;
                var recipe = ResourceLocation.tryParse(recipeRaw.getAsString());
                if (recipe == null || recipeOwners.containsKey(recipe)) continue;
                group.recipes.add(recipe);
                recipeOwners.put(recipe, id);
            }
            for (var providerRaw : stored.getList("providers", Tag.TAG_STRING)) {
                if (group.providers.size() >= MAX_PROVIDERS_PER_GROUP) break;
                var provider = providerRaw.getAsString();
                if (!provider.isBlank()) group.providers.add(provider);
            }
            groups.add(group);
        }
        if (!groups.isEmpty()) routingConfigured = true;
    }

    public CompoundTag writeNbt() {
        var tag = new CompoundTag();
        tag.putBoolean("configured", routingConfigured);
        var list = new ListTag();
        for (var group : groups) {
            var stored = new CompoundTag();
            stored.putString("groupId", group.id);
            stored.putString("name", group.name);
            var recipes = new ListTag();
            for (var recipe : group.recipes) recipes.add(net.minecraft.nbt.StringTag.valueOf(recipe.toString()));
            stored.put("recipes", recipes);
            var providers = new ListTag();
            for (var provider : group.providers) providers.add(net.minecraft.nbt.StringTag.valueOf(provider));
            stored.put("providers", providers);
            list.add(stored);
        }
        tag.put("groups", list);
        return tag;
    }

    public static ResourceLocation processingRecipeId(ResourceLocation adaptedRecipeId,
                                                      ResourceLocation holderRecipeId, boolean processing) {
        if (!processing) return null;
        return adaptedRecipeId != null ? adaptedRecipeId : holderRecipeId;
    }

    private Optional<Group> find(String groupId) {
        if (groupId == null) return Optional.empty();
        return groups.stream().filter(group -> group.id.equals(groupId)).findFirst();
    }

    private static String normalizeName(String name) {
        if (name == null) return null;
        var normalized = name.strip();
        return normalized.isEmpty() || normalized.length() > MAX_NAME_LENGTH ? null : normalized;
    }

    private static String stableId(String value) {
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static List<ResourceLocation> distinctRecipes(Collection<ResourceLocation> recipeIds) {
        if (recipeIds == null) return List.of();
        return List.copyOf(recipeIds.stream().filter(java.util.Objects::nonNull)
            .limit(MAX_RECIPES_PER_GROUP + 1L)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
    }

    private static List<String> distinctProviders(Collection<String> providerIds) {
        if (providerIds == null) return List.of();
        return List.copyOf(providerIds.stream().filter(id -> id != null && !id.isBlank())
            .limit(MAX_PROVIDERS_PER_GROUP + 1L)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
    }
}
