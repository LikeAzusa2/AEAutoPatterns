package com.likeazusa2.aeautopatterns.client;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import com.likeazusa2.aeautopatterns.menu.AutoPatternMenu;
import static com.likeazusa2.aeautopatterns.client.TerminalStyle.*;

/** A separate provider page over the terminal menu; never exposes inventory slots. */
final class ProviderSelectionView {
    private final Minecraft mc=Minecraft.getInstance();
    private final AutoPatternMenu menu;
    private final RecipeSelectionView.Action action;
    private final Runnable back;
    private final List<TerminalButton> buttons=new ArrayList<>();
    private EditBox search;
    private RecipeRouteView routes;
    private int x,y,width,height;
    private String detailId="";
    private CompoundTag detail=new CompoundTag();
    ProviderSelectionView(AutoPatternMenu menu,RecipeSelectionView.Action action,Runnable back,int width,int height) {
        this.menu=menu;this.action=action;this.back=back;resize(width,height);
    }
    private Component tr(String key,Object... args){return Component.translatable("gui.aeautopatterns.providers."+key,args);}
    private String label(CompoundTag row,int width) {
        String suffix=row.getInt("count")>1?" ×"+row.getInt("count"):"";
        return mc.font.plainSubstrByWidth(row.getString("name"),Math.max(0,width-mc.font.width(suffix)))+suffix;
    }
    private Component location(CompoundTag row) {
        return row.getInt("count")>1?tr("group",row.getInt("count")):Component.literal(row.getString("location"));
    }
    void resize(int width,int height) {
        String query=search==null?"":search.getValue();this.width=width;this.height=height;
        x=(width-360)/2;y=(height-252)/2;
        search=new EditBox(mc.font,x+12,y+30,336,16,tr("search"));
        search.setBordered(false);search.setTextColor(TEXT);search.setTextColorUneditable(MUTED);
        search.setMaxLength(80);search.setHint(tr("search"));search.setValue(query);
        search.setResponder(value->action.send("provider_search",0,value));
        if(routes!=null)routes.resize(width,height);
    }
    private TerminalButton button(int bx,int by,int w,int h,Component text,Runnable press) {
        var widget=new TerminalButton(bx,by,w,h,text,b->press.run());
        buttons.add(widget);
        return widget;
    }
    void render(GuiGraphics g,int mx,int my,float partial) {
        if(routes!=null){routes.render(g,mx,my,partial);return;}
        g.fill(0,0,width,height,0xb0000000);bevel(g,x,y,360,252,PANEL,false);
        g.drawString(mc.font,tr("title"),x+12,y+11,TEXT,false);
        String selected=tr("selected",menu.view.getInt("selectedProviderCount")).getString();
        g.drawString(mc.font,selected,x+348-mc.font.width(selected),y+11,MUTED,false);
        bevel(g,x+12,y+30,336,16,SLOT,true);search.render(g,mx,my,partial);buttons.clear();
        button(x+12,y+53,336,18,tr(menu.view.getBoolean("autoContinueProviders")?"continue.on":"continue.off"),
            ()->action.send("provider_continue",0,Boolean.toString(!menu.view.getBoolean("autoContinueProviders"))));
        var rows=menu.view.getList("providers",Tag.TAG_COMPOUND);
        if(!detailId.isEmpty()) {
            CompoundTag updated=null;
            for(var entry:rows)if(((CompoundTag)entry).getString("id").equals(detailId))updated=(CompoundTag)entry;
            if(updated==null)detailId="";else detail=updated;
        }
        CompoundTag hover=null;
        for(int i=0;i<rows.size();i++) {
            var row=rows.getCompound(i);int cx=x+12+i%2*174,cy=y+79+i/2*45;
            boolean highlighted=mx>=cx&&mx<cx+162&&my>=cy&&my<cy+39;
            bevel(g,cx,cy,162,39,row.getBoolean("selected")?0xffbecbce:highlighted?0xffc2c6d8:SLOT,true);
            var icon=ItemStack.parseOptional(mc.level.registryAccess(),row.getCompound("icon"));
            g.pose().pushPose();g.pose().translate(cx+17,cy+8,0);g.pose().scale(1.5f,1.5f,1);
            g.renderItem(icon,0,0);g.pose().popPose();
            g.drawString(mc.font,label(row,112),cx+45,cy+5,TEXT,false);
            String space=tr("space",row.getInt("free"),row.getInt("slots")).getString();
            g.drawString(mc.font,mc.font.plainSubstrByWidth(space,112),cx+45,cy+16,MUTED,false);
            String mode=tr(row.getBoolean("craftingMixed")?"crafting.mixed.short":row.getBoolean("craftingOnly")?"crafting.short":"any.short").getString();
            if(row.getInt("order")>0)mode="#"+row.getInt("order")+" · "+mode;
            g.drawString(mc.font,mc.font.plainSubstrByWidth(mode,112),cx+45,cy+27,row.getBoolean("craftingOnly")?0xff245b47:MUTED,false);
            String id=row.getString("id");
            button(cx+3,cy+13,16,12,Component.empty(),()->action.send("provider_toggle",0,id));
            if(highlighted)hover=row;
        }
        if(rows.isEmpty())g.drawCenteredString(mc.font,tr("empty"),x+180,y+133,MUTED);
        int count=menu.view.getInt("providerCount"),offset=menu.view.getInt("providerOffset");
        int pages=Math.max(1,(count+5)/6);
        button(x+12,y+225,66,17,tr("back"),back);
        button(x+105,y+225,28,17,Component.literal("←"),()->action.send("provider_page",-1,""));
        String page=(offset/6+1)+" / "+pages;
        g.drawCenteredString(mc.font,page,x+180,y+230,TEXT);
        button(x+227,y+225,28,17,Component.literal("→"),()->action.send("provider_page",1,""));
        var routeButton=button(x+268,y+225,80,17,Component.translatable("gui.aeautopatterns.routes.open"),this::openRoutes);
        routeButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
            Component.translatable("gui.aeautopatterns.routes.open.help")));
        if(!detailId.isEmpty()) {
            renderDetails(g,mx,my,partial);return;
        }
        for(var button:buttons)button.render(g,mx,my,partial);
        for(int i=0;i<rows.size();i++) {
            var row=rows.getCompound(i);
            if(!row.getBoolean("selected")&&!row.getBoolean("selectedMixed"))continue;
            int cx=x+12+i%2*174,cy=y+79+i/2*45;
            g.fill(cx+5,cy+14,cx+14,cy+23,0xffe8edf2);
            if(row.getBoolean("selectedMixed")) {
                g.fill(cx+7,cy+18,cx+13,cy+20,0xff245b47);
                continue;
            }
            g.fill(cx+7,cy+18,cx+9,cy+20,0xff245b47);
            g.fill(cx+9,cy+20,cx+11,cy+22,0xff245b47);
            g.fill(cx+10,cy+17,cx+12,cy+19,0xff245b47);
            g.fill(cx+12,cy+15,cx+14,cy+17,0xff245b47);
        }
        if(hover!=null)g.renderTooltip(mc.font,List.of(Component.literal(hover.getString("name")),
            location(hover),tr("details.help")),Optional.empty(),mx,my);
        else if(mx>=x+12&&mx<x+348&&my>=y+53&&my<y+71)
            tooltip(g,List.of(tr("continue.help")),mx,my);
    }
    private void renderDetails(GuiGraphics g,int mx,int my,float partial) {
        int dx=x+39,dy=y+56;
        g.pose().pushPose();g.pose().translate(0,0,400);g.fill(0,0,width,height,0xa0000000);
        bevel(g,dx,dy,282,150,PANEL,false);buttons.clear();
        var icon=ItemStack.parseOptional(mc.level.registryAccess(),detail.getCompound("icon"));
        g.renderItem(icon,dx+12,dy+12);
        g.drawString(mc.font,label(detail,232),dx+36,dy+15,TEXT,false);
        g.drawString(mc.font,mc.font.plainSubstrByWidth(location(detail).getString(),258),dx+12,dy+36,MUTED,false);
        g.drawString(mc.font,tr("space",detail.getInt("free"),detail.getInt("slots")),dx+12,dy+50,TEXT,false);
        button(dx+12,dy+70,258,20,tr(detail.getBoolean("craftingMixed")?"crafting.mixed":detail.getBoolean("craftingOnly")?"crafting.on":"crafting.off"),
            ()->action.send("provider_crafting",0,detailId));
        button(dx+12,dy+101,123,20,tr(detail.getInt("count")>1?"single.group":"single"),()->{action.send("provider_single",0,detailId);detailId="";});
        button(dx+147,dy+101,123,20,tr(detail.getBoolean("selectedMixed")?"select.group":detail.getBoolean("selected")?"deselect":"select"),
            ()->action.send("provider_toggle",0,detailId));
        button(dx+94,dy+128,94,16,tr("close"),()->detailId="");
        for(var button:buttons)button.render(g,mx,my,partial);
        if(mx>=dx+12&&mx<dx+270&&my>=dy+70&&my<dy+90)tooltip(g,List.of(tr("crafting.help")),mx,my);
        g.pose().popPose();
    }
    private void tooltip(GuiGraphics g,List<Component> lines,int mx,int my) {
        var text=new ArrayList<net.minecraft.util.FormattedCharSequence>();
        for(var line:lines)text.addAll(mc.font.split(line,Math.min(320,width-24)));
        g.renderTooltip(mc.font,text,mx,my);
    }
    boolean click(double mx,double my,int button) {
        if(routes!=null)return routes.click(mx,my,button);
        for(var widget:buttons)if(widget.mouseClicked(mx,my,button))return true;
        if(!detailId.isEmpty())return true;
        if(mx>=x+12&&mx<x+348&&my>=y+30&&my<y+46) {
            search.setFocused(true);search.mouseClicked(mx,my,button);return true;
        }
        search.setFocused(false);
        if(button==0) {
            var rows=menu.view.getList("providers",Tag.TAG_COMPOUND);
            for(int i=0;i<rows.size();i++) {
                int cx=x+12+i%2*174,cy=y+79+i/2*45;
                if(mx>=cx&&mx<cx+162&&my>=cy&&my<cy+39) {
                    detail=rows.getCompound(i);detailId=detail.getString("id");search.setFocused(false);break;
                }
            }
        }
        return true;
    }
    boolean scroll(double amount) {
        if(routes!=null)return routes.scroll(amount);
        if(detailId.isEmpty()&&amount!=0)action.send("provider_page",amount>0?-1:1,"");return true;
    }
    boolean key(int key,int scan,int mods) {
        if(routes!=null)return routes.key(key,scan,mods);
        if(key==256){if(detailId.isEmpty())back.run();else detailId="";return true;}
        return detailId.isEmpty()&&(search.keyPressed(key,scan,mods)||search.canConsumeInput());
    }
    boolean character(char character,int mods){
        if(routes!=null)return routes.character(character,mods);
        return detailId.isEmpty()&&search.charTyped(character,mods);
    }
    private void openRoutes() {
        action.send("route_open",0,"");
        routes=new RecipeRouteView(menu,action,this::closeRoutes,width,height);
    }
    private void closeRoutes() {
        if(routes==null)return;
        routes=null;
        action.send("route_close",0,"");
    }
}
