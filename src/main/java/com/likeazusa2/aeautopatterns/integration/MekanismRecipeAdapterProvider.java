package com.likeazusa2.aeautopatterns.integration;

import net.minecraft.world.level.Level;

/** Optional bridge enabled only when both Mekanism and Applied Mekanistics register chemical AE keys. */
public final class MekanismRecipeAdapterProvider implements RecipeAdapterProvider {
    @Override public String adapterId(){return "aeautopatterns:applied-mekanistics";}
    @Override public int priority(){return 60;}
    @Override public RecipeAdapter create(Level level,RecipeAdapter.Settings settings){
        if(!loaded("mekanism")||!loaded("appmek"))return null;
        return new MekanismChemicalRecipeAdapter(level);
    }
    private static boolean loaded(String id){
        try { var list=Class.forName("net.neoforged.fml.ModList").getMethod("get").invoke(null);return (boolean)list.getClass().getMethod("isLoaded",String.class).invoke(list,id); }
        catch(Exception e){return false;}
    }
}
