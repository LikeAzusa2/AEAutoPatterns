package com.likeazusa2.aeautopatterns;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.registries.*;
import java.util.function.Supplier;
import appeng.items.parts.PartItem;
import appeng.api.parts.PartModels;
import appeng.items.parts.PartModelsHelper;
import appeng.menu.implementations.MenuTypeBuilder;
import com.likeazusa2.aeautopatterns.part.AutoPatternTerminalPart;
import com.likeazusa2.aeautopatterns.menu.AutoPatternMenu;
public final class ModContent {
    public static final DeferredRegister.Items ITEMS=DeferredRegister.createItems(AEAutoPatterns.ID);
    public static final DeferredRegister<MenuType<?>> MENUS=DeferredRegister.create(Registries.MENU,AEAutoPatterns.ID);
    public static final DeferredItem<PartItem<AutoPatternTerminalPart>> TERMINAL=ITEMS.register("auto_pattern_terminal",
        ()->new PartItem<>(new Item.Properties().stacksTo(1),AutoPatternTerminalPart.class,AutoPatternTerminalPart::new));
    public static final Supplier<MenuType<AutoPatternMenu>> TERMINAL_MENU=MENUS.register("auto_pattern_terminal",
        ()->MenuTypeBuilder.<AutoPatternMenu,AutoPatternTerminalPart>create(AutoPatternMenu::new,AutoPatternTerminalPart.class)
            .buildUnregistered(ResourceLocation.fromNamespaceAndPath(AEAutoPatterns.ID,"auto_pattern_terminal")));
    static { PartModels.registerModels(PartModelsHelper.createModels(AutoPatternTerminalPart.class)); }
}
