package com.likeazusa2.aeautopatterns;
import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import com.likeazusa2.aeautopatterns.network.TerminalPackets;
@Mod(AEAutoPatterns.ID)
public final class AEAutoPatterns {
    public static volatile int reloadEpoch;
    public static final String ID="aeautopatterns";
    public AEAutoPatterns(IEventBus bus, ModContainer container) {
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER,AnalysisConfig.SPEC);
        appeng.api.networking.GridServices.register(com.likeazusa2.aeautopatterns.integration.AnalysisGridService.class,com.likeazusa2.aeautopatterns.integration.AnalysisGridService.class);
        ModContent.ITEMS.register(bus);ModContent.MENUS.register(bus);
        bus.addListener(TerminalPackets::register);
        bus.addListener((net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey().equals(net.minecraft.world.item.CreativeModeTabs.FUNCTIONAL_BLOCKS)) {
                event.accept(ModContent.TERMINAL.get());
            }
        });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.OnDatapackSyncEvent e)->{if(e.getPlayer()==null){reloadEpoch++;com.likeazusa2.aeautopatterns.integration.SharedCraftingIndex.clear();}});
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.LevelEvent.Unload e)->{
            if(e.getLevel() instanceof net.minecraft.world.level.Level level)com.likeazusa2.aeautopatterns.integration.SharedCraftingIndex.unload(level);
        });
    }
}
