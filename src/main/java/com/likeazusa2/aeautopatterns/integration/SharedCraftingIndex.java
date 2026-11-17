package com.likeazusa2.aeautopatterns.integration;

import java.util.*;
import net.minecraft.world.level.Level;
import com.likeazusa2.aeautopatterns.AEAutoPatterns;

/** Built-in adapters only; optional providers keep their original per-terminal lifetime. */
public final class SharedCraftingIndex {
    private record Entry(int epoch,Map<RecipeAdapter.Settings,RecipeAdapter> adapters) {}
    private static final Map<Level,Entry> WORLDS=new IdentityHashMap<>();
    public static RecipeAdapter get(Level level,RecipeAdapter.Settings settings) {
        var entry=WORLDS.get(level);
        if(entry==null||entry.epoch()!=AEAutoPatterns.reloadEpoch){entry=new Entry(AEAutoPatterns.reloadEpoch,new HashMap<>());WORLDS.put(level,entry);}
        return entry.adapters().computeIfAbsent(settings,s->new CraftingRecipeAdapter(level,s.allowProcessing(),s.substituteItems(),s.substituteFluids()));
    }
    public static void unload(Level level){WORLDS.remove(level);}
    public static void clear(){WORLDS.clear();}
    private SharedCraftingIndex() {}
}
