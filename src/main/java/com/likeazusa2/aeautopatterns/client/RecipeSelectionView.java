package com.likeazusa2.aeautopatterns.client;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import com.likeazusa2.aeautopatterns.menu.AutoPatternMenu;
import static com.likeazusa2.aeautopatterns.client.TerminalStyle.*;

/** Server-authoritative, modal native recipe preview. Paging never commits a route. */
final class RecipeSelectionView {
    interface Action { void send(String action,int index,String value); }
    private final AutoPatternMenu menu;
    private final Action action;
    private final Minecraft mc=Minecraft.getInstance();
    private final List<TerminalButton> buttons=new ArrayList<>();
    RecipeSelectionView(AutoPatternMenu menu,Action action) {this.menu=menu;this.action=action;}
    boolean open() {return menu.view.contains("recipePreview");}
    void close() {action.send("picker_close",0,"");menu.view.remove("recipePreview");}
    void render(GuiGraphics g,int mx,int my,float partial,int width,int height) {
        var preview=menu.view.getCompound("recipePreview");
        int x=(width-264)/2,y=(height-186)/2;
        g.pose().pushPose();g.pose().translate(0,0,400);
        g.fill(0,0,width,height,0xb0000000);bevel(g,x,y,264,186,PANEL,false);
        g.drawString(mc.font,Component.translatable("gui.aeautopatterns.picker.title"),x+10,y+10,TEXT,false);
        g.drawString(mc.font,mc.font.plainSubstrByWidth(preview.getString("name"),242),x+10,y+24,TEXT,false);
        ItemStack hover=ItemStack.EMPTY;
        for(int slot=0;slot<9;slot++)bevel(g,x+52+slot%3*18,y+44+slot/3*18,18,18,SLOT,true);
        long frame=mc.level.getGameTime()/20;
        for(var entry:preview.getList("slots",Tag.TAG_COMPOUND)) {
            var data=(CompoundTag)entry;int slot=data.getInt("slot");
            var variants=data.getList("variants",Tag.TAG_COMPOUND);if(variants.isEmpty())continue;
            var stack=ItemStack.parseOptional(mc.level.registryAccess(),variants.getCompound((int)(frame%variants.size())));
            int sx=x+53+slot%3*18,sy=y+45+slot/3*18;g.renderItem(stack,sx,sy);
            if(mx>=sx&&mx<sx+16&&my>=sy&&my<sy+16)hover=stack;
        }
        g.drawString(mc.font,"→",x+122,y+67,TEXT,false);bevel(g,x+156,y+62,18,18,SLOT,true);
        var output=ItemStack.parseOptional(mc.level.registryAccess(),preview.getCompound("output"));
        g.renderItem(output,x+157,y+63);g.renderItemDecorations(mc.font,output,x+157,y+63);
        if(mx>=x+157&&mx<x+173&&my>=y+63&&my<y+79)hover=output;
        g.drawString(mc.font,mc.font.plainSubstrByWidth(preview.getString("id"),244),x+10,y+105,MUTED,false);
        String page=preview.getInt("page")+" / "+preview.getInt("count");
        g.drawString(mc.font,page,x+132-mc.font.width(page)/2,y+127,TEXT,false);
        buttons.clear();
        buttons.add(new TerminalButton(x+52,y+120,24,20,Component.literal("←"),b->action.send("picker_page",-1,"")));
        buttons.add(new TerminalButton(x+188,y+120,24,20,Component.literal("→"),b->action.send("picker_page",1,"")));
        buttons.add(new TerminalButton(x+20,y+154,106,20,Component.translatable("gui.aeautopatterns.picker.confirm"),b->action.send("picker_confirm",0,preview.getString("id"))));
        buttons.add(new TerminalButton(x+138,y+154,106,20,Component.translatable("gui.aeautopatterns.picker.cancel"),b->close()));
        for(var b:buttons)b.render(g,mx,my,partial);
        if(!hover.isEmpty())g.renderTooltip(mc.font,hover,mx,my);
        else if(mx>=x+10&&mx<x+254&&my>=y+103&&my<y+116)g.renderTooltip(mc.font,Component.literal(preview.getString("id")),mx,my);
        g.pose().popPose();
    }
    boolean click(double x,double y,int button) {for(var b:buttons)if(b.mouseClicked(x,y,button))break;return true;}
}
