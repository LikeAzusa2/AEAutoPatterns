package com.likeazusa2.aeautopatterns.client;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import com.likeazusa2.aeautopatterns.AEAutoPatterns;
import com.likeazusa2.aeautopatterns.ModContent;
@EventBusSubscriber(modid=AEAutoPatterns.ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class ClientEvents {
    @SubscribeEvent public static void keys(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent e) { e.register(TerminalGuide.OPEN); }
    @SubscribeEvent public static void itemColors(net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.Item e) {
        e.register((stack,tint)->0xFF000000 | appeng.api.util.AEColor.TRANSPARENT.getVariantByTintIndex(tint),ModContent.TERMINAL.get());
    }
    @SubscribeEvent public static void screens(RegisterMenuScreensEvent e) { e.register(ModContent.TERMINAL_MENU.get(),AutoPatternScreen::new); }
}
