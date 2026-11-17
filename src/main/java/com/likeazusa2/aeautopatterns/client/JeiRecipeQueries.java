package com.likeazusa2.aeautopatterns.client;

import java.util.*;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import com.likeazusa2.aeautopatterns.menu.AutoPatternMenu;
import com.likeazusa2.aeautopatterns.network.TerminalPackets;

/** Client-only JEI lookup. Results are recipe IDs; the server resolves and validates every candidate. */
public final class JeiRecipeQueries {
    private static volatile IJeiRuntime runtime;
    private static List<RecipeType<?>> types=List.of();
    private JeiRecipeQueries() {}

    public static void setRuntime(IJeiRuntime value) { runtime=value;types=value==null?List.of():value.getJeiHelpers().getAllRecipeTypes().toList(); }
    public static boolean available() { return runtime!=null; }

    @SuppressWarnings({"rawtypes","unchecked"})
    public static List<String> lookup(net.minecraft.nbt.CompoundTag keyTag,Player player) {
        var level=Minecraft.getInstance().level;
        IJeiRuntime jei=runtime;
        var key=AEKey.fromTagGeneric(player.registryAccess(),keyTag);
        if(level==null||jei==null||!(key instanceof AEItemKey item)) {
            return List.of();
        }
        var typed=jei.getIngredientManager().createTypedIngredient(VanillaTypes.ITEM_STACK,item.toStack(),false);
        if(typed.isEmpty()) {
            return List.of();
        }
        IFocus<?> focus=jei.getJeiHelpers().getFocusFactory().createFocus(RecipeIngredientRole.OUTPUT,typed.get());
        var ids=new LinkedHashSet<String>();
        for(RecipeType<?> type:types) {
            if(ids.size()>=256)break;
            try { collect(jei,type,focus,ids); } catch(RuntimeException ignored) { }
        }
        return List.copyOf(ids);
    }
    public static void answer(TerminalPackets.JeiQuery query,Player player) {
        if(player.containerMenu instanceof AutoPatternMenu menu&&menu.containerId==query.container())
            PacketDistributor.sendToServer(new TerminalPackets.JeiCandidates(query.container(),query.revision(),query.key(),lookup(query.key(),player)));
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private static void collect(IJeiRuntime jei,RecipeType type,IFocus<?> focus,Set<String> ids) {
        var manager=jei.getRecipeManager();var category=manager.getRecipeCategory(type);
        manager.createRecipeLookup(type).limitFocus(List.of(focus)).get().limit(256).forEach(recipe->{
            ResourceLocation id=category.getRegistryName(recipe);
            if(id!=null&&ids.size()<256)ids.add(id.toString());
        });
    }
}
