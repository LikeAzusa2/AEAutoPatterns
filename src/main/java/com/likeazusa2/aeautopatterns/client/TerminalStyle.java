package com.likeazusa2.aeautopatterns.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Pixel bevels and palette matching the classic AE terminal panels. */
final class TerminalStyle {
    static final int PANEL=0xffccccd6, WELL=0xff999db3, SLOT=0xffaeb2c7;
    static final int LIGHT=0xfff3f3ff, SHADOW=0xff70748c, EDGE=0xff45465b;
    static final int TEXT=0xff38394d, MUTED=0xff53566e;
    static void bevel(GuiGraphics g,int x,int y,int width,int height,int fill,boolean inset) {
        g.fill(x,y,x+width,y+height,fill);
        int top=inset?SHADOW:LIGHT, bottom=inset?LIGHT:SHADOW;
        g.fill(x,y,x+width,y+1,top);g.fill(x,y,x+1,y+height,top);
        g.fill(x,y+height-1,x+width,y+height,bottom);g.fill(x+width-1,y,x+width,y+height,bottom);
    }
    static final class TerminalButton extends Button {
        TerminalButton(int x,int y,int width,int height,Component label,OnPress press) {
            super(x,y,width,height,label,press,DEFAULT_NARRATION);
        }
        @Override protected void renderWidget(GuiGraphics g,int mx,int my,float partial) {
            g.fill(getX(),getY(),getX()+width,getY()+height,EDGE);
            bevel(g,getX()+1,getY()+1,width-2,height-2,
                !active?0xffb4b5c1:isHoveredOrFocused()?0xffbcc5dd:WELL,false);
            g.fill(getX()+3,getY()+3,getX()+width-3,getY()+4,0xffc8cbdd);
            var font=Minecraft.getInstance().font;
            var label=font.plainSubstrByWidth(getMessage().getString(),width-6);
            g.drawString(font,label,getX()+(width-font.width(label))/2,getY()+(height-8)/2,
                active?TEXT:0xff77798d,false);
        }
    }
}
