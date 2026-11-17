package com.likeazusa2.aeautopatterns.integration;

import net.minecraft.world.level.Level;

/** No-hard-dependency provider for a mod namespace/package. */
public class OptionalModProvider implements RecipeAdapterProvider {
    private final String modId, packagePrefix, adapterId;
    private final int priority;
    public OptionalModProvider(String modId,String packagePrefix,String adapterId,int priority){this.modId=modId;this.packagePrefix=packagePrefix;this.adapterId=adapterId;this.priority=priority;}
    @Override public String adapterId(){return adapterId;}
    @Override public int priority(){return priority;}
    @Override public RecipeAdapter create(Level level,RecipeAdapter.Settings settings){
        if(!loaded(modId))return null;
        return new ReflectiveModRecipeAdapter(adapterId,packagePrefix,priority,level);
    }
    private static boolean loaded(String id){
        try { var list=Class.forName("net.neoforged.fml.ModList").getMethod("get").invoke(null);return (boolean)list.getClass().getMethod("isLoaded",String.class).invoke(list,id); }
        catch(Exception e){return false;}
    }
}
