package com.likeazusa2.aeautopatterns.integration;

import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;

/** Loads third-party integrations while always retaining the built-in adapter. */
public final class AdapterRegistry {
    private AdapterRegistry() {}
    public static RecipeAdapter create(Level level, RecipeAdapter.Settings settings) {
        var adapters = new ArrayList<RecipeAdapter>();
        adapters.add(SharedCraftingIndex.get(level,settings));
        var loader = ServiceLoader.load(RecipeAdapterProvider.class);
        for (var provider : loader) {
            try {
                var adapter = provider.create(level, settings);
                if (adapter != null) adapters.add(adapter);
            } catch (RuntimeException ignored) { /* one optional integration must not break analysis */ }
        }
        return new CompositeRecipeAdapter(adapters);
    }
    public static List<DatapackAdapterSpec> declarations(Level level) {
        return level instanceof ServerLevel server ? DatapackAdapterCatalog.load(server) : List.of();
    }
}
