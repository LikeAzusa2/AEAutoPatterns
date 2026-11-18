package com.likeazusa2.aeautopatterns.integration;

import net.minecraft.world.level.Level;

/** Loaded-but-unverified optional mods report machine outputs without encoding guesses. */
public class OptionalModProvider implements RecipeAdapterProvider {
    private final String modId, adapterId;
    private final int priority;
    public OptionalModProvider(String modId,String adapterId,int priority){this.modId=modId;this.adapterId=adapterId;this.priority=priority;}
    @Deprecated
    public OptionalModProvider(String modId,String ignoredPackagePrefix,String adapterId,int priority){
        this(modId,adapterId,priority);
    }
    @Override public String adapterId(){return adapterId;}
    @Override public int priority(){return priority;}
    @Override public RecipeAdapter create(Level level,RecipeAdapter.Settings settings){
        if(!settings.allowProcessing()||!loaded(modId))return null;
        return new UnverifiedModRecipeAdapter(modId,adapterId,priority,level);
    }
    private static boolean loaded(String id){
        try { var list=Class.forName("net.neoforged.fml.ModList").getMethod("get").invoke(null);return (boolean)list.getClass().getMethod("isLoaded",String.class).invoke(list,id); }
        catch(Exception e){return false;}
    }
}
