package com.likeazusa2.aeautopatterns.client;

import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import com.likeazusa2.aeautopatterns.menu.AutoPatternMenu;
import static com.likeazusa2.aeautopatterns.client.TerminalStyle.*;

/** Separate visual view over the same server menu: no inventory slots or item input are exposed. */
final class DependencyTreeView {
    private final Minecraft mc=Minecraft.getInstance();
    private final AutoPatternMenu menu;
    private final BiConsumer<String,Integer> action;
    private final Runnable back,openIngredients;
    private final List<Button> buttons=new ArrayList<>();
    private final List<Button> floatingButtons=new ArrayList<>();
    private Button recipe,external,variant;
    private EditBox materialSearch;
    private List<CompoundTag> materials=List.of();
    private int listOffset,cachedCount=-1,canvasLeft;
    private int width,height,canvasRight,panelX,panelY,panelWidth,panelHeight,selected=-1,selectedMaterial=-1,revision=-1;
    private double panX=12,panY=12,zoom=1;
    private boolean dragging,centerPending=true;
    DependencyTreeView(AutoPatternMenu menu,BiConsumer<String,Integer> action,Runnable back,Runnable openIngredients,int width,int height) {
        this.menu=menu;this.action=action;this.back=back;this.openIngredients=openIngredients;resize(width,height);
    }
    private Component tr(String key) {return Component.translatable("gui.aeautopatterns."+key);}
    private Button button(int x,int y,int w,String key,Runnable click) {
        var b=new TerminalButton(x,y,w,16,tr(key),v->click.run());buttons.add(b);return b;
    }
    private Button floatingButton(String key,Runnable click) {
        var b=new Button(0,0,16,16,tr(key),v->click.run(),narration->narration.get()) {
            @Override protected void renderWidget(GuiGraphics g,int mx,int my,float partial) {
                bevel(g,getX(),getY(),getWidth(),getHeight(),!active?0xff222936:isHoveredOrFocused()?0xff4a5a76:0xff323e54,false);
                var text=mc.font.plainSubstrByWidth(getMessage().getString(),getWidth()-6);
                g.drawString(mc.font,text,getX()+(getWidth()-mc.font.width(text))/2,getY()+4,active?0xffeef0ff:0xff7e899e,false);
            }
        };
        floatingButtons.add(b);return b;
    }
    void resize(int width,int height) {
        this.width=width;this.height=height;canvasRight=width-12;buttons.clear();floatingButtons.clear();
        canvasLeft=20+Math.clamp(width/5,100,144);
        String query=materialSearch==null?"":materialSearch.getValue();
        materialSearch=new EditBox(mc.font,18,54,canvasLeft-32,16,tr("tree.material_search"));
        materialSearch.setMaxLength(80);materialSearch.setHint(tr("tree.material_search"));
        materialSearch.setResponder(value->{listOffset=0;cachedCount=-1;});materialSearch.setValue(query);
        resetView();
        button(width-180,12,80,"tree.reset",this::resetView);
        button(width-96,12,84,"tree.back",back);
        recipe=floatingButton("recipe",()->edit("recipe",0));
        external=floatingButton("external",()->edit("external",0));
        variant=floatingButton("variant",()->{if(canEdit())openIngredients.run();});
        positionPanel(canvasLeft+12,48);
    }
    private void edit(String name,int index) {if(canEdit())action.accept(name,index);}
    private boolean canEdit() {
        return menu.view.getBoolean("treeReady")&&menu.treeRevision==menu.view.getInt("revision")&&
            selectedMaterial>=0&&selectedMaterial==menu.view.getInt("selectedId");
    }
    private void resetView() {zoom=1;panY=12;centerPending=true;}
    private int nodeX(CompoundTag n) {return n.getInt("x");}
    private int nodeY(CompoundTag n) {return n.getInt("y");}
    private int color(String kind) {
        return switch(kind) {
            case "NETWORK","BUFFER","GENERATED" -> 0xff71c6a3;
            case "EXTERNAL" -> 0xffe0ba79;
            case "UNSUPPORTED","PENDING" -> 0xffe18b99;
            case "NEEDS_RECIPE" -> 0xffffc45c;
            default -> 0xffa9bafa;
        };
    }
    private CompoundTag hit(double mx,double my) {
        if(inPanel(mx,my)||mx<canvasLeft||mx>=canvasRight||my<36||my>=height-28)return null;
        double x=(mx-canvasLeft-panX)/zoom,y=(my-36-panY)/zoom;
        for(var n:menu.treeNodes)if(x>=nodeX(n)&&x<nodeX(n)+128&&y>=nodeY(n)&&y<nodeY(n)+34)return n;
        return null;
    }
    private void label(GuiGraphics g,String value,int x,int y,int max,int color) {
        g.drawString(mc.font,mc.font.plainSubstrByWidth(value,max),x,y,color,false);
    }
    private void wrapped(GuiGraphics g,String value,int y,int lines) {
        var split=mc.font.split(Component.literal(value),panelWidth-16);
        for(int i=0;i<Math.min(lines,split.size());i++)g.drawString(mc.font,split.get(i),panelX+8,panelY+y+i*10,0xffd3d7e8,false);
    }
    void render(GuiGraphics g,int mx,int my,float partial) {
        if(revision!=menu.treeRevision) {revision=menu.treeRevision;selected=-1;selectedMaterial=-1;listOffset=0;cachedCount=-1;resetView();}
        // Deliberately bypass the container render, including its background blur and slots.
        g.fill(0,0,width,height,0x9230394c);
        g.fill(canvasLeft,36,canvasRight,height-28,0x8c101724);
        label(g,tr("tree.title").getString(),16,15,width-208,0xffeef0ff);
        boolean current=menu.treeRevision==menu.view.getInt("revision")&&menu.view.getBoolean("treeReady");
        if(centerPending&&!menu.treeNodes.isEmpty()) {
            panX=(canvasRight-canvasLeft)/2.0-(nodeX(menu.treeNodes.getFirst())+64)*zoom;
            centerPending=false;
        }
        g.enableScissor(canvasLeft,36,canvasRight,height-28);
        g.pose().pushPose();g.pose().translate(canvasLeft+panX,36+panY,0);g.pose().scale((float)zoom,(float)zoom,1);
        if(current) {
            var byId=new HashMap<Integer,CompoundTag>();
            for(var n:menu.treeNodes)byId.put(n.getInt("id"),n);
            for(var n:menu.treeNodes)for(int child:n.getIntArray("children")) {
                var c=byId.get(child);if(c==null)continue;
                branch(g,nodeX(n)+64,nodeY(n)+35,nodeX(c)+64,nodeY(c)-1);
            }
            for(var n:menu.treeNodes) {
                int x=nodeX(n),y=nodeY(n),accent=color(n.getString("kind"));
                double sx=canvasLeft+panX+x*zoom,sy=36+panY+y*zoom;
                if(sx+128*zoom<canvasLeft||sx>canvasRight||sy+34*zoom<36||sy>height-28)continue;
                g.fill(x-1,y-1,x+129,y+35,n.getInt("id")==selected?0xfff4edbe:accent);
                g.fill(x,y,x+128,y+34,0xe52b3248);
                var stack=ItemStack.parseOptional(mc.level.registryAccess(),n.getCompound("icon"));
                if(!stack.isEmpty())g.renderItem(stack,x+4,y+8);
                boolean stopped=!n.getString("stop").equals("NONE");
                label(g,n.getString("name"),x+24,y+5,stopped?88:100,0xfff0f1ff);
                if(stopped)label(g,"…",x+117,y+5,10,0xffe0ba79);
                label(g,tr("kind."+n.getString("kind")).getString(),x+24,y+19,100,accent);
            }
        }
        g.pose().popPose();g.disableScissor();
        renderMaterials(g,mx,my,partial,current);
        if(!current)label(g,tr("tree.reanalyzing").getString(),canvasLeft+8,42,canvasRight-canvasLeft-16,0xffd3d7e8);
        boolean edit=canEdit();recipe.active=edit&&menu.view.getInt("recipes")>1;
        external.active=edit;variant.active=edit&&menu.view.getBoolean("canEditIngredients");
        for(var b:buttons)b.render(g,mx,my,partial);
        String progress=menu.treeNodes.size()+" / "+menu.treeTotal+(menu.treeLimited?"  "+tr("tree.limit").getString():"");
        label(g,tr("tree.help").getString(),16,height-19,width-32,0xffd4dbef);
        label(g,progress,canvasLeft+6,height-39,canvasRight-canvasLeft-12,menu.treeLimited?0xffe0ba79:0xffd4dbef);
        if(selected>=0) {
            // Item icons use their own depth; keep the entire popup above the tree.
            g.pose().pushPose();g.pose().translate(0,0,300);
            g.fill(panelX+3,panelY+3,panelX+panelWidth+3,panelY+panelHeight+3,0x80000000);
            bevel(g,panelX,panelY,panelWidth,panelHeight,0xf21b2231,false);
            label(g,"×",panelX+panelWidth-13,panelY+5,10,0xffd3d7e8);
            if(!current)wrapped(g,tr("tree.reanalyzing").getString(),12,3);
            else if(selectedMaterial!=menu.view.getInt("selectedId"))wrapped(g,tr("tree.loading").getString(),12,2);
            else {
                label(g,menu.view.getString("selected"),panelX+8,panelY+12,panelWidth-28,0xffeef0ff);
                wrapped(g,tr("kind."+menu.view.getString("selectedKind")).getString(),28,1);
                wrapped(g,tr("stock").getString()+" "+menu.view.getLong("stored"),42,1);
                wrapped(g,menu.view.getString("recipe"),56,2);
                if (menu.view.getInt("ingredients") > 0)
                    wrapped(g, Component.translatable("gui.aeautopatterns.ingredient.count", menu.view.getInt("ingredients")).getString(),80,2);
            }
            for(var b:floatingButtons)b.render(g,mx,my,partial);
            g.pose().popPose();
        }
        var hovered=current?hit(mx,my):null;
        if(hovered!=null) {
            var lines=new ArrayList<Component>();lines.add(Component.literal(hovered.getString("name")));
            lines.add(tr("kind."+hovered.getString("kind")));
            if(!hovered.getString("stop").equals("NONE"))lines.add(tr("tree.stop."+hovered.getString("stop")));
            g.renderTooltip(mc.font,lines,Optional.empty(),mx,my);
        }
        if(inPanel(mx,my)&&my<panelY+panelHeight-66&&!menu.view.getString("error").isEmpty())
            g.renderTooltip(mc.font,Component.literal(menu.view.getString("error")),mx,my);
    }
    private boolean treeCurrent() {
        return menu.treeRevision==menu.view.getInt("revision")&&menu.view.getBoolean("treeReady");
    }
    private int listRows() {return Math.max(0,(height-108)/20);}
    private boolean inMaterialList(double mx,double my) {
        return mx>=16&&mx<canvasLeft-14&&my>=76&&my<76+listRows()*20;
    }
    private void select(CompoundTag node) {
        selected=node.getInt("id");selectedMaterial=node.getInt("materialId");
        dragging=false;
        positionBeside(node);
        if(selectedMaterial>=0)action.accept("select",selectedMaterial);
    }
    private void focus(CompoundTag node) {
        select(node);centerPending=false;dragging=false;
        panX=(canvasRight-canvasLeft)/2.0-(nodeX(node)+64)*zoom;
        panY=(height-64)/2.0-(nodeY(node)+17)*zoom;
        positionBeside(node);
    }
    private void positionBeside(CompoundTag node) {
        int x=(int)(canvasLeft+panX+(nodeX(node)+136)*zoom);
        if(x+panelWidth>canvasRight-4) {
            int left=(int)(canvasLeft+panX+nodeX(node)*zoom)-panelWidth-8;
            if(left>=canvasLeft+4)x=left;
        }
        positionPanel(x,(int)(36+panY+nodeY(node)*zoom));
    }
    private void positionPanel(int x,int y) {
        panelWidth=Math.min(190,canvasRight-canvasLeft-8);panelHeight=Math.min(216,height-72);
        panelX=Math.clamp(x,canvasLeft+4,canvasRight-panelWidth-4);
        panelY=Math.clamp(y,40,height-32-panelHeight);
        recipe.setPosition(panelX+8,panelY+panelHeight-62);recipe.setWidth(panelWidth-16);
        external.setPosition(panelX+8,panelY+panelHeight-42);external.setWidth(panelWidth-16);
        variant.setPosition(panelX+8,panelY+panelHeight-22);variant.setWidth(panelWidth-16);
    }
    private boolean inPanel(double mx,double my) {
        return selected>=0&&mx>=panelX&&mx<panelX+panelWidth&&my>=panelY&&my<panelY+panelHeight;
    }
    boolean dismissSelection() {
        if(selected<0)return false;
        selected=-1;selectedMaterial=-1;dragging=false;return true;
    }
    private void renderMaterials(GuiGraphics g,int mx,int my,float partial,boolean current) {
        g.fill(12,36,canvasLeft-8,height-28,0xb52b3144);
        if(cachedCount!=menu.treeNodes.size()) {
            var unique=new LinkedHashMap<Integer,CompoundTag>();
            String query=materialSearch.getValue().strip().toLowerCase(Locale.ROOT);
            for(var n:menu.treeNodes)if(n.getString("name").toLowerCase(Locale.ROOT).contains(query)
                ||n.getCompound("icon").getString("id").toLowerCase(Locale.ROOT).contains(query))
                unique.putIfAbsent(n.getInt("materialId"),n);
            materials=List.copyOf(unique.values());cachedCount=menu.treeNodes.size();
        }
        listOffset=Math.clamp(listOffset,0,Math.max(0,materials.size()-listRows()));
        label(g,tr("tree.materials").getString()+" ("+materials.size()+")",18,41,canvasLeft-36,0xffeef0ff);
        materialSearch.render(g,mx,my,partial);
        if(!current)return;
        int end=Math.min(materials.size(),listOffset+listRows());
        for(int i=listOffset;i<end;i++) {
            var n=materials.get(i);int y=76+(i-listOffset)*20;
            boolean hover=inMaterialList(mx,my)&&my>=y&&my<y+20;
            g.fill(16,y,canvasLeft-16,y+19,n.getInt("materialId")==selectedMaterial?0xc34b5676:hover?0x99505b75:0x57353e54);
            var stack=ItemStack.parseOptional(mc.level.registryAccess(),n.getCompound("icon"));
            if(!stack.isEmpty())g.renderItem(stack,18,y+1);
            label(g,n.getString("name"),37,y+6,canvasLeft-57,current?0xffedf0ff:0xff9399aa);
        }
        if(materials.size()>listRows()&&listRows()>0) {
            int track=listRows()*20,thumb=Math.max(8,track*listRows()/materials.size());
            int y=76+(track-thumb)*listOffset/(materials.size()-listRows());
            g.fill(canvasLeft-13,76,canvasLeft-11,76+track,0x88434b61);
            g.fill(canvasLeft-13,y,canvasLeft-11,y+thumb,0xffa9bafa);
        }
        if(materials.isEmpty())label(g,tr("tree.no_materials").getString(),18,80,canvasLeft-36,0xffaeb8cc);
        if(inMaterialList(mx,my)) {
            int row=(my-76)/20+listOffset;
            if(row<materials.size())g.renderTooltip(mc.font,Component.literal(materials.get(row).getString("name")),mx,my);
        }
    }
    boolean charTyped(char character,int mods) {materialSearch.charTyped(character,mods);return true;}
    boolean keyPressed(int key,int scan,int mods) {materialSearch.keyPressed(key,scan,mods);return true;}
    /** All children share the same level and fork height: horizontal and vertical segments only. */
    private void branch(GuiGraphics g,int x,int y,int cx,int cy) {
        int forkY=(y+cy)/2;
        g.vLine(x,y,forkY,0xff9eafd5);
        g.hLine(Math.min(x,cx),Math.max(x,cx),forkY,0xff9eafd5);
        g.vLine(cx,forkY,cy,0xff9eafd5);
    }
    boolean mouseClicked(double mx,double my,int button) {
        if(inPanel(mx,my)) {
            if(button==0&&mx>=panelX+panelWidth-20&&my<panelY+20){dismissSelection();return true;}
            for(var b:floatingButtons)if(b.mouseClicked(mx,my,button))return true;
            return true;
        }
        materialSearch.mouseClicked(mx,my,button);
        materialSearch.setFocused(mx>=18&&mx<canvasLeft-14&&my>=54&&my<70);
        for(var b:buttons)if(b.mouseClicked(mx,my,button))return true;
        if(inMaterialList(mx,my)) {
            int row=((int)my-76)/20+listOffset;
            if(button==0&&row<materials.size()&&treeCurrent())focus(materials.get(row));
            return true;
        }
        if(mx>=canvasLeft&&mx<canvasRight&&my>=36&&my<height-28) {
            var n=hit(mx,my);
            if(button==0&&n!=null&&menu.view.getBoolean("treeReady")&&menu.treeRevision==menu.view.getInt("revision")) {
                select(n);
            } else if(button==0||button==1){dismissSelection();dragging=true;}
        }
        return true;
    }
    boolean mouseDragged(double dx,double dy) {if(dragging){panX+=dx;panY+=dy;}return true;}
    boolean mouseReleased() {dragging=false;return true;}
    boolean mouseScrolled(double mx,double my,double delta) {
        if(inPanel(mx,my))return true;
        if(mx>=12&&mx<canvasLeft-8&&my>=36&&my<height-28) {
            listOffset=Math.clamp(listOffset-(int)Math.signum(delta),0,Math.max(0,materials.size()-listRows()));
            return true;
        }
        if(mx>=canvasLeft&&mx<canvasRight&&my>=36&&my<height-28&&delta!=0) {
            double old=zoom;zoom=Math.clamp(zoom*(delta>0?1.1:1/1.1),0.35,1.75);
            panX=mx-canvasLeft-(mx-canvasLeft-panX)*zoom/old;panY=my-36-(my-36-panY)*zoom/old;
        }
        return true;
    }
}
