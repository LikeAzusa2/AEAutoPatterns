package com.likeazusa2.aeautopatterns.client;

import java.util.*;
import net.minecraft.client.Minecraft;
import appeng.client.gui.Icon;
import appeng.client.gui.widgets.ToggleButton;
import appeng.core.localization.ButtonToolTips;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.network.PacketDistributor;
import com.likeazusa2.aeautopatterns.menu.AutoPatternMenu;
import com.likeazusa2.aeautopatterns.network.TerminalPackets;
import static com.likeazusa2.aeautopatterns.client.TerminalStyle.*;

public final class AutoPatternScreen extends AbstractContainerScreen<AutoPatternMenu> {
    private static final int ROW_TOP=34, ROW_HEIGHT=18, ROW_BOTTOM=106;
    private EditBox search;
    private List<Item> results=List.of();
    private int searchOffset;
    private boolean draggingRows,draggingSearch;
    private double rowDragGrab;
    private int rowDragOffset,rowDragRevision;
    private Button start,pause,recipe,external,variant,treeButton,processing,autoUpload,uploadTarget;
    private DependencyTreeView tree;
    private ProviderSelectionView providers;
    private final RecipeSelectionView picker;
    private ToggleButton substitutions,fluidSubstitutions;
    private int pendingWidth;
    private boolean showPendingInline;
    private Button pendingTab;
    private Button ignoreComponents;
    private Button pendingSelectAll,pendingMakeExternal;
    private final Set<Integer> pendingSelection=new HashSet<>();
    private int pendingSelectionRevision=-1;
    void acceptJeiItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        var encoded=(net.minecraft.nbt.CompoundTag)stack.save(Minecraft.getInstance().level.registryAccess());
        PacketDistributor.sendToServer(new TerminalPackets.JeiTarget(menu.containerId,menu.view.getInt("revision"),encoded));
    }
    net.minecraft.client.renderer.Rect2i jeiTargetArea() {
        return new net.minecraft.client.renderer.Rect2i(leftPos+8,topPos+34,190,72);
    }
    public AutoPatternScreen(AutoPatternMenu menu,Inventory inv,Component title) {
        super(menu,inv,title);imageWidth=386;imageHeight=270;inventoryLabelY=-100;
        picker=new RecipeSelectionView(menu,this::send);
    }
    private Component tr(String key) { return Component.translatable("gui.aeautopatterns."+key); }
    private void send(String action,int index,String value) {
        PacketDistributor.sendToServer(new TerminalPackets.Action(menu.containerId,menu.view.getInt("revision"),action,index,value));
    }
    private Button button(int x,int y,int w,String label,Runnable click) {
        return addRenderableWidget(new TerminalButton(leftPos+x,topPos+y,w,16,tr(label),v->click.run()));
    }
    @Override protected void init() {
        String query=search==null?"":search.getValue();
        super.init();
        draggingRows=false;
        // Center the combined panels only when a readable side column fits.
        // On narrow scaled windows the queue shares the dependency viewport.
        pendingWidth=width-imageWidth-18>=72?Math.min(112,width-imageWidth-18):0;
        if(pendingWidth>0)leftPos=(width-imageWidth-pendingWidth-6)/2+pendingWidth+6;
        send("pending_scroll",0,pendingWidth==0?"compact":"wide");
        int controlsX=pendingWidth>0?-pendingWidth-2:8;
        int controlsY=pendingWidth>0?49:34;
        int controlsWidth=pendingWidth>0?pendingWidth-8:190;
        int gap=3,buttonWidth=(controlsWidth-gap)/2;
        pendingSelectAll=addRenderableWidget(new TerminalButton(leftPos+controlsX,topPos+controlsY,buttonWidth,14,
            tr("pending.select_all"),b->toggleAllPending()));
        pendingMakeExternal=addRenderableWidget(new TerminalButton(leftPos+controlsX+buttonWidth+gap,topPos+controlsY,
            controlsWidth-buttonWidth-gap,14,tr("pending.make_external"),b->sendBulkExternal()));
        PacketDistributor.sendToServer(new TerminalPackets.Action(menu.containerId,menu.view.getInt("revision"),
            "jei_status",0,Boolean.toString(JeiCompat.available())));
        search=new EditBox(font,leftPos+200,topPos+8,172,12,tr("search"));
        search.setBordered(false);search.setTextColor(TEXT);search.setTextColorUneditable(MUTED);
        search.setHint(tr("search"));search.setMaxLength(80);
        search.setResponder(value->{
            draggingRows=false;
            searchOffset=0;
            if(value.isBlank())results=List.of();
            else {
                var term=value.toLowerCase(Locale.ROOT);
                results=BuiltInRegistries.ITEM.stream().filter(i->i!=Items.AIR&&
                    (BuiltInRegistries.ITEM.getKey(i).toString().contains(term)||
                    i.getDescription().getString().toLowerCase(Locale.ROOT).contains(term))).toList();
            }
        });
        addRenderableWidget(search);
        search.setValue(query);
        pendingTab=addRenderableWidget(new TerminalButton(leftPos+8,topPos+20,126,12,tr("pending.title"),b->{
            showPendingInline=!showPendingInline;if(showPendingInline)search.setValue("");
        }));
        pendingTab.visible=pendingWidth==0;
        treeButton=addRenderableWidget(new TerminalButton(leftPos+138,topPos+20,60,12,tr("tree.open"),b->{
            if(!menu.view.getBoolean("treeReady"))return;
            search.setFocused(false);menu.treeNodes.clear();menu.treeRevision=-1;menu.treeTotal=0;menu.treeLimited=false;
            tree=new DependencyTreeView(menu,(action,index)->send(action,index,""),this::closeTree,width,height);
            send("tree_open",0,"");
        }));
        if(tree!=null)tree.resize(width,height);
        if(providers!=null)providers.resize(width,height);
        ignoreComponents=addRenderableWidget(new TerminalButton(leftPos+264,topPos+20,78,12,tr("ignore_components.off"),
            b->send("ignore_components",0,Boolean.toString(!menu.view.getBoolean("ignoreTargetComponents")))));
        substitutions=addRenderableWidget(new ToggleButton(Icon.S_SUBSTITUTION_ENABLED,Icon.S_SUBSTITUTION_DISABLED,
            enabled->send("substitute",0,Boolean.toString(enabled))));
        substitutions.setPosition(leftPos+350,topPos+22);
        substitutions.setHalfSize(true);substitutions.setDisableBackground(true);substitutions.setWidth(8);substitutions.setHeight(8);
        substitutions.setTooltipOn(List.of(ButtonToolTips.SubstitutionsOn.text(),ButtonToolTips.SubstitutionsDescEnabled.text()));
        substitutions.setTooltipOff(List.of(ButtonToolTips.SubstitutionsOff.text(),ButtonToolTips.SubstitutionsDescDisabled.text()));
        fluidSubstitutions=addRenderableWidget(new ToggleButton(Icon.S_FLUID_SUBSTITUTION_ENABLED,Icon.S_FLUID_SUBSTITUTION_DISABLED,
            enabled->send("substituteFluids",0,Boolean.toString(enabled))));
        fluidSubstitutions.setPosition(leftPos+366,topPos+22);
        fluidSubstitutions.setHalfSize(true);fluidSubstitutions.setDisableBackground(true);fluidSubstitutions.setWidth(8);fluidSubstitutions.setHeight(8);
        fluidSubstitutions.setTooltipOn(List.of(ButtonToolTips.FluidSubstitutions.text(),ButtonToolTips.FluidSubstitutionsDescEnabled.text()));
        fluidSubstitutions.setTooltipOff(List.of(ButtonToolTips.FluidSubstitutions.text(),ButtonToolTips.FluidSubstitutionsDescDisabled.text()));
        button(208,34,80,"cursor",()->send("cursor",0,""));
        processing=button(292,34,84,"processing.off",()->send("processing",0,""));
        autoUpload=button(208,52,82,"upload.off",()->send("autoUpload",0,Boolean.toString(!menu.view.getBoolean("autoUpload"))));
        uploadTarget=button(294,52,82,"upload.target",()->{
            search.setFocused(false);draggingRows=false;
            providers=new ProviderSelectionView(menu,this::send,this::closeProviders,width,height);
            send("uploadTarget",0,"");
        });
        recipe=button(208,70,82,"recipe",()->send("recipe",0,""));
        external=button(294,70,82,"external",()->send("external",0,""));
        button(208,88,22,"previous",()->send("slot",-1,""));
        variant=button(233,88,118,"variant",()->send("ingredient",0,""));
        button(354,88,22,"next",()->send("slot",1,""));
        button(8,118,89,"analyze",()->{search.setValue("");send("analyze",0,"");});
        start=button(101,118,89,"start",()->send("start",0,""));
        pause=button(194,118,89,"pause",()->send("pause",0,""));
        button(287,118,89,"cancel",()->send("cancel",0,""));
    }
    @Override protected void containerTick() {
        super.containerTick();var state=menu.view.getString("state");
        int revision=menu.view.getInt("revision");
        if(pendingSelectionRevision!=revision){pendingSelection.clear();pendingSelectionRevision=revision;}
        pendingTab.setMessage(showPendingInline?tr("dependencies"):
            Component.translatable("gui.aeautopatterns.pending.count",menu.view.getInt("pendingCount")));
        var pendingIds=menu.view.getIntArray("pendingIds");
        boolean allSelected=pendingIds.length>0&&Arrays.stream(pendingIds).allMatch(pendingSelection::contains);
        pendingSelectAll.setMessage(tr(allSelected?"pending.clear":"pending.select_all"));
        pendingSelectAll.active=pendingIds.length>0;
        pendingMakeExternal.active=!pendingSelection.isEmpty();
        treeButton.active=menu.view.getBoolean("treeReady");
        ignoreComponents.setMessage(tr(menu.view.getBoolean("ignoreTargetComponents")?"ignore_components.on":"ignore_components.off"));
        processing.setMessage(tr(menu.view.getBoolean("allowProcessing")?"processing.on":"processing.off"));
        autoUpload.setMessage(tr(menu.view.getBoolean("autoUpload")?"upload.on":"upload.off"));
        uploadTarget.setMessage(tr("upload.target"));
        uploadTarget.active=true;
        start.active=state.equals("READY")||state.equals("PAUSED");
        pause.active=Set.of("RUNNING","NO_BLANKS","FULL","OFFLINE","VALIDATING").contains(state);
        recipe.active=menu.view.getInt("recipes")>1;variant.active=menu.view.getInt("variants")>1;
        external.active=menu.view.contains("selected");
    }
    private void text(GuiGraphics g,String value,int x,int y,int color,int width) {
        g.drawString(font,font.plainSubstrByWidth(value,width),leftPos+x,topPos+y,color,false);
    }
    private void panel(GuiGraphics g,int x,int y,int w,int h,int fill,boolean inset) {
        bevel(g,leftPos+x,topPos+y,w,h,fill,inset);
    }
    private String rowStatus(CompoundTag row) {
        String kind=row.getString("kind");
        if(kind.equals("PENDING")&&menu.view.getString("state").equals("ERROR")) {
            String material=menu.view.getString("issueMaterial");
            return material.isEmpty()?tr("kind.ISSUE").getString():Component.translatable("gui.aeautopatterns.issue.at",material).getString();
        }
        return tr("kind."+kind).getString();
    }
    private List<Component> issueTooltip() {
        var lines=new ArrayList<Component>();
        if(!menu.view.getString("issuePath").isEmpty()) {
            lines.add(Component.translatable("gui.aeautopatterns.issue.path",menu.view.getString("issuePath")));
            if(!menu.view.getString("issueRecipe").isEmpty())lines.add(Component.translatable("gui.aeautopatterns.issue.recipe",menu.view.getString("issueRecipe")));
        }
        String error=menu.view.getString("error");
        lines.add(error.startsWith("Cycle:")?tr("issue.cycle"):Component.literal(error));
        if(!menu.view.getString("issueMaterial").isEmpty())lines.add(tr("issue.locate"));
        return lines;
    }
    private void renderIssueTooltip(GuiGraphics g,List<Component> lines,int mx,int my) {
        var wrapped=new ArrayList<net.minecraft.util.FormattedCharSequence>();
        for(var line:lines)wrapped.addAll(font.split(line,Math.max(80,Math.min(340,width-20))));
        g.renderTooltip(font,wrapped,mx,my);
    }
    @Override protected void renderBg(GuiGraphics g,float partial,int mx,int my) {
        g.fill(leftPos-1,topPos-1,leftPos+imageWidth+1,topPos+imageHeight+1,EDGE);
        panel(g,0,0,imageWidth,imageHeight,PANEL,false);
        panel(g,2,2,imageWidth-4,imageHeight-4,PANEL,false);
        panel(g,196,4,182,17,LIGHT,true);
        if(pendingWidth>0)text(g,tr("dependencies").getString(),8,22,TEXT,124);
        text(g,tr("route").getString(),208,22,TEXT,52);
        panel(g,6,32,194,76,SLOT,true);
        panel(g,203,32,176,84,WELL,true);
        if(pendingInline()) {
            renderPendingRows(g,8,51,190,3,ROW_HEIGHT,mx,my);
        } else if(!search.getValue().isBlank()) {
            for(int i=0;i<Math.min(AutoPatternMenu.VISIBLE_ROWS,results.size()-searchOffset);i++) {
                int y=ROW_TOP+i*ROW_HEIGHT;
                panel(g,8,y,190,ROW_HEIGHT,SLOT,true);
                var item=results.get(searchOffset+i);g.renderItem(item.getDefaultInstance(),leftPos+12,topPos+y+2);
                text(g,item.getDescription().getString(),34,y+6,TEXT,158);
            }
        } else {
            var rows=menu.view.getList("rows",Tag.TAG_COMPOUND);
            for(int i=0;i<rows.size();i++) {
                var row=rows.getCompound(i);int y=ROW_TOP+i*ROW_HEIGHT;
                int indent=Math.min(4,row.getInt("depth"))*7;
                boolean fluidHighlight=menu.view.getBoolean("substituteFluids")&&fluidSubstitutions.isHovered()
                    &&Arrays.stream(menu.view.getIntArray("fluidMaterials")).anyMatch(id->id==row.getInt("id"));
                panel(g,8,y,190,ROW_HEIGHT,fluidHighlight?0xff7ac25f:row.getBoolean("selected")?0xffc4ccdf:SLOT,true);
                text(g,row.getBoolean("children")?(row.getBoolean("collapsed")?"+":"−"):"·",10+indent,y+6,MUTED,9);
                var icon=ItemStack.parseOptional(minecraft.level.registryAccess(),row.getCompound("icon"));
                if(!icon.isEmpty())g.renderItem(icon,leftPos+20+indent,topPos+y+2);
                text(g,row.getString("name"),39+indent,y,TEXT,153-indent);
                text(g,rowStatus(row),39+indent,y+9,kindColor(row.getString("kind")),153-indent);
            }
        }
        int count=pendingInline()?0:search.getValue().isBlank()?menu.view.getInt("rowCount"):results.size();
        int offset=draggingRows?rowDragOffset:search.getValue().isBlank()?menu.view.getInt("scrollOffset"):searchOffset;
        if(count>AutoPatternMenu.VISIBLE_ROWS) {
            int thumb=Math.max(8,72*AutoPatternMenu.VISIBLE_ROWS/count);
            int y=ROW_TOP+(72-thumb)*offset/(count-AutoPatternMenu.VISIBLE_ROWS);
            g.fill(leftPos+196,topPos+ROW_TOP,leftPos+199,topPos+ROW_BOTTOM,SHADOW);
            g.fill(leftPos+196,topPos+y,leftPos+199,topPos+y+thumb,LIGHT);
        }
        if(menu.view.getBoolean("inputFluid")&&fluidSubstitutions.isHovered())
            g.fill(leftPos+207,topPos+105,leftPos+377,topPos+114,0xff7ac25f);
        text(g,menu.view.getInt("ingredient")+"/"+menu.view.getInt("ingredients")+" "+menu.view.getString("input"),208,106,TEXT,168);
        String state=menu.view.getString("state");
        String progress=state.equals("ANALYZING")
            ?tr("state."+state).getString()+" · "+tr("phase."+menu.view.getString("analysisPhase")).getString()+" · "+menu.view.getInt("analysisMaterials")
            :state.equals("VALIDATING")?tr("state."+state).getString()+" · "+menu.view.getInt("analysisMaterials")
            :state.equals("INDEXING")?tr("state."+state).getString()
            :tr("state."+state).getString()+"  "+menu.view.getInt("done")+"/"+menu.view.getInt("total");
        text(g,progress,8,140,TEXT,282);
        int total=menu.view.getInt("total"), done=menu.view.getInt("done");
        panel(g,298,141,78,7,SHADOW,true);
        if(total>0)g.fill(leftPos+299,topPos+142,leftPos+299+Math.min(76,76*done/total),topPos+147,0xff79b8ac);
        text(g,tr("outputs").getString(),8,152,TEXT,160);
        text(g,tr("blanks").getString(),174,152,TEXT,42);
        text(g,tr("inventory").getString(),216,152,TEXT,80);
        for(var slot:menu.slots)panel(g,slot.x-1,slot.y-1,18,18,SLOT,true);

        text(g,tr("required").getString(),174,203,MUTED,42);
        text(g,Integer.toString(menu.view.getInt("GENERATE")),184,215,TEXT,30);
        text(g,tr("summary").getString()+" "+menu.view.getInt("GENERATE")+" / "+menu.view.getInt("NETWORK")+" / "+menu.view.getInt("BUFFER")+" / "+menu.view.getInt("EXTERNAL")+" / "+menu.view.getInt("UNSUPPORTED"),8,240,TEXT,370);
        text(g,tr("stock").getString()+" "+menu.view.getLong("stored")+"  "+tr("kind.NEEDS_RECIPE").getString()+": "+menu.view.getInt("NEEDS_RECIPE"),8,250,MUTED,370);
        String error=menu.view.getString("error");
        if(!error.isEmpty())text(g,menu.view.getString("issueMaterial").isEmpty()?error:
            Component.translatable("gui.aeautopatterns.issue.at",menu.view.getString("issueMaterial")).getString()+" · "+tr("issue.locate").getString(),8,260,0xff8d303a,370);
        if(pendingWidth>0) {
            int x=-pendingWidth-6;
            panel(g,x,20,pendingWidth,218,PANEL,false);
            text(g,tr("pending.title").getString(),x+6,27,TEXT,pendingWidth-12);
            text(g,Component.translatable("gui.aeautopatterns.pending.count",menu.view.getInt("pendingCount")).getString(),x+6,39,MUTED,pendingWidth-12);
            renderPendingRows(g,x+4,66,pendingWidth-8,AutoPatternMenu.PENDING_VISIBLE_ROWS,21,mx,my);
        }
    }
    private boolean pendingInline() {return pendingWidth==0&&showPendingInline&&search.getValue().isBlank();}
    private void renderPendingRows(GuiGraphics g,int x,int y,int w,int visible,int rowHeight,int mx,int my) {
        var rows=menu.view.getList("pendingRows",Tag.TAG_COMPOUND);
        panel(g,x,y,w,visible*rowHeight,SLOT,true);
        for(int i=0;i<Math.min(visible,rows.size());i++) {
            var row=rows.getCompound(i);int ry=y+i*rowHeight;
            boolean hovered=mx>=leftPos+x&&mx<leftPos+x+w-4&&my>=topPos+ry&&my<topPos+ry+rowHeight;
            int fill=row.getBoolean("selected")?0xffc4ccdf:hovered?0xffc3c7d8:SLOT;
            if(row.getBoolean("unresolved")&&!row.getBoolean("selected"))fill=hovered?0xffdfb9b2:0xffd4aaa4;
            panel(g,x,ry,w-4,rowHeight,fill,true);
            int id=row.getInt("id");boolean checked=pendingSelection.contains(id);
            int boxY=ry+(rowHeight-10)/2;
            panel(g,x+1,boxY,10,10,checked?0xff8aa49a:WELL,true);
            if(checked) {
                g.fill(leftPos+x+3,topPos+boxY+4,leftPos+x+5,topPos+boxY+7,TEXT);
                g.fill(leftPos+x+5,topPos+boxY+2,leftPos+x+9,topPos+boxY+5,TEXT);
            }
            var icon=ItemStack.parseOptional(minecraft.level.registryAccess(),row.getCompound("icon"));
            if(!icon.isEmpty())g.renderItem(icon,leftPos+x+14,topPos+ry+(rowHeight-16)/2);
            if(rowHeight>=21) {
                var lines=font.split(Component.literal(row.getString("name")),w-40);
                for(int line=0;line<Math.min(1,lines.size());line++)
                    g.drawString(font,lines.get(line),leftPos+x+32,topPos+ry+2+line*9,TEXT,false);
                String status=row.getBoolean("unresolved")?tr("pending.unresolved").getString()
                    :row.getBoolean("needsRecipe")?tr("pending.needs_recipe").getString():"";
                if(!status.isEmpty())g.drawString(font,font.plainSubstrByWidth(status,w-40),leftPos+x+32,topPos+ry+12,
                    row.getBoolean("unresolved")?0xff8d303a:0xffa26412,false);
            } else text(g,row.getString("name"),x+32,ry+5,TEXT,w-40);
        }
        if(rows.isEmpty())text(g,tr("pending.empty").getString(),x+5,y+7,MUTED,w-10);
        int count=menu.view.getInt("pendingCount"),offset=menu.view.getInt("pendingOffset");
        if(count>visible) {
            int height=visible*rowHeight,thumb=Math.max(8,height*visible/count);
            int top=y+(height-thumb)*offset/(count-visible);
            g.fill(leftPos+x+w-3,topPos+y,leftPos+x+w,topPos+y+height,SHADOW);
            g.fill(leftPos+x+w-3,topPos+top,leftPos+x+w,topPos+top+thumb,LIGHT);
        }
    }
    private int pendingRowAt(double mx,double my) {
        int x=pendingWidth>0?-pendingWidth-2:8,y=pendingWidth>0?66:51;
        int w=pendingWidth>0?pendingWidth-8:190,h=pendingWidth>0?21:ROW_HEIGHT;
        int visible=pendingWidth>0?AutoPatternMenu.PENDING_VISIBLE_ROWS:3;
        if(pendingWidth==0&&!pendingInline())return -1;
        if(mx<leftPos+x||mx>=leftPos+x+w||my<topPos+y||my>=topPos+y+visible*h)return -1;
        return ((int)my-topPos-y)/h;
    }
    private boolean pendingCheckboxAt(double mx,double my) {
        int x=pendingWidth>0?-pendingWidth-1:9,y=pendingWidth>0?66:51;
        int h=pendingWidth>0?21:ROW_HEIGHT,visible=pendingWidth>0?AutoPatternMenu.PENDING_VISIBLE_ROWS:3;
        return (pendingWidth>0||pendingInline())&&mx>=leftPos+x&&mx<leftPos+x+12
            &&my>=topPos+y&&my<topPos+y+visible*h;
    }
    private void toggleAllPending() {
        int[] ids=menu.view.getIntArray("pendingIds");
        boolean all=ids.length>0&&Arrays.stream(ids).allMatch(pendingSelection::contains);
        if(all)for(int id:ids)pendingSelection.remove(id);else for(int id:ids)if(id>=0)pendingSelection.add(id);
    }
    private void sendBulkExternal() {
        if(pendingSelection.isEmpty())return;
        PacketDistributor.sendToServer(new TerminalPackets.BulkExternal(menu.containerId,menu.view.getInt("revision"),
            pendingSelection.stream().sorted().toList()));
        pendingSelection.clear();
    }
    private int kindColor(String kind) {
        return switch(kind) {
            case "NETWORK","BUFFER","GENERATED"->0xff245b47;
            case "EXTERNAL"->0xff715222;
            case "UNSUPPORTED"->0xff8d303a;
            case "NEEDS_RECIPE"->0xffa26412;
            case "PENDING"->menu.view.getString("state").equals("ERROR")?0xff8d303a:MUTED;
            default->0xff354d7a;
        };
    }
    @Override protected void renderLabels(GuiGraphics g,int mx,int my) {
        g.drawString(font,title,8,10,TEXT,false);
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        if(providers!=null){providers.render(g,mx,my,partial);return;}
        boolean showBulk=tree==null&&(pendingWidth>0||pendingInline());
        pendingSelectAll.visible=showBulk;pendingMakeExternal.visible=showBulk;
        if(tree!=null){tree.render(g,mx,my,partial);if(picker.open())picker.render(g,mx,my,partial,width,height);return;}
        substitutions.setState(menu.view.getBoolean("substitute"));
        fluidSubstitutions.setState(menu.view.getBoolean("substituteFluids"));
        super.render(g,mx,my,partial);renderTooltip(g,mx,my);
        int pendingRow=pendingRowAt(mx,my);
        var pending=menu.view.getList("pendingRows",Tag.TAG_COMPOUND);
        if(pendingRow>=0&&pendingRow<pending.size()) {
            var row=pending.getCompound(pendingRow);
            var details=new ArrayList<Component>();details.add(Component.literal(row.getString("name")));
            details.add(row.getBoolean("unresolved")?tr("pending.unresolved_help"):tr("pending.help"));
            if(row.getBoolean("unresolved")&&!row.getString("path").isEmpty())
                details.add(Component.translatable("gui.aeautopatterns.issue.path",row.getString("path")));
            if(row.getBoolean("unresolved")&&!row.getString("recipe").isEmpty())
                details.add(Component.translatable("gui.aeautopatterns.issue.recipe",row.getString("recipe")));
            g.renderTooltip(font,details,Optional.empty(),mx,my);
        }
        if(mx>=leftPos+8&&mx<leftPos+377&&my>=topPos+138&&my<topPos+150) {
            var details=new ArrayList<Component>(List.of(Component.translatable("gui.aeautopatterns.analysis.graph",menu.view.getInt("analysisMaterials"),menu.view.getLong("analysisEdges"),menu.view.getInt("analysisComponents")),
                Component.translatable("gui.aeautopatterns.analysis.work",menu.view.getLong("analysisAttempts"),menu.view.getLong("analysisCacheHits"),menu.view.getLong("analysisReused")),
                Component.translatable("gui.aeautopatterns.analysis.time",menu.view.getInt("analysisMillis"),menu.view.getInt("analysisJeiPending"))));
            String diagnostics=menu.view.getString("analysisAdapterDiagnostics");
            if(!diagnostics.isEmpty())details.add(Component.literal(diagnostics));
            g.renderTooltip(font,details,Optional.empty(),mx,my);
        }
        if(substitutions.isHovered())g.renderTooltip(font,substitutions.getTooltipMessage(),Optional.empty(),mx,my);
        if(fluidSubstitutions.isHovered())g.renderTooltip(font,fluidSubstitutions.getTooltipMessage(),Optional.empty(),mx,my);
        if(processing.isHovered())g.renderTooltip(font,List.of(tr("processing.help"),tr("processing.fuel")),Optional.empty(),mx,my);
        if(ignoreComponents.isHovered())renderIssueTooltip(g,List.of(tr("ignore_components.help"),tr("ignore_components.trim")),mx,my);
        if(autoUpload.isHovered())g.renderTooltip(font,List.of(tr("upload.help")),Optional.empty(),mx,my);
        if(uploadTarget.isHovered())g.renderTooltip(font,List.of(
            Component.translatable("gui.aeautopatterns.providers.summary",menu.view.getInt("selectedProviderCount"),menu.view.getInt("selectedOnlineProviders")),
            Component.translatable("gui.aeautopatterns.providers.free_total",menu.view.getInt("uploadFreeSlots")),
            Component.literal(menu.view.getString("uploadStatus"))),Optional.empty(),mx,my);
        if(search.isHovered()&&JeiCompat.available())g.renderTooltip(font,tr("search.jei_drop"),mx,my);
        if(mx>=leftPos+8&&mx<leftPos+378&&my>=topPos+259&&my<topPos+270&&!menu.view.getString("error").isEmpty())
            renderIssueTooltip(g,issueTooltip(),mx,my);
        if(!pendingInline()&&search.getValue().isBlank()&&mx>=leftPos+8&&mx<leftPos+198&&my>=topPos+ROW_TOP&&my<topPos+ROW_BOTTOM) {
            int row=(my-topPos-ROW_TOP)/ROW_HEIGHT;var rows=menu.view.getList("rows",Tag.TAG_COMPOUND);
            if(row<rows.size()) {
                var r=rows.getCompound(row);
                var details=new ArrayList<Component>();details.add(Component.literal(r.getString("name")+" — "+rowStatus(r)));
                if(menu.view.getString("state").equals("ERROR"))details.addAll(issueTooltip());
                renderIssueTooltip(g,details,mx,my);
            }
        }
        if(recipe.isHovered()&&!menu.view.getString("recipe").isEmpty()) {
            var details=new ArrayList<Component>();
            details.add(Component.literal(menu.view.getString("recipe")));
            if(!menu.view.getString("adapterId").isEmpty()) details.add(Component.literal("Adapter: "+menu.view.getString("adapterId")));
            if(!menu.view.getString("machineType").isEmpty()) details.add(Component.literal("Machine: "+menu.view.getString("machineType")));
            if(!menu.view.getString("conditions").isEmpty()) details.add(Component.literal(menu.view.getString("conditions")));
            if(menu.view.getInt("probabilisticOutputs")>0) details.add(Component.literal("Probabilistic outputs: "+menu.view.getInt("probabilisticOutputs")));
            g.renderTooltip(font,details.stream().map(Component::getVisualOrderText).toList(),mx,my);
        }
        if(picker.open())picker.render(g,mx,my,partial,width,height);
    }
    @Override public boolean mouseClicked(double mx,double my,int button) {
        if(providers!=null)return providers.click(mx,my,button);
        if(picker.open())return picker.click(mx,my,button);
        if(tree!=null)return tree.mouseClicked(mx,my,button);
        int scrollCount=search.getValue().isBlank()?menu.view.getInt("rowCount"):results.size();
        if(button==0&&!pendingInline()&&scrollCount>AutoPatternMenu.VISIBLE_ROWS
            &&mx>=leftPos+194&&mx<leftPos+200&&my>=topPos+ROW_TOP&&my<topPos+ROW_BOTTOM) {
            int thumb=Math.max(8,72*AutoPatternMenu.VISIBLE_ROWS/scrollCount);
            int offset=search.getValue().isBlank()?menu.view.getInt("scrollOffset"):searchOffset;
            int thumbY=topPos+ROW_TOP+(72-thumb)*offset/(scrollCount-AutoPatternMenu.VISIBLE_ROWS);
            rowDragGrab=my>=thumbY&&my<thumbY+thumb?my-thumbY:thumb/2.0;
            draggingRows=true;draggingSearch=!search.getValue().isBlank();
            rowDragOffset=offset;rowDragRevision=menu.view.getInt("revision");
            search.setFocused(false);dragRowsTo(my);return true;
        }
        if((pendingWidth>0||pendingInline())&&
            (pendingSelectAll.mouseClicked(mx,my,button)||pendingMakeExternal.mouseClicked(mx,my,button)))return true;
        int pendingRow=pendingRowAt(mx,my);
        if(button==0&&pendingRow>=0) {
            var rows=menu.view.getList("pendingRows",Tag.TAG_COMPOUND);
            if(pendingRow<rows.size()) {
                var row=rows.getCompound(pendingRow);
                if(pendingCheckboxAt(mx,my)) {
                    int id=row.getInt("id");if(!pendingSelection.remove(id))pendingSelection.add(id);
                } else send(row.getBoolean("unresolved")?"locate_material":"select",row.getInt("id"),"");
            }
            return true;
        }
        if(button==0&&mx>=leftPos+8&&mx<leftPos+378&&my>=topPos+259&&my<topPos+270&&!menu.view.getString("issueMaterial").isEmpty()) {
            send("issue",0,"");return true;
        }
        if(button==0&&mx>=leftPos+8&&mx<leftPos+198&&my>=topPos+ROW_TOP&&my<topPos+ROW_BOTTOM) {
            int row=((int)my-topPos-ROW_TOP)/ROW_HEIGHT;
            if(!search.getValue().isBlank()) {
                if(searchOffset+row<results.size()) {send("target",0,BuiltInRegistries.ITEM.getKey(results.get(searchOffset+row)).toString());search.setValue("");}
                return true;
            }
            var rows=menu.view.getList("rows",Tag.TAG_COMPOUND);
            if(row<rows.size()) {
                var r=rows.getCompound(row);
                send(mx<leftPos+20+Math.min(4,r.getInt("depth"))*7?"expand":"select",r.getInt("id"),"");
                return true;
            }
        }
        return super.mouseClicked(mx,my,button);
    }
    @Override public boolean mouseScrolled(double mx,double my,double horizontal,double vertical) {
        if(providers!=null)return providers.scroll(vertical);
        if(picker.open())return true;
        if(tree!=null)return tree.mouseScrolled(mx,my,vertical);
        if(pendingRowAt(mx,my)>=0) {
            if(vertical!=0)send("pending_scroll",vertical>0?-1:1,pendingWidth==0?"compact":"wide");
            return true;
        }
        if(mx>=leftPos+8&&mx<leftPos+198&&my>=topPos+ROW_TOP&&my<topPos+ROW_BOTTOM) {
            if(vertical!=0) {
                if(search.getValue().isBlank())send("scroll",vertical>0?-1:1,"");
                else {
                    searchOffset=Math.max(0,Math.min(Math.max(0,results.size()-AutoPatternMenu.VISIBLE_ROWS),searchOffset+(vertical>0?-1:1)));
                }
            }
            return true;
        }
        return super.mouseScrolled(mx,my,horizontal,vertical);
    }
    @Override protected boolean hasClickedOutside(double mx,double my,int x,int y,int button) {
        if(pendingWidth>0&&mx>=leftPos-pendingWidth-6&&mx<leftPos-6&&my>=topPos+20&&my<topPos+238)return false;
        return super.hasClickedOutside(mx,my,x,y,button);
    }
    private void closeTree() {tree=null;send("tree_close",0,"");}
    private void closeProviders() {providers=null;send("providers_close",0,"");}
    @Override public void onClose() {if(providers!=null)closeProviders();else if(picker.open())picker.close();else if(tree!=null)closeTree();else super.onClose();}
    @Override public boolean charTyped(char character,int mods) {
        if(providers!=null)return providers.character(character,mods);
        if(picker.open())return true;
        return tree!=null?tree.charTyped(character,mods):super.charTyped(character,mods);
    }
    @Override public boolean mouseDragged(double mx,double my,int button,double dx,double dy) {
        if(providers!=null)return true;
        if(draggingRows&&button==0){dragRowsTo(my);return true;}
        if(picker.open())return true;
        return tree!=null?tree.mouseDragged(dx,dy):super.mouseDragged(mx,my,button,dx,dy);
    }
    @Override public boolean mouseReleased(double mx,double my,int button) {
        if(providers!=null)return true;
        if(draggingRows&&button==0){dragRowsTo(my);draggingRows=false;return true;}
        if(picker.open())return true;
        return tree!=null?tree.mouseReleased():super.mouseReleased(mx,my,button);
    }
    private void dragRowsTo(double mouseY) {
        if(tree!=null||picker.open()||pendingInline()||draggingSearch!=!search.getValue().isBlank()
            ||rowDragRevision!=menu.view.getInt("revision")) {draggingRows=false;return;}
        int count=draggingSearch?results.size():menu.view.getInt("rowCount");
        int max=Math.max(0,count-AutoPatternMenu.VISIBLE_ROWS);
        if(max==0){draggingRows=false;return;}
        int thumb=Math.max(8,72*AutoPatternMenu.VISIBLE_ROWS/count);
        double fraction=(mouseY-topPos-ROW_TOP-rowDragGrab)/(72-thumb);
        int offset=(int)Math.round(Math.max(0,Math.min(1,fraction))*max);
        if(offset==rowDragOffset)return;
        rowDragOffset=offset;
        if(draggingSearch)searchOffset=offset;else send("scroll_to",offset,"");
    }
    @Override public boolean keyPressed(int key,int scan,int mods) {
        if(providers!=null){providers.key(key,scan,mods);return true;}
        if(picker.open()){if(key==256)picker.close();return true;}
        if(tree!=null) {if(key==256){if(!tree.dismissSelection())closeTree();return true;}return tree.keyPressed(key,scan,mods);}
        if(search.isFocused()&&key!=256)return search.keyPressed(key,scan,mods)||search.canConsumeInput();
        return super.keyPressed(key,scan,mods);
    }
}
