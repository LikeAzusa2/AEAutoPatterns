package com.likeazusa2.aeautopatterns.part;
import com.likeazusa2.aeautopatterns.AEAutoPatterns;
import com.likeazusa2.aeautopatterns.AnalysisConfig;

import java.util.*;
import appeng.api.networking.*;
import appeng.api.parts.IPartItem;
import appeng.api.parts.IPartModel;
import appeng.api.stacks.*;
import appeng.core.AppEng;
import appeng.core.definitions.AEItems;
import appeng.items.parts.PartModels;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import appeng.parts.PartModel;
import appeng.parts.reporting.AbstractTerminalPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.items.ItemStackHandler;
import com.likeazusa2.aeautopatterns.ModContent;
import com.likeazusa2.aeautopatterns.logic.*;
import com.likeazusa2.aeautopatterns.integration.*;
import com.likeazusa2.aeautopatterns.menu.AutoPatternMenu;
import net.minecraft.world.phys.Vec3;

public final class AutoPatternTerminalPart extends AbstractTerminalPart {
    public enum State { IDLE, INDEXING, ANALYZING, VALIDATING, WAITING_SELECTION, READY, RUNNING, PAUSED, NO_BLANKS, FULL, OFFLINE, STALE, ERROR, COMPLETE }
    private static final Set<AutoPatternTerminalPart> ACTIVE = Collections.newSetFromMap(new IdentityHashMap<>());
    @PartModels public static final net.minecraft.resources.ResourceLocation MODEL_OFF = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(AEAutoPatterns.ID, "part/auto_pattern_terminal_off");
    @PartModels public static final net.minecraft.resources.ResourceLocation MODEL_ON = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(AEAutoPatterns.ID, "part/auto_pattern_terminal_on");
    @PartModels public static final net.minecraft.resources.ResourceLocation MODEL_BACKGROUND = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(AEAutoPatterns.ID, "part/auto_pattern_terminal_background");
    @PartModels public static final net.minecraft.resources.ResourceLocation MODEL_BACKGROUND_ON = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(AEAutoPatterns.ID, "part/auto_pattern_terminal_background_on");
    public static final IPartModel MODELS_OFF = new PartModel(MODEL_BASE, MODEL_BACKGROUND, MODEL_OFF, MODEL_STATUS_OFF);
    public static final IPartModel MODELS_ON = new PartModel(MODEL_BASE, MODEL_BACKGROUND_ON, MODEL_ON, MODEL_STATUS_ON);
    public static final IPartModel MODELS_HAS_CHANNEL = new PartModel(MODEL_BASE, MODEL_BACKGROUND_ON, MODEL_ON, MODEL_STATUS_HAS_CHANNEL);
    private Level level;
    private boolean allowProcessing;
    private boolean ignoreTargetComponents;
    private AEKey cursorTarget;
    private boolean autoUpload;
    private int uploadTarget;
    private boolean providerSelectionConfigured,autoContinueProviders;
    private final LinkedHashSet<String> selectedProviders=new LinkedHashSet<>();
    private final Set<String> craftingOnlyProviders=new HashSet<>();
    private String uploadStatus="";
    private boolean substitute,substituteFluids=true;
    private final Map<AEKey,Set<Integer>> fluidPreview=new HashMap<>();
    private final ItemStackHandler inventory=new ItemStackHandler(28) {
        @Override protected void onContentsChanged(int slot) { if(lookup!=null)lookup.invalidate();if(slot>0)bufferVersion++;markForSave(); }
        @Override public boolean isItemValid(int slot,ItemStack stack) { return slot==0&&AEItems.BLANK_PATTERN.is(stack); }
    };
    private AEKey target;
    private final Set<AEKey> external=new HashSet<>();
    private final Map<AEKey,String> recipeChoices=new HashMap<>();
    private final Map<AEKey,Map<Integer,AEKey>> ingredientChoices=new HashMap<>();
    private final Map<AEKey,Long> pendingJei=new HashMap<>();
    private record JeiFlight(UUID player,List<AEKey> keys,long sent) {}
    private final LinkedHashSet<AEKey> queuedJei=new LinkedHashSet<>();
    private final Map<Integer,JeiFlight> jeiFlights=new LinkedHashMap<>();
    private int nextJeiBatch;
    private boolean jeiTimedOut;
    private final Map<AEKey,List<String>> jeiRecipeIds=new HashMap<>();
    private final Set<java.util.UUID> jeiClients=new HashSet<>();
    private final LinkedHashMap<AEKey,List<DependencyPlanner.Choice<AEKey,PatternRecipe>>> discovered=new LinkedHashMap<>();
    private RecipeAdapter adapter;
    private RecipeAdapter.Settings adapterSettings;
    private int adapterReloadEpoch=-1;
    private List<DatapackAdapterSpec> adapterDeclarations=List.of();
    private PatternLookup lookup;
    private AnalysisSession<AEKey,PatternRecipe> planner;
    private List<DependencyPlanner.Node<AEKey,PatternRecipe>> queue=List.of();
    private final Set<AEKey> generated=new HashSet<>();
    private State state=State.IDLE;
    private String error="";
    private int cursor,revision,completed;
    private long topology;
    private long bufferVersion,analysisBufferVersion;
    private static final AnalysisScheduler SCHEDULER=new AnalysisScheduler();
    private Iterator<DependencyPlanner.Node<AEKey,PatternRecipe>> validation=Collections.emptyIterator();
    private Iterator<DependencyPlanner.Node<AEKey,PatternRecipe>> backgroundValidation=Collections.emptyIterator();
    private Iterator<DependencyPlanner.Node<AEKey,PatternRecipe>> capacityValidation=Collections.emptyIterator();
    private final List<ItemStack> simulatedOutput=new ArrayList<>();
    private int simulatedFree;
    private long validationBufferVersion;
    private State afterValidation=State.READY;
    private long analysisStarted,indexNanos;
    private final Map<AEKey,String> orderingKeys=new HashMap<>();
    private final Map<AEKey,Integer> materialRanks=new HashMap<>();
    private List<AEKey> keySnapshot=List.of();
    private int keyRevision=-1;
    private IGrid analyzedGrid;
    private int reloadEpoch;
    private long serverTick;
    static { NeoForge.EVENT_BUS.addListener(AutoPatternTerminalPart::serverTick); }
    public AutoPatternTerminalPart(IPartItem<?> partItem) { super(partItem); }
    public ItemStackHandler inventory() { return inventory; }
    public IManagedGridNode node() { return getMainNode(); }
    private void markForSave() {
        if (getHost() != null) getHost().markForSave();
    }
    private void setChanged() { markForSave(); }
    private static void serverTick(ServerTickEvent.Post event) {
        synchronized (ACTIVE) {
            var parts=ACTIVE.stream().filter(p->p.level!=null&&p.level.getServer()==event.getServer()).toList();
            for(var part:parts)part.serverTick++;
            SCHEDULER.tick(parts,(long)(AnalysisConfig.GLOBAL_MS.get()*1_000_000),
                (long)(AnalysisConfig.SESSION_MS.get()*1_000_000),System::nanoTime,AutoPatternTerminalPart::tick);
        }
    }
    @Override public void addToWorld() {
        super.addToWorld();
        level = getLevel();
        if (level != null && !level.isClientSide) synchronized (ACTIVE) { ACTIVE.add(this); }
    }
    @Override public void removeFromWorld() {
        synchronized (ACTIVE) { ACTIVE.remove(this); }
        super.removeFromWorld();
    }
    @Override protected void onMainNodeStateChanged(appeng.api.networking.IGridNodeListener.State reason) {
        super.onMainNodeStateChanged(reason);
        if (analyzedGrid != null && node().getGrid() != analyzedGrid) invalidatePlan();
    }
    @Override public void addAdditionalDrops(List<ItemStack> drops, boolean wrenched) {
        super.addAdditionalDrops(drops, wrenched);
        for (int i=0;i<inventory.getSlots();i++) {
            var stack=inventory.getStackInSlot(i);
            if (!stack.isEmpty()) drops.add(stack.copy());
        }
    }
    @Override public void clearContent() {
        super.clearContent();
        for (int i=0;i<inventory.getSlots();i++) inventory.setStackInSlot(i,ItemStack.EMPTY);
    }
    @Override public void readFromNBT(CompoundTag tag,HolderLookup.Provider registries) {
        super.readFromNBT(tag,registries);node().loadFromNBT(tag);inventory.deserializeNBT(registries,tag.getCompound("inventory"));
        allowProcessing=tag.getBoolean("allowProcessing");
        ignoreTargetComponents=tag.getBoolean("ignoreTargetComponents");
        cursorTarget=tag.contains("cursorTarget")?AEKey.fromTagGeneric(registries,tag.getCompound("cursorTarget")):null;
        substitute=tag.getBoolean("substitute");substituteFluids=!tag.contains("substituteFluids")||tag.getBoolean("substituteFluids");
        fluidPreview.clear();target=tag.contains("target")?AEKey.fromTagGeneric(registries,tag.getCompound("target")):null;
        external.clear();recipeChoices.clear();ingredientChoices.clear();
        for(var entry:tag.getList("rules",Tag.TAG_COMPOUND)) {
            var r=(CompoundTag)entry;var key=AEKey.fromTagGeneric(registries,r.getCompound("key"));if(key==null) continue;
            if(r.getBoolean("external")) external.add(key);if(r.contains("recipe")) recipeChoices.put(key,r.getString("recipe"));
            var inputs=new HashMap<Integer,AEKey>();for(var in:r.getList("inputs",Tag.TAG_COMPOUND)) {
                var input=(CompoundTag)in;var value=AEKey.fromTagGeneric(registries,input.getCompound("key"));if(value!=null) inputs.put(input.getInt("index"),value);
            }
            ingredientChoices.put(key,inputs);
        }
        completed=tag.getInt("completed");cursor=tag.getInt("cursor");state=target==null?State.IDLE:State.STALE;
        autoUpload=tag.getBoolean("autoUpload");uploadTarget=Math.max(0,tag.getInt("uploadTarget"));uploadStatus="";
        providerSelectionConfigured=tag.getBoolean("providerSelectionConfigured");autoContinueProviders=tag.getBoolean("autoContinueProviders");
        selectedProviders.clear();craftingOnlyProviders.clear();
        for(var entry:tag.getList("providerRules",Tag.TAG_COMPOUND)) {
            var rule=(CompoundTag)entry;var id=rule.getString("id");
            if(rule.getBoolean("selected"))selectedProviders.add(id);
            if(rule.getBoolean("craftingOnly"))craftingOnlyProviders.add(id);
        }
    }
    @Override public void writeToNBT(CompoundTag tag,HolderLookup.Provider registries) {
        super.writeToNBT(tag,registries);node().saveToNBT(tag);tag.put("inventory",inventory.serializeNBT(registries));
        tag.putBoolean("allowProcessing",allowProcessing);tag.putBoolean("autoUpload",autoUpload);
        tag.putBoolean("ignoreTargetComponents",ignoreTargetComponents);
        if(cursorTarget!=null)tag.put("cursorTarget",cursorTarget.toTagGeneric(registries));
        tag.putInt("uploadTarget",uploadTarget);tag.putBoolean("substitute",substitute);tag.putBoolean("substituteFluids",substituteFluids);
        tag.putBoolean("providerSelectionConfigured",providerSelectionConfigured);tag.putBoolean("autoContinueProviders",autoContinueProviders);
        var providerRules=new ListTag();var providerIds=new LinkedHashSet<>(selectedProviders);providerIds.addAll(craftingOnlyProviders);
        for(var id:providerIds) {
            var rule=new CompoundTag();rule.putString("id",id);rule.putBoolean("selected",selectedProviders.contains(id));
            rule.putBoolean("craftingOnly",craftingOnlyProviders.contains(id));providerRules.add(rule);
        }
        tag.put("providerRules",providerRules);
        if(target!=null) tag.put("target",target.toTagGeneric(registries));
        var rules=new ListTag();var keys=new HashSet<>(external);keys.addAll(recipeChoices.keySet());keys.addAll(ingredientChoices.keySet());
        for(var key:keys) {
            var r=new CompoundTag();r.put("key",key.toTagGeneric(registries));r.putBoolean("external",external.contains(key));
            if(recipeChoices.containsKey(key)) r.putString("recipe",recipeChoices.get(key));
            var inputs=new ListTag();ingredientChoices.getOrDefault(key,Map.of()).forEach((i,k)->{var in=new CompoundTag();in.putInt("index",i);in.put("key",k.toTagGeneric(registries));inputs.add(in);});
            r.put("inputs",inputs);rules.add(r);
        }
        tag.put("rules",rules);tag.putInt("completed",completed);tag.putInt("cursor",cursor);tag.putString("state",state.name());
    }
    @Override public boolean onUseWithoutItem(Player player, Vec3 pos) {
        if (!super.onUseWithoutItem(player, pos) && !isClientSide()) MenuOpener.open(ModContent.TERMINAL_MENU.get(), player, MenuLocators.forPart(this));
        return true;
    }
    @Override public net.minecraft.world.inventory.MenuType<?> getMenuType(Player player) { return ModContent.TERMINAL_MENU.get(); }
    @Override public IPartModel getStaticModels() { return selectModel(MODELS_OFF, MODELS_ON, MODELS_HAS_CHANNEL); }
    public int revision() { return revision; }
    public List<AEKey> keys() {
        if(keyRevision!=revision||keySnapshot.size()!=discovered.size()){keySnapshot=List.copyOf(discovered.keySet());keyRevision=revision;}
        return keySnapshot;
    }
    public AEKey target() { return target; }
    public Map<AEKey,DependencyPlanner.Node<AEKey,PatternRecipe>> nodes() { return planner==null?Map.of():planner.nodes(); }
    public List<DependencyPlanner.Choice<AEKey,PatternRecipe>> options(AEKey key) { return discovered.getOrDefault(key,List.of()); }
    public List<DependencyPlanner.Choice<AEKey,PatternRecipe>> nativeOptions(AEKey key) {
        var unique=new LinkedHashMap<String,DependencyPlanner.Choice<AEKey,PatternRecipe>>();
        for(var c:options(key))if(c.kind()==DependencyPlanner.Kind.GENERATE&&c.payload()!=null
                &&c.payload().adapter() instanceof CraftingRecipeAdapter)unique.putIfAbsent(c.id(),c);
        return List.copyOf(unique.values());
    }
    public State state() { return state; }
    public boolean isGenerated(AEKey key) { return generated.contains(key); }
    public String error() { return error; }
    public List<AEKey> failurePath() { return planner==null?List.of():planner.failurePath(); }
    public String failureRecipe() { return planner==null?"":planner.failureRecipe(); }
    public int completed() { return completed; }
    public int total() { return queue.size(); }
    public boolean online() { return node().isActive(); }
    public long stored(AEKey key) { var g=node().getGrid();return g==null?0:g.getStorageService().getCachedInventory().get(key); }
    public boolean substitute() {return substitute;}
    public boolean substituteFluids() {return substituteFluids;}
    public void setSubstitution(boolean fluids,boolean enabled) {
        if((fluids?substituteFluids:substitute)==enabled)return;
        if(fluids)substituteFluids=enabled;else substitute=enabled;
        setChanged();if(target!=null)analyze();else revision++;
    }
    /** Ask AE itself which concrete crafting slots accept fluids, just like its terminal. */
    public Set<Integer> fluidInputs(AEKey key) {
        if(!substituteFluids)return Set.of();
        return fluidPreview.computeIfAbsent(key,k->{
            var n=nodes().get(k);if(n==null)return Set.of();
            var payload=n.choice().payload();
            if(payload==null||!(payload.adapter() instanceof CraftingRecipeAdapter)
                ||!(payload.recipe().value() instanceof net.minecraft.world.item.crafting.CraftingRecipe))return Set.of();
            var encoded=payload.adapter().encode(payload,n.children(),k,level);
            var decoded=appeng.api.crafting.PatternDetailsHelper.decodePattern(encoded,level);
            var slots=new HashSet<Integer>();
            if(decoded instanceof appeng.crafting.pattern.AECraftingPattern crafting) {
                var ingredients=payload.recipe().value().getIngredients();
                int width=payload.recipe().value() instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped?shaped.getWidth():3;
                int ordinal=0;
                for(int i=0;i<ingredients.size();i++)if(!ingredients.get(i).isEmpty()) {
                    if(crafting.getValidFluid((i/width)*3+i%width)!=null)slots.add(ordinal);
                    ordinal++;
                }
            }
            return Set.copyOf(slots);
        });
    }
    public boolean allowProcessing() {return allowProcessing;}
    public boolean autoUpload() { return autoUpload; }
    public int uploadTarget() { return uploadTarget; }
    public String uploadStatus() { return uploadStatus; }
    public List<PatternDeployment.Target> uploadTargets() { return PatternDeployment.targets(node().getGrid()); }
    public List<PatternDeployment.Selection> providerSelections() {
        return providerSelections(providerSelectionConfigured ? List.of() : uploadTargets());
    }
    public List<PatternDeployment.Selection> providerSelections(List<PatternDeployment.Target> targets) {
        if(!providerSelectionConfigured) {
            if(uploadTarget>=0&&uploadTarget<targets.size())return List.of(new PatternDeployment.Selection(PatternDeployment.identity(targets.get(uploadTarget).container()),false));
        }
        return selectedProviders.stream().map(id->new PatternDeployment.Selection(id,craftingOnlyProviders.contains(id))).toList();
    }
    public boolean providerCraftingOnly(String id) {return craftingOnlyProviders.contains(id);}
    public boolean autoContinueProviders(){return autoContinueProviders;}
    public void setAutoContinueProviders(boolean enabled){autoContinueProviders=enabled;setChanged();}
    public void configureProvider(String id,String operation) {
        var targets=uploadTargets();
        var group=PatternDeployment.groupTargets(targets).stream().filter(g->g.id().equals(id)).findFirst();
        if(group.isEmpty())return;
        var ids=group.get().members().stream().map(t->PatternDeployment.identity(t.container())).toList();
        if(!providerSelectionConfigured) {
            providerSelections(targets).forEach(s->selectedProviders.add(s.id()));providerSelectionConfigured=true;
        }
        switch(operation) {
            case "toggle" -> {
                if(selectedProviders.containsAll(ids))selectedProviders.removeAll(ids);
                else selectedProviders.addAll(ids);
            }
            case "single" -> {selectedProviders.retainAll(ids);selectedProviders.addAll(ids);}
            case "crafting" -> {
                if(craftingOnlyProviders.containsAll(ids))craftingOnlyProviders.removeAll(ids);
                else craftingOnlyProviders.addAll(ids);
            }
            default -> {return;}
        }
        uploadStatus="";setChanged();
    }
    public void setAutoUpload(boolean enabled) { autoUpload=enabled; uploadStatus=""; setChanged(); }
    public void cycleUploadTarget() {
        var list=uploadTargets();
        if (list.isEmpty()) { uploadTarget=0; uploadStatus="No visible pattern providers"; }
        else uploadTarget=(uploadTarget+1)%list.size();
        setChanged();
    }
    public Set<AdapterCapability> adapterCapabilities() { return adapter==null?Set.of():adapter.capabilities(); }
    public List<DatapackAdapterSpec> adapterDeclarations() { return adapterDeclarations; }
    public void toggleProcessing() {
        allowProcessing=!allowProcessing;
        // Preserve native route choices; remove only locks on newly disabled processing routes.
        if(!allowProcessing)for(var entry:new HashMap<>(recipeChoices).entrySet()) {
            var id=net.minecraft.resources.ResourceLocation.tryParse(entry.getValue());
            if(id!=null&&level.getRecipeManager().byKey(id).map(r->r.value() instanceof net.minecraft.world.item.crafting.AbstractCookingRecipe).orElse(false)) {
                recipeChoices.remove(entry.getKey());ingredientChoices.remove(entry.getKey());
            }
        }
        setChanged();if(target!=null)analyze();else {revision++;}
    }
    public void setTarget(AEKey key) {
        cursorTarget=null;
        target=key;external.clear();recipeChoices.clear();ingredientChoices.clear();cancel();
    }
    public boolean ignoreTargetComponents() {return ignoreTargetComponents;}
    public void setCursorTarget(ItemStack stack) {
        if(stack.isEmpty())return;
        var original=AEItemKey.of(stack);
        setTarget(ignoreTargetComponents?AEItemKey.of(stack.getItem().getDefaultInstance()):original);
        cursorTarget=original;setChanged();
    }
    public void setIgnoreTargetComponents(boolean enabled) {
        if(ignoreTargetComponents==enabled)return;
        ignoreTargetComponents=enabled;
        if(cursorTarget instanceof AEItemKey item) {
            var original=cursorTarget;
            var next=enabled?AEItemKey.of(item.getItem().getDefaultInstance()):original;
            if(!Objects.equals(target,next)) {
                setTarget(next);cursorTarget=original;analyze();
            } else revision++;
        } else revision++;
        setChanged();
    }
    public void invalidatePlan() {
        if(state!=State.IDLE&&state!=State.COMPLETE) { state=State.STALE; error="";revision++;setChanged(); }
    }
    public void cancel() { fluidPreview.clear();pendingJei.clear();queuedJei.clear();jeiFlights.clear();jeiRecipeIds.clear();validation=Collections.emptyIterator();capacityValidation=Collections.emptyIterator();backgroundValidation=Collections.emptyIterator();simulatedOutput.clear();planner=null;queue=List.of();discovered.clear();cursor=0;completed=0;generated.clear();state=State.IDLE;error="";revision++;setChanged(); }
    public void analyze() {
        analyze(null);
    }
    private void analyze(AEKey changed) {
        if(target==null) return;
        fluidPreview.clear();
        pendingJei.clear();
        queuedJei.clear();jeiFlights.clear();jeiTimedOut=false;
        var previous=planner;
        boolean incremental=changed!=null&&previous!=null&&previous.done()&&unchanged()&&analysisBufferVersion==bufferVersion;
        planner=null;queue=List.of();cursor=0;completed=0;generated.clear();error="";
        validation=Collections.emptyIterator();backgroundValidation=Collections.emptyIterator();
        if(!incremental)discovered.clear();
        materialRanks.clear();orderingKeys.clear();
        analyzedGrid=node().getGrid();topology=topologyVersion();reloadEpoch=com.likeazusa2.aeautopatterns.AEAutoPatterns.reloadEpoch;
        var requestedSettings=new RecipeAdapter.Settings(allowProcessing,substitute,substituteFluids);
        if(adapter==null||!requestedSettings.equals(adapterSettings)||adapterReloadEpoch!=reloadEpoch) {
            adapter=null;
            adapterSettings=requestedSettings;adapterReloadEpoch=reloadEpoch;
            jeiRecipeIds.clear();
            incremental=false;discovered.clear();
        }
        lookup=new PatternLookup(analyzedGrid,level,inventory);
        analysisBufferVersion=bufferVersion;
        analysisStarted=System.nanoTime();indexNanos=0;
        if(incremental){planner=previous.revise(changed,this::choices).awaitOrdering(this::jeiReady).orderWith(this::orderJei);state=State.ANALYZING;}
        else state=State.INDEXING;
        revision++;setChanged();
    }
    public void setJeiClient(java.util.UUID player,boolean available) {
        if(available)jeiClients.add(player);else jeiClients.remove(player);
    }
    public void receiveJeiCandidates(int expectedRevision,AEKey key,List<String> ids) {
        if(expectedRevision!=revision||!pendingJei.containsKey(key))return;
        pendingJei.remove(key);
        jeiRecipeIds.put(key,ids.stream().filter(id->id.length()<=256).distinct().limit(256).toList());
    }
    public void receiveJeiBatch(int expectedRevision,int batch,CompoundTag data,UUID player) {
        var flight=jeiFlights.get(batch);
        if(expectedRevision!=revision||flight==null||!flight.player().equals(player)||serverTick-flight.sent()>=40)return;
        int characters=0;
        for(var raw:data.getList("entries",Tag.TAG_COMPOUND)) {
            var entry=(CompoundTag)raw;var key=AEKey.fromTagGeneric(level.registryAccess(),entry.getCompound("key"));
            if(key==null||!flight.keys().contains(key))continue;
            var ids=new ArrayList<String>();var list=entry.getList("ids",Tag.TAG_STRING);
            for(int i=0;i<Math.min(256,list.size());i++) {
                var id=list.getString(i);characters+=id.length();if(id.length()>256||characters>8192)break;ids.add(id);
            }
            receiveJeiCandidates(expectedRevision,key,ids);
        }
        for(var key:flight.keys())if(pendingJei.remove(key)!=null)jeiRecipeIds.putIfAbsent(key,List.of());
        jeiFlights.remove(batch);
    }
    private boolean jeiReady(){return pendingJei.isEmpty()&&queuedJei.isEmpty()&&jeiFlights.isEmpty();}
    private List<DependencyPlanner.Choice<AEKey,PatternRecipe>> orderJei(AEKey key,List<DependencyPlanner.Choice<AEKey,PatternRecipe>> choices) {
        var ids=jeiRecipeIds.getOrDefault(key,List.of());if(ids.isEmpty())return choices;
        var ranks=new HashMap<String,Integer>();for(int i=0;i<ids.size();i++)ranks.putIfAbsent(ids.get(i),i);
        Comparator<DependencyPlanner.Choice<AEKey,PatternRecipe>> comparator=Comparator.comparingInt(c->ranks.getOrDefault(c.id(),Integer.MAX_VALUE));
        var all=new ArrayList<>(discovered.getOrDefault(key,List.of()));all.sort(comparator);discovered.put(key,List.copyOf(all));
        var result=new ArrayList<>(choices);result.sort(comparator);return List.copyOf(result);
    }
    private ServerPlayer jeiPlayer() {
        if(level instanceof net.minecraft.server.level.ServerLevel server)for(var id:jeiClients) {
            var player=server.getPlayerByUUID(id);
            if(player instanceof ServerPlayer sp&&player.containerMenu instanceof AutoPatternMenu menu&&menu.terminal()==this)return sp;
        }
        return null;
    }
    private void flushJei() {
        var player=jeiPlayer();
        if(player==null||jeiFlights.values().stream().anyMatch(f->serverTick-f.sent()>=40))jeiTimedOut=true;
        if(jeiTimedOut) {
            for(var key:pendingJei.keySet())jeiRecipeIds.putIfAbsent(key,List.of());
            pendingJei.clear();queuedJei.clear();jeiFlights.clear();return;
        }
        while(jeiFlights.size()<2&&!queuedJei.isEmpty()) {
            var keys=new ArrayList<AEKey>();var entries=new ListTag();var it=queuedJei.iterator();
            while(it.hasNext()&&keys.size()<32) {
                var key=it.next();it.remove();keys.add(key);pendingJei.put(key,serverTick);
                var entry=new CompoundTag();entry.put("key",key.toTagGeneric(level.registryAccess()));entries.add(entry);
            }
            int batch=nextJeiBatch++;var data=new CompoundTag();data.put("entries",entries);
            jeiFlights.put(batch,new JeiFlight(player.getUUID(),List.copyOf(keys),serverTick));
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,new com.likeazusa2.aeautopatterns.network.TerminalPackets.JeiBatch(player.containerMenu.containerId,revision,batch,data,false));
        }
    }
    public void startOrResume() {
        if(state==State.READY||state==State.PAUSED) {
            error="";beginValidation(State.RUNNING);
            setChanged();
        }
    }
    public void pause() {
        if(state==State.RUNNING||state==State.NO_BLANKS||state==State.FULL||state==State.OFFLINE||state==State.VALIDATING) { state=State.PAUSED;setChanged(); }
    }
    public void toggleExternal(AEKey key) { if(!external.remove(key)) external.add(key);analyze(key); }
    public void setExternal(Collection<AEKey> keys) {
        boolean changed=false;
        for(var key:keys)changed|=external.add(key);
        if(changed)analyze();
    }
    public void nextRecipe(AEKey key) {
        var list=options(key); if(list.isEmpty()) return;
        var current=nodes().get(key);String id=current!=null?current.choice().id():recipeChoices.getOrDefault(key,list.getFirst().id());
        int at=0;for(int i=0;i<list.size();i++) if(list.get(i).id().equals(id)) at=i;
        external.remove(key);recipeChoices.put(key,list.get((at+1)%list.size()).id());ingredientChoices.remove(key);analyze(key);
    }
    public void chooseRecipe(AEKey key,String id) {
        if(nativeOptions(key).stream().noneMatch(c->c.id().equals(id)))return;
        external.remove(key);recipeChoices.put(key,id);ingredientChoices.remove(key);analyze(key);
    }
    public List<List<AEKey>> ingredientOptions(AEKey key) {
        var node = nodes().get(key);
        if (node == null || node.choice().kind() != DependencyPlanner.Kind.GENERATE) return List.of();
        // Use the original choices so a locked ingredient can still be replaced.
        return options(key).stream().filter(choice -> choice.id().equals(node.choice().id()))
            .findFirst().orElse(node.choice()).ingredients();
    }

    public boolean replaceIngredient(AEKey key, String recipeId, AEKey current, AEKey replacement) {
        var node = nodes().get(key);
        if (node == null || !node.choice().id().equals(recipeId) || current.equals(replacement)) return false;
        var groups = ingredientOptions(key);
        if (groups.size() != node.children().size()) return false;
        var slots = new ArrayList<Integer>();
        for (int slot = 0; slot < groups.size(); slot++) {
            if (!node.children().get(slot).equals(current)) continue;
            if (!groups.get(slot).contains(replacement)) return false;
            slots.add(slot);
        }
        if (slots.isEmpty()) return false;
        var selected = ingredientChoices.computeIfAbsent(key, ignored -> new HashMap<>());
        for (int slot : slots) selected.put(slot, replacement);
        recipeChoices.put(key, recipeId);
        analyze(key);
        return true;
    }
    private List<DependencyPlanner.Choice<AEKey,PatternRecipe>> choices(AEKey key) {
        if(discovered.size()>=AnalysisConfig.NODES.get()&&!discovered.containsKey(key)) throw new IllegalStateException("Candidate material limit: "+AnalysisConfig.NODES.get());
        var existing=lookup.find(key);
        var base=existing.isEmpty()?adapter.find(key):existing;
        if(existing.isEmpty()&&!base.isEmpty()&&key instanceof AEItemKey
                &&!jeiRecipeIds.containsKey(key)&&!pendingJei.containsKey(key)) {
            if(!jeiTimedOut&&jeiPlayer()!=null){pendingJei.put(key,-1L);queuedJei.add(key);}
            else jeiRecipeIds.put(key,List.of());
        }
        if(base.isEmpty()) base=List.of(new DependencyPlanner.Choice<>("external",
            key instanceof AEItemKey&&adapter.unsupported(key)?DependencyPlanner.Kind.UNSUPPORTED:DependencyPlanner.Kind.EXTERNAL,List.of(),null));
        var sorted=new ArrayList<DependencyPlanner.Choice<AEKey,PatternRecipe>>();
        for(var c:base) {
            var groups=new ArrayList<List<AEKey>>();
            for(var group:c.ingredients()) {
                var variants=new ArrayList<>(group);
                variants.sort(Comparator.comparingInt((AEKey k)->materialRanks.computeIfAbsent(k,v->lookup.existsCached(v)?0:adapter.hasRecipe(v)?1:2))
                    .thenComparing(k->orderingKeys.computeIfAbsent(k,v->v.toTagGeneric(level.registryAccess()).toString())));
                groups.add(List.copyOf(variants));
            }
            sorted.add(new DependencyPlanner.Choice<>(c.id(),c.kind(),List.copyOf(groups),c.payload()));
        }
        var preferred=jeiRecipeIds.get(key);
        if(preferred!=null&&!preferred.isEmpty()) {
            var ranks=new HashMap<String,Integer>();for(int i=0;i<preferred.size();i++)ranks.put(preferred.get(i),i);
            sorted.sort(Comparator.comparingInt(c->ranks.getOrDefault(c.id(),Integer.MAX_VALUE)));
            if(sorted.stream().noneMatch(c->ranks.containsKey(c.id()))) sorted.sort(Comparator.comparing(DependencyPlanner.Choice::id));
        }
        discovered.put(key,List.copyOf(sorted));
        if(external.contains(key)) return List.of(new DependencyPlanner.Choice<>("manual_external",DependencyPlanner.Kind.EXTERNAL,List.of(),null));
        // Deployed patterns take precedence, without discarding the player's saved native route.
        if(!existing.isEmpty())return sorted;
        var chosen=recipeChoices.get(key);
        if(chosen!=null&&!sorted.stream().map(DependencyPlanner.Choice::id).toList().contains(chosen)) {
            recipeChoices.remove(key);ingredientChoices.remove(key);chosen=null;
        }
        if(existing.isEmpty()&&chosen==null&&nativeOptions(key).stream().map(DependencyPlanner.Choice::id).distinct().count()>1)
            return List.of(new DependencyPlanner.Choice<>("choose_recipe",DependencyPlanner.Kind.NEEDS_RECIPE,List.of(),null));
        if(chosen!=null) {var selectedRecipe=chosen;sorted.removeIf(c->!c.id().equals(selectedRecipe));}
        var forced=ingredientChoices.getOrDefault(key,Map.of());
        if(!forced.isEmpty()) {
            var adjusted=new ArrayList<DependencyPlanner.Choice<AEKey,PatternRecipe>>();
            for(var c:sorted) {
                var groups=new ArrayList<List<AEKey>>();
                for(int i=0;i<c.ingredients().size();i++) {
                    var override=forced.get(i);var group=c.ingredients().get(i);
                    groups.add(override==null?group:group.contains(override)?List.of(override):List.of());
                }
                adjusted.add(new DependencyPlanner.Choice<>(c.id(),c.kind(),groups,c.payload()));
            }
            return adjusted;
        }
        return sorted;
    }
    private long topologyVersion() {
        var grid=node().getGrid();return grid==null?-1:grid.getService(AnalysisGridService.class).version();
    }
    private boolean unchanged() {
        return reloadEpoch==com.likeazusa2.aeautopatterns.AEAutoPatterns.reloadEpoch&&node().getGrid()==analyzedGrid&&topology==topologyVersion();
    }
    private boolean nodeUnchanged(DependencyPlanner.Node<AEKey,PatternRecipe> n) {
        var payload=n.choice().payload();
        if(payload!=null&&payload.recipe()!=null&&level.getRecipeManager().byKey(payload.recipe().id()).orElse(null)!=payload.recipe())return false;
        return (n.choice().kind()!=DependencyPlanner.Kind.NETWORK&&n.choice().kind()!=DependencyPlanner.Kind.BUFFER)
            ||lookup.find(n.key()).stream().anyMatch(c->c.id().equals(n.choice().id()));
    }
    private void beginValidation(State next) {
        validation=nodes().values().iterator();afterValidation=next;state=State.VALIDATING;
        validationBufferVersion=bufferVersion;simulatedOutput.clear();simulatedFree=27;
        capacityValidation=Collections.emptyIterator();
        if(next==State.RUNNING&&!autoUpload) {
            for(int i=1;i<inventory.getSlots();i++)if(!inventory.getStackInSlot(i).isEmpty()) {
                simulatedOutput.add(inventory.getStackInSlot(i).copy());simulatedFree--;
            }
            capacityValidation=queue.subList(cursor,queue.size()).iterator();
        }
    }
    public AnalysisSession.Metrics analysisMetrics(){return planner==null?null:planner.metrics();}
    public long indexNanos(){return indexNanos;}
    public int pendingJeiCount(){return pendingJei.size();}
    public String adapterDiagnostics() {
        if(!(adapter instanceof CompositeRecipeAdapter composite))return "";
        var slow=composite.timings().entrySet().stream().filter(e->e.getValue().maxNanos()>4_000_000)
            .max(Comparator.comparingLong(e->e.getValue().maxNanos()));
        var lines=new ArrayList<String>();
        slow.ifPresent(e->lines.add(e.getKey()+": max "+String.format(java.util.Locale.ROOT,"%.2fms",e.getValue().maxNanos()/1_000_000.0)));
        composite.failures().entrySet().stream().limit(4).forEach(e->lines.add(e.getKey()+": "+e.getValue()));
        return String.join("; ",lines);
    }
    public void tick(){serverTick++;tick(System.nanoTime()+2_000_000);}
    private void tick(long deadline) {
        if(state==State.IDLE||state==State.COMPLETE||state==State.ERROR||state==State.STALE) return;
        try {
            if(!online()) {
                if(state==State.RUNNING||state==State.NO_BLANKS||state==State.FULL) state=State.OFFLINE;
                return;
            }
            if(!unchanged()) { invalidatePlan();return; }
            if((state==State.INDEXING||state==State.ANALYZING)&&analysisBufferVersion!=bufferVersion){invalidatePlan();return;}
            if(state==State.INDEXING) {
                long started=System.nanoTime();
                if(adapter==null) {
                    adapter=AdapterRegistry.create(level,adapterSettings);adapterDeclarations=AdapterRegistry.declarations(level);
                    long elapsed=System.nanoTime()-started;
                    if(elapsed>4_000_000)org.slf4j.LoggerFactory.getLogger("aeautopatterns.analysis.adapters").warn("Adapter initialization: {}ms (soft tick budget)",elapsed/1_000_000.0);
                    indexNanos+=elapsed;return;
                }
                do {
                    if(adapter.indexStep(1)) { planner=new AnalysisSession<>(target,this::choices,recipeChoices::containsKey,AnalysisConfig.limits()).awaitOrdering(this::jeiReady).orderWith(this::orderJei);state=State.ANALYZING;break; }
                } while(System.nanoTime()<deadline);
                indexNanos+=System.nanoTime()-started;
                return;
            }
            if(state==State.ANALYZING) {
                planner.step(deadline);
                flushJei();
                if(planner.done()) {
                    discovered.keySet().retainAll(planner.materialKeys());
                    if(!planner.error().isEmpty()) { error=planner.error();state=State.ERROR; }
                    else if(planner.nodes().values().stream().anyMatch(n->n.choice().kind()==DependencyPlanner.Kind.UNSUPPORTED)) {
                        error="Unsupported recipe. Select an external input or another recipe.";state=State.ERROR;
                    } else if(planner.nodes().values().stream().anyMatch(n->n.choice().kind()==DependencyPlanner.Kind.NEEDS_RECIPE)) {
                        queue=List.of();state=State.WAITING_SELECTION;
                    } else { queue=planner.queue();beginValidation(State.READY); }
                    org.slf4j.LoggerFactory.getLogger("aeautopatterns.analysis").info("Analysis {}: {}, index={}ms elapsed={}ms",target,planner.metrics(),indexNanos/1_000_000.0,(System.nanoTime()-analysisStarted)/1_000_000.0);
                    revision++;setChanged();
                }
                return;
            }
            if(state==State.VALIDATING) {
                if(validationBufferVersion!=bufferVersion){beginValidation(afterValidation);return;}
                for(int i=0;i<64&&validation.hasNext()&&System.nanoTime()<deadline;i++)
                    if(!nodeUnchanged(validation.next())){invalidatePlan();return;}
                if(!validation.hasNext()) {
                    for(int i=0;i<64&&capacityValidation.hasNext()&&System.nanoTime()<deadline;i++)
                        if(!reserveOutput(capacityValidation.next())) {
                            state=State.FULL;error="Output buffer is too small for the remaining patterns. Remove encoded patterns and resume.";setChanged();return;
                        }
                    if(!capacityValidation.hasNext()){state=afterValidation;setChanged();}
                }
                return;
            }
            if(!backgroundValidation.hasNext())backgroundValidation=nodes().values().iterator();
            for(int i=0;i<64&&backgroundValidation.hasNext()&&System.nanoTime()<deadline;i++)
                if(!nodeUnchanged(backgroundValidation.next())){invalidatePlan();return;}
            if(System.nanoTime()>=deadline)return;
            if(state==State.RUNNING||state==State.NO_BLANKS||state==State.FULL||state==State.OFFLINE) encodeNext();
        } catch(RuntimeException ex) { state=State.ERROR;error=Objects.toString(ex.getMessage(),ex.getClass().getSimpleName());revision++;setChanged(); }
    }
    private void encodeNext() {
        if(cursor>=queue.size()) { state=State.COMPLETE;setChanged();return; }
        var n=queue.get(cursor);
        if(!nodeUnchanged(n)){invalidatePlan();return;}
        if(lookup.exists(n.key())) { cursor++;completed++;setChanged();return; }
        if(inventory.getStackInSlot(0).isEmpty()) { state=State.NO_BLANKS;return; }
        var payload=n.choice().payload();
        var encoded=payload.adapter().encode(payload,n.children(),n.key(),level);
        if(autoUpload) {
            var result=PatternDeployment.deploy(node().getGrid(),providerSelections(),autoContinueProviders,encoded,level);
            if(result==PatternDeployment.Result.INSERTED || result==PatternDeployment.Result.DUPLICATE) {
                if(result==PatternDeployment.Result.INSERTED) inventory.extractItem(0,1,false);
                generated.add(n.key()); cursor++; completed++; state=State.RUNNING;
                uploadStatus=result==PatternDeployment.Result.DUPLICATE?"Pattern already installed":"Uploaded to provider";
                error=""; setChanged(); return;
            }
            uploadStatus=switch(result) {
                case NO_TARGET -> "No visible pattern provider";
                case FULL -> "Selected pattern provider is full";
                case OFFLINE -> "Pattern provider network is offline";
                default -> "Upload waiting";
            };
            state=result==PatternDeployment.Result.FULL?State.FULL:State.OFFLINE;
            error=uploadStatus; setChanged(); return;
        }
        int slot=-1;
        for(int i=1;i<28;i++) {
            var stack=inventory.getStackInSlot(i);
            if(stack.isEmpty()||(ItemStack.isSameItemSameComponents(stack,encoded)&&stack.getCount()<stack.getMaxStackSize())) { slot=i;break; }
        }
        if(slot<0) { state=State.FULL;error="Output buffer is full. Remove encoded patterns to continue.";return; }
        // Both inventories are local and this entire commit runs on the server thread.
        var output=inventory.getStackInSlot(slot).copy();
        if(output.isEmpty()) output=encoded;else output.grow(1);
        inventory.extractItem(0,1,false);inventory.setStackInSlot(slot,output);
        generated.add(n.key());cursor++;completed++;state=State.RUNNING;error="";setChanged();
    }
    /** One preflight reservation per scheduling step, without consuming any inventory. */
    private boolean reserveOutput(DependencyPlanner.Node<AEKey,PatternRecipe> entry) {
        if(lookup.exists(entry.key()))return true;
        var payload=entry.choice().payload();
        var encoded=payload.adapter().encode(payload,entry.children(),entry.key(),level);
        for(var stack:simulatedOutput)if(ItemStack.isSameItemSameComponents(stack,encoded)&&stack.getCount()<stack.getMaxStackSize()) {
            stack.grow(1);return true;
        }
        if(simulatedFree==0)return false;
        simulatedFree--;simulatedOutput.add(encoded.copy());
        return true;
    }
}
