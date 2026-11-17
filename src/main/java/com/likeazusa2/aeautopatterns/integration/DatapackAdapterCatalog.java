package com.likeazusa2.aeautopatterns.integration;

import com.google.gson.JsonParseException;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;

/** Loads optional data-pack adapter declarations without inventing recipe semantics. */
public final class DatapackAdapterCatalog {
    private DatapackAdapterCatalog() {}
    public static List<DatapackAdapterSpec> load(ServerLevel level) {
        if (level.getServer() == null) return List.of();
        var resources = level.getServer().getResourceManager().listResources("aeautopatterns/adapter", p -> p.getPath().endsWith(".json"));
        var result = new ArrayList<DatapackAdapterSpec>();
        for (var entry : resources.entrySet()) {
            try {
                var json = net.minecraft.util.GsonHelper.parse(entry.getValue().openAsReader());
                var object = net.minecraft.util.GsonHelper.convertToJsonObject(json, entry.getKey().toString());
                var id = net.minecraft.util.GsonHelper.getAsString(object, "adapter_id", entry.getKey().getNamespace() + ":" + entry.getKey().getPath());
                var machine = ResourceLocation.parse(net.minecraft.util.GsonHelper.getAsString(object, "machine_type", id));
                var priority = net.minecraft.util.GsonHelper.getAsInt(object, "priority", 0);
                result.add(new DatapackAdapterSpec(id, machine, priority,
                    locations(object, "recipe_types"), strings(object, "input_paths"), strings(object, "output_paths"),
                    strings(object, "fluid_paths"), strings(object, "condition_paths")));
            } catch (Exception ignored) { /* invalid optional declarations are reported by diagnostics, never fatal */ }
        }
        result.sort(Comparator.comparingInt(DatapackAdapterSpec::priority).reversed().thenComparing(DatapackAdapterSpec::adapterId));
        return List.copyOf(result);
    }
    private static List<String> strings(com.google.gson.JsonObject object, String key) {
        if (!object.has(key)) return List.of();
        var result = new ArrayList<String>();
        for (var value : object.getAsJsonArray(key)) if (value.isJsonPrimitive()) result.add(value.getAsString());
        return List.copyOf(result);
    }
    private static List<ResourceLocation> locations(com.google.gson.JsonObject object, String key) {
        return strings(object, key).stream().map(ResourceLocation::parse).toList();
    }
}
