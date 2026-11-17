package com.likeazusa2.aeautopatterns.client;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.constants.VanillaTypes;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;

@JeiPlugin
public final class AutoPatternJeiPlugin implements IModPlugin {
    @Override public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath("aeautopatterns","jei_plugin");
    }
    @Override public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(AutoPatternScreen.class, new IGhostIngredientHandler<>() {
            @Override public <I> java.util.List<Target<I>> getTargetsTyped(AutoPatternScreen screen,
                    mezz.jei.api.ingredients.ITypedIngredient<I> ingredient, boolean doStart) {
                if (ingredient.getIngredient(VanillaTypes.ITEM_STACK).isEmpty()) return java.util.List.of();
                ItemStack stack=ingredient.getIngredient(VanillaTypes.ITEM_STACK).orElse(ItemStack.EMPTY);
                return java.util.List.of(new Target<>() {
                    @Override public Rect2i getArea() { return screen.jeiTargetArea(); }
                    @Override public void accept(I ignored) { screen.acceptJeiItem(stack.copy()); }
                });
            }
            @Override public void onComplete() {}
        });
    }
    @Override public void onRuntimeAvailable(IJeiRuntime runtime) {
        JeiRecipeQueries.setRuntime(runtime);
        JeiCompat.setAvailable(true);
        var player=net.minecraft.client.Minecraft.getInstance().player;
        if(player!=null&&player.containerMenu instanceof com.likeazusa2.aeautopatterns.menu.AutoPatternMenu menu)
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.likeazusa2.aeautopatterns.network.TerminalPackets.Action(
                menu.containerId,menu.view.getInt("revision"),"jei_status",0,"true"));
    }
    @Override public void onRuntimeUnavailable() {
        JeiRecipeQueries.setRuntime(null);JeiCompat.setAvailable(false);
    }
}
