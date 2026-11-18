package com.likeazusa2.aeautopatterns.menu;
import java.util.*;
import appeng.api.stacks.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.items.*;
import net.neoforged.neoforge.network.PacketDistributor;
import appeng.menu.AEBaseMenu;
import com.likeazusa2.aeautopatterns.part.AutoPatternTerminalPart;
import com.likeazusa2.aeautopatterns.ModContent;
import com.likeazusa2.aeautopatterns.logic.DependencyPlanner;
import com.likeazusa2.aeautopatterns.logic.MindMapLayout;
import com.likeazusa2.aeautopatterns.logic.PatternDeployment;
import com.likeazusa2.aeautopatterns.network.TerminalPackets;

public final class AutoPatternMenu extends AEBaseMenu {
    public static final int VISIBLE_ROWS=4;
    public static final int PENDING_VISIBLE_ROWS=8;
    public static final int INGREDIENT_PAGE_SIZE=5;
    private final AutoPatternTerminalPart terminal;
    private final Player player;
    private final Set<AEKey> collapsed=new HashSet<>();
    private int scrollOffset,pendingOffset,selected,ticks;
    private int pendingVisibleRows=PENDING_VISIBLE_ROWS;
    private int rowRevision=-1,rowNodeCount=-1,rowMaterialCount=-1;
    private boolean rowsDirty=true;
    private AutoPatternTerminalPart.State rowState;
    private List<Row> cachedRows=List.of();
    private List<AEKey> cachedPending=List.of();
    private Set<AEKey> cachedUnresolved=Set.of();
    private Map<AEKey,Integer> cachedIds=Map.of();
    private final EnumMap<DependencyPlanner.Kind,Integer> cachedCounts=new EnumMap<>(DependencyPlanner.Kind.class);
    public CompoundTag view=new CompoundTag();
    public final List<CompoundTag> treeNodes=new ArrayList<>();
    public int treeTotal,treeRevision=-1;
    public boolean treeLimited;
    private boolean treeOpen;
    private boolean providersOpen;
    private String providerSearch="";
    private int providerOffset;
    private AEKey pickerKey;
    private int pickerPage,pickerRevision;
    private record IngredientGroup(AEKey current, int slotCount, List<AEKey> alternatives) {}

    private record IngredientPicker(AEKey key, String recipeId, int revision, List<IngredientGroup> inputs, int slot) {
        IngredientPicker select(int slot) {
            return new IngredientPicker(key, recipeId, revision, inputs, slot);
        }

        List<AEKey> candidates() {
            return slot < 0 ? List.of() : inputs.get(slot).alternatives();
        }
    }
    private IngredientPicker ingredientPicker;
    private int ingredientListId, sentIngredientCount;
    private final List<AEKey> receivedIngredients = new ArrayList<>();
    private int receivedIngredientListId = -1;

    public void receiveIngredients(CompoundTag batch) {
        var picker = view.getCompound("ingredientPicker");
        int listId = batch.getInt("listId");
        if (!view.contains("ingredientPicker") || picker.getInt("listId") != listId) return;
        if (receivedIngredientListId != listId) {
            receivedIngredientListId = listId;
            receivedIngredients.clear();
        }
        if (batch.getInt("offset") != receivedIngredients.size()) return;
        for (var entry : batch.getList("keys", Tag.TAG_COMPOUND)) {
            receivedIngredients.add(AEKey.fromTagGeneric(player.registryAccess(), (CompoundTag) entry));
        }
    }

    public List<AEKey> receivedIngredients(int listId) {
        return receivedIngredientListId == listId ? receivedIngredients : List.of();
    }

    private void openIngredientPicker(AEKey key) {
        if (!treeReady() || key == null) return;
        var node = terminal.nodes().get(key);
        var groups = terminal.ingredientOptions(key);
        if (node == null || groups.isEmpty() || groups.size() != node.children().size()) return;
        var slotsByIngredient = new LinkedHashMap<AEKey, List<Integer>>();
        for (int slot = 0; slot < groups.size(); slot++) {
            slotsByIngredient.computeIfAbsent(node.children().get(slot), ignored -> new ArrayList<>()).add(slot);
        }
        var inputs = new ArrayList<IngredientGroup>(slotsByIngredient.size());
        slotsByIngredient.forEach((current, slots) -> {
            var common = new LinkedHashSet<>(groups.get(slots.getFirst()));
            common.remove(current);
            for (int i = 1; i < slots.size() && !common.isEmpty(); i++) {
                common.retainAll(new HashSet<>(groups.get(slots.get(i))));
            }
            inputs.add(new IngredientGroup(current, slots.size(), List.copyOf(common)));
        });
        ingredientPicker = new IngredientPicker(key, node.choice().id(), terminal.revision(), List.copyOf(inputs), -1);
        ingredientListId++;
        sentIngredientCount = 0;
    }

    private void ingredientAction(TerminalPackets.Action action) {
        var picker = ingredientPicker;
        if (picker == null || picker.revision() != terminal.revision()) return;
        int index = action.index();
        if (action.action().equals("ingredient_slot")) {
            if (index >= 0 && index < picker.inputs().size() && index != picker.slot()) {
                ingredientPicker = picker.select(index);
                ingredientListId++;
                sentIngredientCount = 0;
            }
            return;
        }
        if (picker.slot() < 0 || !action.value().equals(Integer.toString(ingredientListId))) return;
        var candidates = picker.candidates();
        if (index >= 0 && index < sentIngredientCount && index < candidates.size()
                && terminal.replaceIngredient(picker.key(), picker.recipeId(), picker.inputs().get(picker.slot()).current(), candidates.get(index))) {
            ingredientPicker = null;
        }
    }

    private void syncIngredientPicker(CompoundTag tag) {
        var picker = ingredientPicker;
        if (picker == null) return;
        if (picker.revision() != terminal.revision()) {
            ingredientPicker = null;
            return;
        }
        var data = new CompoundTag();
        data.putInt("slot", picker.slot());
        data.putInt("listId", ingredientListId);
        data.put("product", picker.key().toTagGeneric(player.registryAccess()));
        var inputs = new ListTag();
        for (var input : picker.inputs()) inputs.add(input.current().toTagGeneric(player.registryAccess()));
        data.put("inputs", inputs);
        data.putIntArray("slotCounts", picker.inputs().stream().mapToInt(IngredientGroup::slotCount).toArray());
        data.putInt("candidateCount", picker.candidates().size());
        tag.put("ingredientPicker", data);
    }

    private void streamIngredients(ServerPlayer serverPlayer) {
        var picker = ingredientPicker;
        if (picker == null || picker.revision() != terminal.revision()) return;
        var candidates = picker.candidates();
        if (sentIngredientCount >= candidates.size()) return;
        int end = Math.min(sentIngredientCount + 32, candidates.size());
        var keys = new ListTag();
        for (int i = sentIngredientCount; i < end; i++) {
            keys.add(candidates.get(i).toTagGeneric(player.registryAccess()));
        }
        var batch = new CompoundTag();
        batch.putInt("listId", ingredientListId);
        batch.putInt("offset", sentIngredientCount);
        batch.put("keys", keys);
        PacketDistributor.sendToPlayer(serverPlayer, new TerminalPackets.IngredientBatch(containerId, batch));
        sentIngredientCount = end;
    }
    private void openPicker(AEKey key) {
        if(terminal.nativeOptions(key).isEmpty())return;
        pickerKey=key;pickerPage=0;pickerRevision=terminal.revision();
    }
    public AutoPatternTerminalPart terminal(){return terminal;}
    public void requestJei(AEKey key,int revision) {
        if(!(player instanceof ServerPlayer sp))return;
        PacketDistributor.sendToPlayer(sp,new TerminalPackets.JeiQuery(containerId,revision,key.toTagGeneric(player.registryAccess())));
    }
    public void receiveJeiCandidates(int revision,CompoundTag key,List<String> ids) {
        if(terminal==null||revision!=terminal.revision())return;
        var decoded=AEKey.fromTagGeneric(player.registryAccess(),key);
        if(decoded!=null)terminal.receiveJeiCandidates(revision,decoded,ids);
    }
    private int sentTreeRevision=-1;
    private final com.likeazusa2.aeautopatterns.logic.TreeReveal<MindMapLayout.DisplayNode<AEKey>> treeReveal=new com.likeazusa2.aeautopatterns.logic.TreeReveal<>();
    private Map<AEKey,Integer> treeIds=Map.of();
    public void receiveTree(CompoundTag data) {
        int revision=data.getInt("revision"),offset=data.getInt("offset");
        if(data.getBoolean("reset")) {treeNodes.clear();treeRevision=revision;treeTotal=data.getInt("total");treeLimited=data.getBoolean("limited");}
        if(revision!=treeRevision||offset!=treeNodes.size())return;
        for(var entry:data.getList("nodes",Tag.TAG_COMPOUND))treeNodes.add((CompoundTag)entry);
    }
    private boolean treeReady() {
        return !terminal.keys().isEmpty()&&switch(terminal.state()) {
            case IDLE,INDEXING,ANALYZING,STALE -> false;
            default -> true;
        };
    }
    public AutoPatternMenu(MenuType<AutoPatternMenu> type,int id,Inventory inv,AutoPatternTerminalPart part) {
        super(type,id,inv,part);terminal=part;player=inv.player;
        var storage=part==null?new ItemStackHandler(28):part.inventory();
        addSlot(new SlotItemHandler(storage,0,184,174));
        for(int row=0;row<3;row++) for(int col=0;col<9;col++) addSlot(new SlotItemHandler(storage,1+row*9+col,8+col*18,162+row*18) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
        });
        for(int row=0;row<3;row++) for(int col=0;col<9;col++) addSlot(new Slot(inv,col+row*9+9,216+col*18,162+row*18));
        for(int col=0;col<9;col++) addSlot(new Slot(inv,col,216+col*18,220));
    }
    @Override public boolean stillValid(Player p) {
        if (terminal == null || terminal.getHost() == null || terminal.getHost().getPart(terminal.getSide()) != terminal) return false;
        var pos = terminal.getBlockEntity().getBlockPos();
        return p.level().getBlockEntity(pos) == terminal.getBlockEntity() && p.distanceToSqr(pos.getCenter()) <= 64;
    }
    @Override public ItemStack quickMoveStack(Player p,int index) {
        if(index<0||index>=slots.size()) return ItemStack.EMPTY;
        var slot=slots.get(index);if(!slot.hasItem())return ItemStack.EMPTY;
        var stack=slot.getItem();var copy=stack.copy();
        if(index<28) { if(!moveItemStackTo(stack,28,slots.size(),true))return ItemStack.EMPTY; }
        else if(!moveItemStackTo(stack,0,1,false)) return ItemStack.EMPTY;
        if(stack.isEmpty())slot.setByPlayer(ItemStack.EMPTY);else slot.setChanged();return copy;
    }
    public void action(TerminalPackets.Action a) {
        if(a.action().equals("ingredient_close")){ingredientPicker=null;sync();return;}
        if(a.action().equals("picker_close")){pickerKey=null;sync();return;}
        if(a.action().equals("tree_close")){treeOpen=false;return;}
        if(a.action().equals("providers_close")){providersOpen=false;return;}
        if(a.action().equals("jei_status")){terminal.setJeiClient(player.getUUID(),Boolean.parseBoolean(a.value()));return;}
        if(a.revision()!=terminal.revision()) { pickerKey=null;sync();return; }
        if (ingredientPicker != null && ingredientPicker.revision() != terminal.revision()) ingredientPicker = null;
        if (ingredientPicker != null) {
            switch (a.action()) {
                case "ingredient_slot", "ingredient_choose" -> { ingredientAction(a); sync(); }
            }
            return;
        }
        if(a.action().equals("picker_page")||a.action().equals("picker_confirm")) {
            if(pickerKey!=null&&pickerRevision==terminal.revision()) {
                var options=terminal.nativeOptions(pickerKey);
                if(!options.isEmpty()) {
                    pickerPage=Math.floorMod(pickerPage,options.size());
                    if(a.action().equals("picker_page"))pickerPage=Math.floorMod(pickerPage+Integer.signum(a.index()),options.size());
                    else if(options.get(pickerPage).id().equals(a.value())) {
                        terminal.chooseRecipe(pickerKey,a.value());pickerKey=null;
                    }
                }
            }
            sync();return;
        }
        var keys=terminal.keys();
        AEKey key=selected>=0&&selected<keys.size()?keys.get(selected):terminal.target();
        switch(a.action()) {
            case "tree_open" -> { if(treeReady()){treeOpen=true;sentTreeRevision=-1;} }
            case "target" -> {
                var id=ResourceLocation.tryParse(a.value());
                if(id!=null&&BuiltInRegistries.ITEM.containsKey(id)) {
                    var item=BuiltInRegistries.ITEM.get(id);if(item!=Items.AIR) {terminal.setTarget(AEItemKey.of(item));scrollOffset=0;selected=0;}
                }
            }
            case "cursor" -> { if(!getCarried().isEmpty()){ terminal.setCursorTarget(getCarried());scrollOffset=0;selected=0;} }
            case "ignore_components" -> terminal.setIgnoreTargetComponents(Boolean.parseBoolean(a.value()));
            case "substitute" -> terminal.setSubstitution(false,Boolean.parseBoolean(a.value()));
            case "substituteFluids" -> terminal.setSubstitution(true,Boolean.parseBoolean(a.value()));
            case "processing" -> terminal.toggleProcessing();
            case "autoUpload" -> terminal.setAutoUpload(Boolean.parseBoolean(a.value()));
            case "uploadTarget" -> {providersOpen=true;providerOffset=0;}
            case "provider_search" -> {providerSearch=a.value().substring(0,Math.min(80,a.value().length())).toLowerCase(Locale.ROOT);providerOffset=0;}
            case "provider_page" -> providerOffset=Math.max(0,providerOffset+Integer.signum(a.index())*6);
            case "provider_toggle" -> terminal.configureProvider(a.value(),"toggle");
            case "provider_single" -> terminal.configureProvider(a.value(),"single");
            case "provider_crafting" -> terminal.configureProvider(a.value(),"crafting");
            case "provider_continue" -> terminal.setAutoContinueProviders(Boolean.parseBoolean(a.value()));
            case "analyze" -> terminal.analyze();
            case "start" -> terminal.startOrResume();
            case "pause" -> terminal.pause();
            case "cancel" -> terminal.cancel();
            case "scroll" -> scrollOffset=Math.max(0,scrollOffset+Integer.signum(a.index()));
            case "scroll_to" -> {
                var rows=visibleRows(keys,terminal.nodes());
                scrollOffset=Math.max(0,Math.min(a.index(),Math.max(0,rows.size()-VISIBLE_ROWS)));
            }
            case "pending_scroll" -> {
                pendingVisibleRows=a.value().equals("compact")?3:PENDING_VISIBLE_ROWS;
                pendingOffset=Math.max(0,pendingOffset+Integer.signum(a.index()));
            }
            case "issue" -> {
                var path=terminal.failurePath();
                int at=path.size()>1&&path.subList(0,path.size()-1).contains(path.getLast())?path.size()-2:path.size()-1;
                locateMaterial(keys,path.isEmpty()?null:path.get(at));
            }
            case "locate_material" -> {
                if(a.index()>=0&&a.index()<keys.size()&&cachedUnresolved.contains(keys.get(a.index())))
                    locateMaterial(keys,keys.get(a.index()));
            }
            case "select" -> { if(a.index()>=0&&a.index()<keys.size()){
                selected=a.index();var n=terminal.nodes().get(keys.get(selected));
                if(n!=null&&n.choice().kind()==DependencyPlanner.Kind.NEEDS_RECIPE)openPicker(keys.get(selected));
            } }
            case "expand" -> { if(a.index()>=0&&a.index()<keys.size()) { var k=keys.get(a.index());if(!collapsed.remove(k))collapsed.add(k);rowsDirty=true; } }
            case "recipe" -> { if(key!=null) {if(!terminal.nativeOptions(key).isEmpty())openPicker(key);else terminal.nextRecipe(key);} }
            case "external" -> { if(key!=null)terminal.toggleExternal(key); }
            case "ingredient_open" -> {
                if (pickerKey == null && !providersOpen && selected == a.index()) openIngredientPicker(key);
            }
            default -> { return; }
        }
        sync();
    }
    public void bulkExternal(TerminalPackets.BulkExternal action) {
        if(action.revision()!=terminal.revision()||action.materialIds().size()>16384)return;
        var keys=terminal.keys();var chosen=new LinkedHashSet<AEKey>();
        var allowed=new HashSet<>(cachedPending);
        for(var id:action.materialIds()) {
            if(id==null||id<0||id>=keys.size())return;
            var key=keys.get(id);var node=terminal.nodes().get(key);
            boolean unresolved=cachedUnresolved.contains(key);
            boolean needsRecipe=node!=null&&node.choice().kind()==DependencyPlanner.Kind.NEEDS_RECIPE;
            if((unresolved||needsRecipe)&&allowed.contains(key))chosen.add(key);
        }
        if(!chosen.isEmpty())terminal.setExternal(chosen);
        sync();
    }
    @Override public void broadcastChanges() {
        super.broadcastChanges();
        if (player instanceof ServerPlayer serverPlayer) {
            if (++ticks % 10 == 0) sync();
            streamTree();
            streamIngredients(serverPlayer);
        }
    }
    private void streamTree() {
        if(!treeOpen||!(player instanceof ServerPlayer sp))return;
        if(sentTreeRevision!=terminal.revision()) {
            sentTreeRevision=terminal.revision();
            var ids=new HashMap<AEKey,Integer>();var keys=terminal.keys();
            for(int i=0;i<keys.size();i++)ids.put(keys.get(i),i);
            treeIds=ids;
            var layout=MindMapLayout.arrange(treeReady()?terminal.target():null,key->{
                var node=terminal.nodes().get(key);return node==null?List.of():node.children();
            });
            treeReveal.reset(layout.nodes());
            var reset=new CompoundTag();reset.putBoolean("reset",true);reset.putInt("revision",sentTreeRevision);
            reset.putInt("offset",0);reset.putInt("total",layout.nodes().size());reset.putBoolean("limited",layout.limited());reset.put("nodes",new ListTag());
            PacketDistributor.sendToPlayer(sp,new TerminalPackets.TreeBatch(containerId,reset));
        }
        if(!treeReady())return;
        // Analysis finishing keeps its revision, so initialize once its nodes become available.
        if(treeReveal.size()==0) {sentTreeRevision=-1;return;}
        var batch=treeReveal.tick();if(batch.isEmpty())return;
        var nodes=new ListTag();
        for(var row:batch) {
            var entry=new CompoundTag();var key=row.key();var node=terminal.nodes().get(key);
            entry.putInt("id",row.id());entry.putInt("materialId",treeIds.getOrDefault(key,-1));entry.putInt("parentId",row.parentId());
            entry.putString("stop",row.stop().name());entry.putInt("x",row.x());entry.putInt("y",row.y());
            entry.putString("name",key.getDisplayName().getString());
            entry.putString("kind",terminal.isGenerated(key)?"GENERATED":node==null?"PENDING":node.choice().kind().name());
            entry.putIntArray("children",row.children().stream().mapToInt(Integer::intValue).toArray());
            if(key instanceof AEItemKey item)entry.put("icon",item.toStack().save(player.registryAccess()));
            nodes.add(entry);
        }
        var tag=new CompoundTag();tag.putInt("revision",sentTreeRevision);tag.putInt("offset",treeReveal.position()-batch.size());
        tag.put("nodes",nodes);PacketDistributor.sendToPlayer(sp,new TerminalPackets.TreeBatch(containerId,tag));
    }
    private record Row(AEKey key,int depth) {}
    private List<Row> visibleRows(List<AEKey> keys,Map<AEKey,DependencyPlanner.Node<AEKey,com.likeazusa2.aeautopatterns.integration.PatternRecipe>> nodes) {
        if(!rowsDirty&&rowRevision==terminal.revision()&&rowState==terminal.state()&&rowNodeCount==nodes.size()&&rowMaterialCount==keys.size())return cachedRows;
        rowRevision=terminal.revision();rowState=terminal.state();rowNodeCount=nodes.size();rowMaterialCount=keys.size();rowsDirty=false;
        var rows=new ArrayList<Row>();flatten(terminal.target(),0,new HashSet<>(),rows);
        var ids=new HashMap<AEKey,Integer>();for(int i=0;i<keys.size();i++)ids.put(keys.get(i),i);cachedIds=ids;
        if(rowState==AutoPatternTerminalPart.State.ERROR) {
            var present=new HashSet<AEKey>();rows.forEach(row->present.add(row.key()));
            for(var k:keys)if(present.add(k))rows.add(new Row(k,0));
        }
        cachedCounts.clear();nodes.values().forEach(n->cachedCounts.merge(n.choice().kind(),1,Integer::sum));
        // Independent of collapsed dependency rows: every material awaiting a
        // native recipe selection remains reachable from the compact queue.
        cachedPending=keys.stream().filter(k->{var n=nodes.get(k);return n!=null&&n.choice().kind()==DependencyPlanner.Kind.NEEDS_RECIPE;}).toList();
        var unresolved=new LinkedHashSet<AEKey>();
        var failurePath=terminal.failurePath();
        if(rowState==AutoPatternTerminalPart.State.ERROR&&!failurePath.isEmpty()) {
            int at=failurePath.size()>1&&failurePath.subList(0,failurePath.size()-1).contains(failurePath.getLast())
                ?failurePath.size()-2:failurePath.size()-1;
            var failed=failurePath.get(at);if(ids.containsKey(failed))unresolved.add(failed);
        }
        cachedUnresolved=Set.copyOf(unresolved);
        var combined=new LinkedHashSet<AEKey>(cachedPending);combined.addAll(unresolved);cachedPending=List.copyOf(combined);
        return cachedRows=List.copyOf(rows);
    }
    private void flatten(AEKey key,int depth,Set<AEKey> seen,List<Row> rows) {
        if(key==null)return;
        var stack=new ArrayDeque<Row>();stack.push(new Row(key,depth));
        while(!stack.isEmpty()) {
            var row=stack.pop();if(!seen.add(row.key()))continue;rows.add(row);
            if(collapsed.contains(row.key()))continue;
            var n=terminal.nodes().get(row.key());
            if(n!=null)for(int i=n.children().size()-1;i>=0;i--)stack.push(new Row(n.children().get(i),row.depth()+1));
        }
    }
    public void sync() {
        if(!(player instanceof ServerPlayer sp))return;
        var tag=new CompoundTag();tag.putInt("revision",terminal.revision());tag.putString("state",terminal.state().name());
        syncIngredientPicker(tag);
        if(pickerKey!=null&&pickerRevision!=terminal.revision())pickerKey=null;
        if(pickerKey!=null) {
            var options=terminal.nativeOptions(pickerKey);
            if(!options.isEmpty()) {
                pickerPage=Math.floorMod(pickerPage,options.size());var choice=options.get(pickerPage);
                var preview=new CompoundTag();preview.putString("name",pickerKey.getDisplayName().getString());
                preview.putInt("page",pickerPage+1);preview.putInt("count",options.size());preview.putString("id",choice.id());
                var recipe=choice.payload().recipe().value();
                var ingredients=((com.likeazusa2.aeautopatterns.integration.CraftingRecipeAdapter)choice.payload().adapter()).ingredients(recipe);
                int width=recipe instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped?shaped.getWidth():3;
                var slots=new ListTag();int ordinal=0;
                for(int i=0;i<ingredients.size();i++)if(!ingredients.get(i).isEmpty()) {
                    var slot=new CompoundTag();slot.putInt("slot",(i/width)*3+i%width);var variants=new ListTag();
                    for(var candidate:choice.ingredients().get(ordinal++))if(candidate instanceof AEItemKey item)variants.add(item.toStack().save(player.registryAccess()));
                    slot.put("variants",variants);slots.add(slot);
                }
                preview.put("slots",slots);
                var output=recipe.getResultItem(player.registryAccess());
                if(pickerKey instanceof AEItemKey item) {var exact=item.toStack();exact.setCount(Math.max(1,output.getCount()));preview.put("output",exact.save(player.registryAccess()));}
                tag.put("recipePreview",preview);
            }
        }
        tag.putBoolean("substitute",terminal.substitute());tag.putBoolean("substituteFluids",terminal.substituteFluids());
        tag.putBoolean("ignoreTargetComponents",terminal.ignoreTargetComponents());
        tag.putBoolean("treeReady",treeReady());tag.putBoolean("allowProcessing",terminal.allowProcessing());
        tag.putBoolean("autoUpload",terminal.autoUpload());tag.putInt("uploadTarget",terminal.uploadTarget());
        tag.putString("uploadStatus",terminal.uploadStatus());
        var uploadTargets=terminal.uploadTargets();
        tag.putInt("uploadTargetCount",uploadTargets.size());
        tag.putBoolean("autoContinueProviders",terminal.autoContinueProviders());
        var selections=terminal.providerSelections(uploadTargets);
        var selectionOrders=new HashMap<String,Integer>();
        for(int i=0;i<selections.size();i++)selectionOrders.put(selections.get(i).id(),i+1);
        tag.putInt("selectedProviderCount",selections.size());
        if(providersOpen) {
            var filtered=PatternDeployment.groupTargets(uploadTargets).stream().filter(group->providerSearch.isEmpty()
                || group.name().toLowerCase(Locale.ROOT).contains(providerSearch)
                || group.members().stream().anyMatch(t->(PatternDeployment.location(t.container())+" "+
                    BuiltInRegistries.ITEM.getKey(PatternDeployment.icon(t.container()).getItem()))
                    .toLowerCase(Locale.ROOT).contains(providerSearch))).toList();
            providerOffset=Math.min(providerOffset,Math.max(0,(filtered.size()-1)/6*6));
            tag.putInt("providerOffset",providerOffset);tag.putInt("providerCount",filtered.size());
            var providers=new ListTag();
            for(int i=providerOffset;i<Math.min(filtered.size(),providerOffset+6);i++) {
                var group=filtered.get(i);var entry=new CompoundTag();
                var container=group.representative().container();
                int selectedCount=0,craftingCount=0,order=Integer.MAX_VALUE,free=0,slots=0;
                for(var target:group.members()) {
                    var memberId=PatternDeployment.identity(target.container());
                    var memberOrder=selectionOrders.get(memberId);
                    if(memberOrder!=null) {selectedCount++;order=Math.min(order,memberOrder);}
                    if(terminal.providerCraftingOnly(memberId))craftingCount++;
                    free+=target.freeSlots();slots+=target.container().getTerminalPatternInventory().size();
                }
                int count=group.members().size();
                entry.putString("id",group.id());entry.putString("name",group.name());entry.putInt("count",count);
                entry.putInt("free",free);entry.putInt("slots",slots);
                entry.putString("location",PatternDeployment.location(container));
                entry.putBoolean("selected",selectedCount==count);
                entry.putBoolean("selectedMixed",selectedCount>0&&selectedCount<count);
                entry.putBoolean("craftingOnly",craftingCount==count);
                entry.putBoolean("craftingMixed",craftingCount>0&&craftingCount<count);
                entry.putInt("order",selectedCount==0?0:order);
                entry.put("icon",PatternDeployment.icon(container).save(player.registryAccess()));
                providers.add(entry);
            }
            tag.put("providers",providers);
        }
        var selectedTargets=uploadTargets.stream().filter(t->selectionOrders.containsKey(PatternDeployment.identity(t.container()))).toList();
        tag.putInt("selectedOnlineProviders",selectedTargets.size());
        tag.putInt("uploadFreeSlots",selectedTargets.stream().mapToInt(com.likeazusa2.aeautopatterns.logic.PatternDeployment.Target::freeSlots).sum());
        tag.putString("adapterCapabilities",terminal.adapterCapabilities().stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(",")));
        tag.putInt("adapterDeclarations",terminal.adapterDeclarations().size());
        tag.putString("error",terminal.error());tag.putBoolean("online",terminal.online());tag.putInt("done",terminal.completed());tag.putInt("total",terminal.total());
        var metrics=terminal.analysisMetrics();
        tag.putInt("analysisJeiPending",terminal.pendingJeiCount());
        tag.putString("analysisAdapterDiagnostics",terminal.adapterDiagnostics());
        if(metrics!=null) {
            tag.putString("analysisPhase",metrics.phase());tag.putInt("analysisMaterials",metrics.materials());tag.putLong("analysisEdges",metrics.edges());
            tag.putInt("analysisComponents",metrics.components());tag.putLong("analysisAttempts",metrics.attempts());tag.putLong("analysisCacheHits",metrics.cacheHits());
            tag.putLong("analysisReused",metrics.reused());tag.putInt("analysisMillis",(int)(metrics.nanos()/1_000_000));
        }
        if(terminal.state()==AutoPatternTerminalPart.State.ERROR&&!terminal.failurePath().isEmpty()) {
            var path=terminal.failurePath();
            // A cycle repeats its entry at the end; the preceding node owns the failing recipe.
            var failed=path.get(path.size()>1&&path.subList(0,path.size()-1).contains(path.getLast())?path.size()-2:path.size()-1);
            tag.putString("issueMaterial",failed.getDisplayName().getString());
            tag.putString("issuePath",path.stream().map(k->k.getDisplayName().getString()).collect(java.util.stream.Collectors.joining(" → ")));
            tag.putString("issueRecipe",terminal.failureRecipe());
        }
        if(terminal.target()!=null)tag.putString("target",terminal.target().getDisplayName().getString());
        var keys=terminal.keys();var nodes=terminal.nodes();var rows=visibleRows(keys,nodes);var keyIds=cachedIds;
        String failurePathText=terminal.failurePath().stream().map(material->material.getDisplayName().getString())
            .collect(java.util.stream.Collectors.joining(" → "));
        pendingOffset=Math.min(pendingOffset,Math.max(0,cachedPending.size()-pendingVisibleRows));
        tag.putInt("pendingCount",cachedPending.size());tag.putInt("pendingOffset",pendingOffset);
        var pendingRows=new ListTag();
        var pendingIds=new int[cachedPending.size()];
        for(int i=0;i<cachedPending.size();i++)pendingIds[i]=keyIds.getOrDefault(cachedPending.get(i),-1);
        tag.putIntArray("pendingIds",pendingIds);
        for(int i=pendingOffset;i<Math.min(cachedPending.size(),pendingOffset+pendingVisibleRows);i++) {
            var key=cachedPending.get(i);var entry=new CompoundTag();
            entry.putInt("id",keyIds.getOrDefault(key,-1));entry.putString("name",key.getDisplayName().getString());
            entry.putBoolean("selected",keyIds.getOrDefault(key,-1)==selected);
            entry.putBoolean("unresolved",cachedUnresolved.contains(key));
            entry.putBoolean("needsRecipe",!cachedUnresolved.contains(key)&&nodes.get(key)!=null
                &&nodes.get(key).choice().kind()==DependencyPlanner.Kind.NEEDS_RECIPE);
            if(cachedUnresolved.contains(key)) {
                entry.putString("path",failurePathText);
                entry.putString("recipe",terminal.failureRecipe());
            }
            if(key instanceof AEItemKey item)entry.put("icon",item.toStack().save(player.registryAccess()));
            pendingRows.add(entry);
        }
        tag.put("pendingRows",pendingRows);
        scrollOffset=Math.min(scrollOffset,Math.max(0,rows.size()-VISIBLE_ROWS));tag.putInt("scrollOffset",scrollOffset);tag.putInt("rowCount",rows.size());
        var list=new ListTag();
        for(int i=scrollOffset;i<Math.min(rows.size(),scrollOffset+VISIBLE_ROWS);i++) {
            var row=rows.get(i);var r=new CompoundTag();var n=nodes.get(row.key());
            r.putInt("id",keyIds.getOrDefault(row.key(),-1));r.putInt("depth",row.depth());r.putString("name",row.key().getDisplayName().getString());
            r.putString("kind",terminal.isGenerated(row.key())?"GENERATED":n==null?"PENDING":n.choice().kind().name());
            r.putBoolean("children",n!=null&&!n.children().isEmpty());r.putBoolean("collapsed",collapsed.contains(row.key()));r.putBoolean("selected",keyIds.getOrDefault(row.key(),-1)==selected);
            if(row.key() instanceof AEItemKey item)r.put("icon",item.toStack().save(player.registryAccess()));list.add(r);
        }
        tag.put("rows",list);
        for(var kind:DependencyPlanner.Kind.values())tag.putInt(kind.name(),cachedCounts.getOrDefault(kind,0));
        if(!keys.isEmpty()) {
            selected=Math.min(selected,keys.size()-1);var key=keys.get(selected);var n=nodes.get(key);
            tag.putInt("selectedId",selected);
            tag.putString("selectedKind",terminal.isGenerated(key)?"GENERATED":n==null?"PENDING":n.choice().kind().name());
            tag.putString("selected",key.getDisplayName().getString());tag.putLong("stored",terminal.stored(key));
            tag.putString("recipe",n==null?"":n.choice().id().startsWith("NETWORK:")?"NETWORK":n.choice().id().startsWith("BUFFER:")?"BUFFER":n.choice().id());
            tag.putInt("recipes",terminal.options(key).size());
            if(n!=null&&n.choice().payload()!=null&&n.choice().payload().adapted()!=null) {
                var adapted=n.choice().payload().adapted();
                tag.putString("adapterId",adapted.adapterId());tag.putString("recipeId",adapted.recipeId().toString());
                tag.putString("machineType",adapted.requirements().machineType().toString());
                tag.putString("conditions",String.join("; ",adapted.requirements().conditions()));
                tag.putInt("probabilisticOutputs",adapted.probabilisticOutputs().size());
                tag.putBoolean("hasRecipeWarnings",adapted.hasWarnings());
            }
            if (n != null && !n.children().isEmpty()) {
                tag.putInt("ingredients", n.children().size());
                tag.putBoolean("canEditIngredients", treeReady() && n.choice().kind() == DependencyPlanner.Kind.GENERATE);
                if (treeReady()) {
                    var fluidSlots = terminal.fluidInputs(key);
                    tag.putBoolean("inputFluid", !fluidSlots.isEmpty());
                    var fluidIds = fluidSlots.stream().map(i -> keyIds.getOrDefault(n.children().get(i), -1))
                        .mapToInt(Integer::intValue).distinct().toArray();
                    tag.putIntArray("fluidMaterials", fluidIds);
                }
            }
        }
        PacketDistributor.sendToPlayer(sp,new TerminalPackets.Snapshot(containerId,tag));
    }
    private void locateMaterial(List<AEKey> keys,AEKey key) {
        if(key==null)return;
        int id=keys.indexOf(key);if(id<0)return;
        selected=id;collapsed.clear();rowsDirty=true;
        var visible=new ArrayList<Row>();flatten(terminal.target(),0,new HashSet<>(),visible);
        var present=new HashSet<AEKey>();visible.forEach(row->present.add(row.key()));
        for(var material:keys)if(present.add(material))visible.add(new Row(material,0));
        for(int i=0;i<visible.size();i++)if(visible.get(i).key().equals(key)){scrollOffset=i;break;}
    }
}
