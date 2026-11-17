package com.likeazusa2.aeautopatterns.client;

import appeng.api.parts.IPartHost;
import appeng.items.tools.GuideItem;
import com.likeazusa2.aeautopatterns.AEAutoPatterns;
import com.likeazusa2.aeautopatterns.part.AutoPatternTerminalPart;
import com.mojang.blaze3d.platform.InputConstants;
import guideme.GuidesCommon;
import guideme.PageAnchor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/** World targeting complements GuideME's native item-tooltip hold-to-open shortcut. */
@EventBusSubscriber(modid=AEAutoPatterns.ID,value=Dist.CLIENT)
public final class TerminalGuide {
    public static final KeyMapping OPEN=new KeyMapping("key.aeautopatterns.terminal_guide",
        KeyConflictContext.IN_GAME,InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_G,"key.categories.aeautopatterns");
    private static final PageAnchor PAGE=PageAnchor.page(ResourceLocation.fromNamespaceAndPath(AEAutoPatterns.ID,"auto_pattern_terminal.md"));

    private TerminalGuide() {}

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var minecraft=Minecraft.getInstance();
        while(OPEN.consumeClick()) {
            if(minecraft.screen!=null||minecraft.player==null||minecraft.level==null)continue;
            if(minecraft.hitResult instanceof BlockHitResult hit
                &&minecraft.level.getBlockEntity(hit.getBlockPos()) instanceof IPartHost host
                &&host.selectPartWorld(hit.getLocation()).part instanceof AutoPatternTerminalPart) {
                GuidesCommon.openGuide(minecraft.player,GuideItem.GUIDE_ID,PAGE);
            }
        }
    }
}
