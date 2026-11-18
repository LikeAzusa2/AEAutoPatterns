package com.likeazusa2.aeautopatterns.menu;

import java.util.*;
import com.likeazusa2.aeautopatterns.AEAutoPatterns;
import com.likeazusa2.aeautopatterns.integration.*;
import com.likeazusa2.aeautopatterns.logic.*;
import com.likeazusa2.aeautopatterns.network.TerminalPackets;
import com.likeazusa2.aeautopatterns.part.AutoPatternTerminalPart;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

final class RecipeRouteMenu {
    private static final int PAGE=6, GROUP_PAGE=7;
    private final AutoPatternTerminalPart terminal;
    private final ServerPlayer player;
    private boolean open, ready;
    private String groupId="", mode="recipes", scope="all", query="", machine="", status="";
    private int offset, groupOffset, epoch=-1;
    private RecipeAdapter indexing;
    private final Map<ResourceLocation, ProcessingRecipeView> catalog=new TreeMap<>();
    private record Row(String token, ResourceLocation recipe, String provider) {}
    private List<Row> visible=List.of();
    private List<ResourceLocation> batch=List.of();
    private String batchToken="";

    RecipeRouteMenu(AutoPatternTerminalPart terminal, ServerPlayer player) { this.terminal=terminal; this.player=player; }
    void close() { open=false; visible=List.of(); batch=List.of(); batchToken=""; }

    void tick() {
        if(!open)return;
        if(epoch!=AEAutoPatterns.reloadEpoch) {
            epoch=AEAutoPatterns.reloadEpoch; ready=false; catalog.clear();
            visible=List.of(); batch=List.of(); batchToken="";
            indexing=AdapterRegistry.create(player.level(),new RecipeAdapter.Settings(true,false,true));
        }
        if(ready||indexing==null||!indexing.indexStep(64))return;
        for(var recipe:indexing.processingRecipes()) {
            var view=describe(recipe);
            if(view!=null)catalog.putIfAbsent(view.id(),view);
        }
        ready=true; indexing=null;
    }

    boolean action(TerminalPackets.Action a) {
        String value=a.value();
        if(a.action().equals("route_open")) { open=true; return true; }
        if(!open)return false;
        var group=active(); status="";
        switch(a.action()) {
            case "route_close" -> close();
            case "route_select" -> { if(terminal.recipeRouteGroups().stream().anyMatch(g->g.groupId().equals(value))) { groupId=value; offset=0; } }
            case "route_create" -> {
                var id=terminal.createRecipeRouteGroup(value);
                if(id==null)status="limit";
                else { groupId=id; offset=0; groupOffset=(terminal.recipeRouteGroups().size()-1)/GROUP_PAGE*GROUP_PAGE; }
            }
            case "route_rename" -> { if(group!=null)terminal.renameRecipeRouteGroup(groupId,value); }
            case "route_delete" -> { if(group!=null)terminal.deleteRecipeRouteGroup(groupId); }
            case "route_tab" -> { if(value.equals("recipes")||value.equals("providers")) { mode=value; offset=0; } }
            case "route_scope" -> { if(List.of("plan","all","bound").contains(value)) { scope=value; offset=0; machine=""; } }
            case "route_search" -> { query=value.substring(0,Math.min(80,value.length())); offset=0; }
            case "route_machine" -> { if(value.isEmpty()||ResourceLocation.tryParse(value)!=null) { machine=value; offset=0; } }
            case "route_page" -> offset=Math.max(0,offset+Integer.signum(a.index())*PAGE);
            case "group_page" -> groupOffset=Math.max(0,groupOffset+Integer.signum(a.index())*GROUP_PAGE);
            case "route_recipe", "route_proceed_move", "route_provider" -> {
                if(group==null||a.index()<0||a.index()>=visible.size())return true;
                var row=visible.get(a.index());
                if(!row.token().equals(value))return true;
                if(a.action().equals("route_provider")) {
                    if(row.provider()!=null&&!terminal.toggleRouteGroupProvider(groupId,row.provider()))status="limit";
                } else if(row.recipe()!=null) {
                    var id=row.recipe();
                    if(group.recipeIds().contains(id)&&a.action().equals("route_recipe"))
                        terminal.removeRecipesFromRouteGroup(groupId,List.of(id));
                    else if(current(id)) {
                        if(a.action().equals("route_proceed_move"))status=terminal.moveRecipesToRouteGroup(groupId,List.of(id))?"moved":"limit";
                        else if(!terminal.addRecipesToRouteGroup(groupId,List.of(id)))status="limit";
                    }
                }
            }
            case "route_batch" -> {
                if(group!=null&&!batch.isEmpty()&&batchToken.equals(value)&&batch.stream().allMatch(this::current))
                    status=terminal.addRecipesToRouteGroup(groupId,batch)?"batch":"limit";
            }
            default -> { return false; }
        }
        visible=List.of(); batch=List.of(); batchToken="";
        return true;
    }

    private boolean current(ResourceLocation id) {
        return epoch==AEAutoPatterns.reloadEpoch&&player.level().getRecipeManager().byKey(id).isPresent();
    }
    private RecipeRouteGroups.GroupView active() {
        return terminal.recipeRouteGroups().stream().filter(g->g.groupId().equals(groupId)).findFirst().orElse(null);
    }
    private ProcessingRecipeView describe(PatternRecipe recipe) {
        return ProcessingRecipeView.of(recipe,player.registryAccess(),List.of());
    }
    private static int pageOffset(int offset,int count,int page) { return Math.min(offset,Math.max(0,(count-1)/page*page)); }
    private String token(Object identity) {
        String source=groupId+"|"+terminal.revision()+"|"+epoch+"|"+identity;
        return UUID.nameUUIDFromBytes(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    void snapshot(CompoundTag tag,List<PatternDeployment.Target> targets) {
        if(!open)return;
        var groups=terminal.recipeRouteGroups(); var group=active();
        if(group==null&&!groups.isEmpty()) { group=groups.getFirst(); groupId=group.groupId(); offset=0; }
        if(group==null)groupId="";
        var route=new CompoundTag();
        route.putString("groupId",groupId); route.putString("groupName",group==null?"":group.name());
        route.putString("mode",mode); route.putString("scope",scope); route.putString("query",query); route.putString("machine",machine);
        groupOffset=pageOffset(groupOffset,groups.size(),GROUP_PAGE);
        route.putInt("groupOffset",groupOffset); route.putInt("groupCount",groups.size());
        var groupTags=new ListTag();
        for(var g:groups.subList(groupOffset,Math.min(groups.size(),groupOffset+GROUP_PAGE))) {
            var row=new CompoundTag(); row.putString("id",g.groupId()); row.putString("name",g.name());
            row.putInt("recipes",g.recipeIds().size()); row.putInt("providers",g.providerIds().size()); groupTags.add(row);
        }
        route.put("groups",groupTags);
        var owners=new HashMap<ResourceLocation,RecipeRouteGroups.GroupView>();
        for(var g:groups)for(var id:g.recipeIds())owners.put(id,g);
        var planned=terminal.plannedProcessingRecipes();
        route.put("preview",preview(planned,owners,targets));
        visible=List.of(); batch=List.of(); batchToken="";
        if(group!=null) {
            if(mode.equals("providers"))providers(route,group,targets);
            else recipes(route,group,owners,planned);
        }
        if(!status.isEmpty())route.putString("status",status);
        else if(!ready&&scope.equals("all")&&mode.equals("recipes"))route.putString("status","loading");
        tag.put("route",route);
    }

    private void recipes(CompoundTag route,RecipeRouteGroups.GroupView group,
            Map<ResourceLocation,RecipeRouteGroups.GroupView> owners,List<PatternRecipe> planned) {
        var source=new TreeMap<ResourceLocation,ProcessingRecipeView>();
        switch(scope) {
            case "all" -> { if(epoch==AEAutoPatterns.reloadEpoch)source.putAll(catalog); }
            case "plan" -> { for(var recipe:planned) { var row=describe(recipe); if(row!=null)source.putIfAbsent(row.id(),row); } }
            case "bound" -> { for(var id:group.recipeIds())source.put(id,catalog.get(id)); }
            default -> throw new IllegalStateException("Unknown recipe scope");
        }
        var machines=new TreeSet<ResourceLocation>();
        for(var view:source.values())if(view!=null)machines.add(view.machine());
        var machineTags=new ListTag();
        for(var type:machines) {
            var entry=new CompoundTag(); entry.putString("id",type.toString());
            entry.putString("labelKey",ProcessingRecipeView.machineKey(type));
            entry.putString("fallback",ProcessingRecipeView.machineFallback(type)); machineTags.add(entry);
        }
        route.put("machines",machineTags);
        String search=query.strip().toLowerCase(Locale.ROOT);
        var ids=source.keySet().stream().filter(id->{
            var entry=source.get(id);
            return entry==null?machine.isEmpty()&&search.isEmpty():
                (machine.isEmpty()||entry.machine().toString().equals(machine))&&entry.searchText().contains(search);
        }).toList();
        offset=pageOffset(offset,ids.size(),PAGE);
        var rows=new ListTag(); var refs=new ArrayList<Row>();
        for(var id:ids.subList(offset,Math.min(ids.size(),offset+PAGE))) {
            var entry=source.get(id); var row=entry==null?new CompoundTag():entry.describe(player.registryAccess());
            if(entry==null) {
                row.putBoolean("unavailable",true);
                row.putString("machineKey","gui.aeautopatterns.routes.unavailable");
                row.putString("machineFallback","");
                row.putString("name",id.toString());
            }
            var owner=owners.get(id); row.putBoolean("selected",owner!=null&&owner.groupId().equals(groupId));
            if(owner!=null&&!owner.groupId().equals(groupId))row.putString("conflict",owner.name());
            var token=token(id); row.putString("token",token); refs.add(new Row(token,id,null)); rows.add(row);
        }
        visible=List.copyOf(refs);
        int remaining=RecipeRouteGroups.MAX_RECIPES_PER_GROUP-group.recipeIds().size();
        if(!machine.isEmpty()||!search.isEmpty()||scope.equals("plan")) {
            batch=ids.stream().filter(id->!owners.containsKey(id)&&source.get(id)!=null).limit(remaining+1L).toList();
            if(batch.size()>remaining) { batch=List.of(); route.putString("status","limit"); }
        }
        batchToken=batch.isEmpty()?"":token(batch);
        route.putString("batchToken",batchToken); putPage(route,ids.size(),rows);
    }

    private void providers(CompoundTag route,RecipeRouteGroups.GroupView group,List<PatternDeployment.Target> targets) {
        String search=query.strip().toLowerCase(Locale.ROOT); var order=new HashMap<String,Integer>();
        for(int i=0;i<group.providerIds().size();i++)order.put(group.providerIds().get(i),i+1);
        var providers=PatternDeployment.groupTargets(targets).stream().filter(p->p.name().toLowerCase(Locale.ROOT).contains(search)
            ||p.members().stream().anyMatch(t->PatternDeployment.location(t.container()).toLowerCase(Locale.ROOT).contains(search))).toList();
        offset=pageOffset(offset,providers.size(),PAGE);
        var rows=new ListTag(); var refs=new ArrayList<Row>();
        for(var p:providers.subList(offset,Math.min(offset+PAGE,providers.size()))) {
            int selected=0,crafting=0,first=Integer.MAX_VALUE,free=0,slots=0;
            for(var target:p.members()) {
                var id=PatternDeployment.identity(target.container());
                if(order.containsKey(id)) { selected++; first=Math.min(first,order.get(id)); }
                if(terminal.providerCraftingOnly(id))crafting++;
                free+=target.freeSlots(); slots+=target.container().getTerminalPatternInventory().size();
            }
            var container=p.representative().container(); var row=new CompoundTag(); int count=p.members().size();
            row.putString("name",p.name()); row.putInt("count",count); row.putInt("free",free); row.putInt("slots",slots);
            row.putString("location",PatternDeployment.location(container));
            row.putBoolean("selected",selected==count); row.putBoolean("selectedMixed",selected>0&&selected<count);
            row.putBoolean("craftingOnly",crafting==count); row.putBoolean("craftingMixed",crafting>0&&crafting<count);
            row.putInt("order",selected==0?0:first); row.put("icon",PatternDeployment.icon(container).save(player.registryAccess()));
            var token=token(p.id()); row.putString("token",token); refs.add(new Row(token,null,p.id())); rows.add(row);
        }
        visible=List.copyOf(refs); putPage(route,providers.size(),rows);
    }

    private void putPage(CompoundTag route,int count,ListTag rows) {
        route.putInt("count",count); route.putInt("offset",offset); route.put("rows",rows);
    }

    private ListTag preview(List<PatternRecipe> planned,Map<ResourceLocation,RecipeRouteGroups.GroupView> owners,
            List<PatternDeployment.Target> targets) {
        var counts=new LinkedHashMap<Optional<RecipeRouteGroups.GroupView>,Integer>();
        for(var recipe:planned) {
            var id=recipe.adapted()!=null?recipe.adapted().recipeId():recipe.recipe().id();
            counts.merge(Optional.ofNullable(owners.get(id)),1,Integer::sum);
        }
        var result=new ListTag(); boolean configured=terminal.routeStatus().routingConfigured();
        for(var entry:counts.entrySet()) {
            if(result.size()>=GROUP_PAGE)break;
            var group=entry.getKey().orElse(null); var row=new CompoundTag();
            row.putString("name",group==null?"—":group.name()); row.putInt("count",entry.getValue());
            var eligible=group==null?List.<PatternDeployment.Target>of():targets.stream().filter(t->{
                var id=PatternDeployment.identity(t.container()); return group.providerIds().contains(id)&&!terminal.providerCraftingOnly(id);
            }).toList();
            row.putString("destination",eligible.stream().map(PatternDeployment.Target::name).distinct().limit(3)
                .collect(java.util.stream.Collectors.joining(" / ")));
            row.putString("status",!terminal.autoUpload()?"buffered":group==null?configured?"unassigned":"legacy":
                eligible.isEmpty()?"offline":eligible.stream().noneMatch(t->t.freeSlots()>0)?"full":"ready"); result.add(row);
        }
        return result;
    }

    String destination(ResourceLocation recipe) {
        return terminal.recipeRouteGroups().stream().filter(g->g.recipeIds().contains(recipe))
            .map(RecipeRouteGroups.GroupView::name).findFirst().orElse("");
    }
}
