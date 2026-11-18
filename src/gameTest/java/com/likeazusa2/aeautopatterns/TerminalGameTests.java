package com.likeazusa2.aeautopatterns;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.gametest.*;
import appeng.api.stacks.*;
import appeng.api.crafting.*;
import appeng.core.definitions.*;
import com.likeazusa2.aeautopatterns.part.AutoPatternTerminalPart;
import com.likeazusa2.aeautopatterns.integration.*;
import com.likeazusa2.aeautopatterns.logic.*;

@GameTestHolder(AEAutoPatterns.ID)
@PrefixGameTestTemplate(false)
public final class TerminalGameTests {
    private static final BlockPos TERMINAL=new BlockPos(1,2,1);
    private AutoPatternTerminalPart setup(GameTestHelper h) {
        appeng.api.parts.PartHelper.setPart(h.getLevel(),h.absolutePos(TERMINAL),null,null,AEParts.GLASS_CABLE.item(appeng.api.util.AEColor.TRANSPARENT));
        h.setBlock(TERMINAL.below(),AEBlocks.CREATIVE_ENERGY_CELL.block());
        return appeng.api.parts.PartHelper.setPart(h.getLevel(),h.absolutePos(TERMINAL),net.minecraft.core.Direction.NORTH,null,ModContent.TERMINAL.get());
    }
    private void check(boolean condition,String message) { if(!condition)throw new GameTestAssertException(message); }
    @GameTest(template="empty",timeoutTicks=400)
    public void providerRoutingFiltersAndContinuesWithStableSelections(GameTestHelper h) {
        var be=setup(h);
        h.setBlock(TERMINAL.east(),AEBlocks.PATTERN_PROVIDER.block());
        h.setBlock(TERMINAL.east(2),AEBlocks.PATTERN_PROVIDER.block());
        h.setBlock(TERMINAL.east(3),AEBlocks.PATTERN_PROVIDER.block());
        var first=(appeng.blockentity.crafting.PatternProviderBlockEntity)h.getBlockEntity(TERMINAL.east());
        var second=(appeng.blockentity.crafting.PatternProviderBlockEntity)h.getBlockEntity(TERMINAL.east(2));
        var unused=(appeng.blockentity.crafting.PatternProviderBlockEntity)h.getBlockEntity(TERMINAL.east(3));
        var firstId=PatternDeployment.identity(first);var secondId=PatternDeployment.identity(second);
        h.startSequence().thenWaitUntil(()->check(be.online()&&be.uploadTargets().size()==3,"Three providers online"))
            .thenExecute(()->{
                be.configureProvider(firstId,"single");be.configureProvider(secondId,"toggle");be.configureProvider(secondId,"crafting");
                check(be.providerSelections().size()==2,"Multi-selection contains exactly two providers");
                check(be.providerSelections().getFirst().id().equals(firstId),"Selection preserves upload order");
                var adapter=new CraftingRecipeAdapter(h.getLevel(),false,false,false);while(!adapter.indexStep(64)){}
                var key=AEItemKey.of(Items.CRAFTING_TABLE);var choice=adapter.find(key).getFirst();
                var pattern=adapter.encode(choice.payload(),choice.ingredients().stream().map(List::getFirst).toList(),key,h.getLevel());
                var inv=first.getTerminalPatternInventory();
                for(int i=0;i<inv.size();i++)inv.setItemDirect(i,new ItemStack(Items.DIRT));
                var grid=be.node().getGrid();
                check(PatternDeployment.deploy(grid,be.providerSelections(),false,pattern,h.getLevel())==PatternDeployment.Result.FULL,"Disabled continuation stops at full first provider");
                be.setAutoContinueProviders(true);
                check(PatternDeployment.deploy(grid,be.providerSelections(),true,pattern,h.getLevel())==PatternDeployment.Result.INSERTED,"Crafting pattern continues into selected crafting-only provider");
                check(PatternDeployment.deploy(grid,be.providerSelections(),true,pattern,h.getLevel())==PatternDeployment.Result.DUPLICATE,"Repeat upload does not duplicate in fallback provider");
                for(var nativeKey:List.of(AEItemKey.of(Items.STONE_SLAB),AEItemKey.of(Items.NETHERITE_PICKAXE))) {
                    var nativeChoice=adapter.find(nativeKey).stream().filter(c->c.payload().recipe().value() instanceof net.minecraft.world.item.crafting.StonecutterRecipe
                        || c.payload().recipe().value() instanceof net.minecraft.world.item.crafting.SmithingTransformRecipe).findFirst().orElseThrow();
                    var nativePattern=adapter.encode(nativeChoice.payload(),nativeChoice.ingredients().stream().map(List::getFirst).toList(),nativeKey,h.getLevel());
                    check(PatternDeployment.deploy(grid,be.providerSelections(),true,nativePattern,h.getLevel())==PatternDeployment.Result.INSERTED,"Native stonecutting and smithing patterns can continue into crafting-only providers");
                }
                check(PatternDeployment.freeSlots(unused)==unused.getTerminalPatternInventory().size(),"Unselected provider is untouched");
                var processing=PatternDetailsHelper.encodeProcessingPattern(List.of(new GenericStack(AEItemKey.of(Items.RAW_IRON),1)),List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT),1)));
                check(PatternDeployment.deploy(grid,be.providerSelections(),true,processing,h.getLevel())==PatternDeployment.Result.FULL,"Processing pattern cannot fall through crafting-only target");
                be.configureProvider(firstId,"crafting");
                check(PatternDeployment.deploy(grid,be.providerSelections(),true,processing,h.getLevel())==PatternDeployment.Result.NO_TARGET,"Only crafting-only targets excludes processing entirely");
                check(PatternDeployment.deploy(grid,List.of(new PatternDeployment.Selection("missing",false)),true,pattern,h.getLevel())==PatternDeployment.Result.NO_TARGET,"Missing selection cannot silently redirect to another provider");
                var tag=new net.minecraft.nbt.CompoundTag();be.writeToNBT(tag,h.getLevel().registryAccess());var loaded=restore(h,tag);
                check(loaded.autoContinueProviders()&&loaded.providerSelections().equals(be.providerSelections()),"Selection order, restrictions and master toggle persist");
                be.configureProvider(firstId,"single");check(be.providerSelections().size()==1,"Single-select replaces previous multi-selection");
                be.configureProvider(firstId,"toggle");check(be.providerSelections().isEmpty(),"Explicit empty selection does not fall back to legacy target");
            }).thenSucceed();
    }
    @GameTest(template="empty",timeoutTicks=500)
    public void terminalUploadsToFallbackProviderWithoutLosingBlanks(GameTestHelper h) {
        var be=setup(h);var target=AEItemKey.of(Items.CRAFTING_TABLE);
        h.setBlock(TERMINAL.east(),AEBlocks.PATTERN_PROVIDER.block());h.setBlock(TERMINAL.east(2),AEBlocks.PATTERN_PROVIDER.block());
        var first=(appeng.blockentity.crafting.PatternProviderBlockEntity)h.getBlockEntity(TERMINAL.east());
        var second=(appeng.blockentity.crafting.PatternProviderBlockEntity)h.getBlockEntity(TERMINAL.east(2));
        be.setTarget(target);be.inventory().setStackInSlot(0,AEItems.BLANK_PATTERN.stack(8));
        h.startSequence().thenWaitUntil(()->check(be.online()&&be.uploadTargets().size()==2,"Two providers online"))
            .thenExecute(()->{
                be.configureProvider(PatternDeployment.identity(first),"single");be.configureProvider(PatternDeployment.identity(second),"toggle");
                be.configureProvider(PatternDeployment.identity(second),"crafting");be.setAutoUpload(true);
                var inv=first.getTerminalPatternInventory();for(int i=0;i<inv.size();i++)inv.setItemDirect(i,new ItemStack(Items.DIRT));
                be.analyze();
            }).thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"Analysis ready"))
            .thenExecute(()->be.setExternal(List.of(be.nodes().get(target).children().getFirst())))
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"External base applied"))
            .thenExecute(be::startOrResume)
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.FULL,"Full selected provider pauses"))
            .thenExecute(()->{
                check(be.inventory().getStackInSlot(0).getCount()==8,"Full provider cannot consume blanks");
                be.setAutoContinueProviders(true);
            }).thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.COMPLETE,"Enabling continuation resumes upload"))
            .thenExecute(()->{
                check(be.inventory().getStackInSlot(0).getCount()==7,"Exactly one inserted pattern consumes one blank");
                check(PatternDeployment.freeSlots(second)==second.getTerminalPatternInventory().size()-1,"Fallback holds exactly one pattern");
                for(int i=1;i<=AutoPatternTerminalPart.OUTPUT_PATTERN_SLOTS;i++)check(be.inventory().getStackInSlot(i).isEmpty(),"Automatic upload does not also fill local buffer");
            }).thenSucceed();
    }
    @GameTest(template="empty",timeoutTicks=400)
    public void ignoringCursorComponentsUsesDefaultAndRestoresOriginal(GameTestHelper h) {
        var be=setup(h);var stack=new ItemStack(Items.CRAFTING_TABLE);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Charged custom target"));
        var data=new net.minecraft.nbt.CompoundTag();data.putLong("energy",123456);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(data));
        var exact=AEItemKey.of(stack);var normal=AEItemKey.of(Items.CRAFTING_TABLE);
        check(!exact.equals(normal),"Fixture has additional target components");
        check(!be.ignoreTargetComponents(),"Exact mode is the default");
        be.setCursorTarget(stack);check(be.target().equals(exact),"Default cursor selection preserves exact components");
        be.inventory().setStackInSlot(0,AEItems.BLANK_PATTERN.stack(8));
        h.startSequence().thenWaitUntil(()->check(be.online(),"Network ready"))
            .thenExecute(()->be.setIgnoreTargetComponents(true))
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"Default component target analyzes successfully"))
            .thenExecute(()->{
                check(be.target().equals(normal),"Target uses default item components");
                check(AEItemKey.of(stack).equals(exact),"Cursor stack remains unchanged");
                check(be.inventory().getStackInSlot(0).getCount()==8,"Changing target options consumes no blanks");
                var tag=new net.minecraft.nbt.CompoundTag();be.writeToNBT(tag,h.getLevel().registryAccess());
                var loaded=restore(h,tag);
                check(loaded.ignoreTargetComponents()&&loaded.target().equals(normal),"Setting and normalized target persist");
                loaded.setIgnoreTargetComponents(false);
                check(loaded.target().equals(exact),"Turning off after loading restores original cursor components");
                be.setIgnoreTargetComponents(false);
                check(be.target().equals(exact),"Turning off restores exact target");
                be.setTarget(normal);be.setIgnoreTargetComponents(true);be.setIgnoreTargetComponents(false);
                check(be.target().equals(normal),"Search selection clears previous cursor source");
            }).thenSucceed();
    }
    @GameTest(template="empty",timeoutTicks=400)
    public void bulkExternalReanalyzesAllChangedMaterialsOnce(GameTestHelper h) {
        var be=setup(h);var target=AEItemKey.of(Items.CRAFTING_TABLE);
        be.setTarget(target);be.inventory().setStackInSlot(0,AEItems.BLANK_PATTERN.stack(12));
        var changed=new ArrayList<AEKey>();int[] before={0};
        h.startSequence().thenWaitUntil(()->check(be.online(),"Network ready"))
            .thenExecute(be::analyze)
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"Initial table analysis ready"))
            .thenExecute(()->{
                var materials=be.nodes().get(target).children();
                var plank=materials.getFirst();var child=be.nodes().get(plank).children().getFirst();
                changed.add(plank);changed.add(child);before[0]=be.revision();
                be.setExternal(List.of(plank,child,plank));
                check(be.revision()==before[0]+1,"Batch analyzes once despite repeated material");
            })
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"Batch analysis ready"))
            .thenExecute(()->{
                check(be.nodes().get(changed.getFirst()).choice().id().equals("manual_external"),"Changed material cannot reuse old generated subtree");
                check(be.total()==1,"Only the crafting table remains to encode");
                check(be.inventory().getStackInSlot(0).getCount()==12,"Batch choice consumes no blanks");
                var tag=new net.minecraft.nbt.CompoundTag();be.writeToNBT(tag,h.getLevel().registryAccess());
                long saved=java.util.stream.StreamSupport.stream(tag.getList("rules",10).spliterator(),false)
                    .map(net.minecraft.nbt.CompoundTag.class::cast).filter(rule->rule.getBoolean("external")).count();
                check(saved==2,"All unique external choices persist including now-hidden descendant");
            }).thenSucceed();
    }
    private AutoPatternTerminalPart restore(GameTestHelper h,net.minecraft.nbt.CompoundTag tag) {
        // AE config deserialization calls the host's save callback, so restore an
        // installed Part just as CableBusBlockEntity does, never a hostless instance.
        var restored=appeng.api.parts.PartHelper.setPart(h.getLevel(),h.absolutePos(TERMINAL.above(2)),net.minecraft.core.Direction.NORTH,null,ModContent.TERMINAL.get());
        restored.readFromNBT(tag,h.getLevel().registryAccess());return restored;
    }
    @GameTest(template="empty",timeoutTicks=400)
    public void nativeAmbiguityWaitsForExplicitSelection(GameTestHelper h) {
        var be=setup(h);var key=AEItemKey.of(Items.SANDSTONE_SLAB);
        be.setTarget(key);be.inventory().setStackInSlot(0,AEItems.BLANK_PATTERN.stack(16));
        h.startSequence().thenWaitUntil(()->check(be.online(),"Network online"))
            .thenExecute(be::analyze)
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.WAITING_SELECTION,"Wait for native recipe selection: "+be.state()))
            .thenExecute(()->{
                check(be.nativeOptions(key).size()>1,"Crafting and stonecutting candidates must be available");
                check(be.nodes().get(key).choice().kind()==DependencyPlanner.Kind.NEEDS_RECIPE,"Root must be visibly marked");
                be.startOrResume();check(be.state()==AutoPatternTerminalPart.State.WAITING_SELECTION,"Cannot start unresolved plan");
                be.chooseRecipe(key,"invalid:recipe");check(be.state()==AutoPatternTerminalPart.State.WAITING_SELECTION,"Invalid recipe must not commit");
                var choice=be.nativeOptions(key).stream().filter(c->c.payload().recipe().value() instanceof net.minecraft.world.item.crafting.StonecutterRecipe).findFirst().orElseThrow();
                be.chooseRecipe(key,choice.id());
            })
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"Confirmed native route ready: "+be.state()+" "+be.error()))
            .thenExecute(()->{
                check(be.inventory().getStackInSlot(0).getCount()==16,"Analysis never consumes blanks");
                check(be.nodes().get(key).choice().payload().recipe().value() instanceof net.minecraft.world.item.crafting.StonecutterRecipe,"Explicit route retained");
                be.analyze();
            })
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"Choice survives reanalysis"))
            .thenSucceed();
    }
    @GameTest(template="empty",timeoutTicks=40)
    public void genericAdaptedRecipeKeepsFluidAndMultiOutputMetadata(GameTestHelper h) {
        var water=AEFluidKey.of(net.minecraft.world.level.material.Fluids.WATER);
        var recipe=new AdaptedRecipe("test",ResourceLocation.parse("test:fluid_machine"),RecipeKind.MACHINE,
            List.of(new StackSpec(water,1000,StackRole.CONSUMED),new StackSpec(AEItemKey.of(Items.IRON_INGOT),1,StackRole.CATALYST)),
            List.of(new StackSpec(AEItemKey.of(Items.GOLD_INGOT),2,StackRole.CONSUMED),new StackSpec(water,250,StackRole.RETURNED)),
            List.of(new ChanceOutput(AEItemKey.of(Items.GOLD_NUGGET),1,.25f)),
            new MachineRequirements(ResourceLocation.parse("test:machine"),List.of("temperature >= 500")),null);
        var adapter=new GenericProcessingAdapter("test",20,List.of(recipe));
        var choices=adapter.find(AEItemKey.of(Items.GOLD_INGOT));
        check(choices.size()==1,"Generic adapter must index primary output");
        check(choices.getFirst().ingredients().size()==2,"All machine inputs must be visible");
        check(choices.getFirst().payload().adapted().outputs().size()==2,"All deterministic outputs must be retained");
        check(choices.getFirst().payload().adapted().probabilisticOutputs().size()==1,"Chance output must remain metadata");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=400)
    public void terminalEncodesAndDeduplicates(GameTestHelper h) {
        var be=setup(h);be.inventory().setStackInSlot(0,AEItems.BLANK_PATTERN.stack(16));
        be.setTarget(AEItemKey.of(Items.CHEST));
        h.startSequence().thenWaitUntil(()->check(be.online(),"Terminal must connect to AE grid"))
            .thenExecute(be::analyze).thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"Plan: "+be.state()+" "+be.error()))
            .thenExecute(()->{
                check(be.total()>0,"Must plan chest and intermediate patterns");
                check(be.nodes().values().stream().anyMatch(n->n.choice().kind()==DependencyPlanner.Kind.EXTERNAL),"Must list base materials");
                be.startOrResume();
            }).thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.COMPLETE,"Encoding: "+be.state()+" "+be.error()))
            .thenExecute(()->{
                int count=0;for(int i=1;i<=AutoPatternTerminalPart.OUTPUT_PATTERN_SLOTS;i++) {
                    var stack=be.inventory().getStackInSlot(i);count+=stack.getCount();
                    if(!stack.isEmpty())check(PatternDetailsHelper.decodePattern(stack,h.getLevel())!=null,"Pattern must decode");
                }
                check(count==16-be.inventory().getStackInSlot(0).getCount(),"Exactly one blank consumed per output");
                check(new PatternLookup(be.node().getGrid(),h.getLevel(),be.inventory()).exists(AEItemKey.of(Items.CHEST)),"Chest must exist in buffer");
                be.analyze();
            }).thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"Reanalysis must finish"))
            .thenExecute(()->check(be.total()==0,"Buffered root must skip encoding AND still traverse existing dependencies"))
            .thenSucceed();
    }
    @GameTest(template="empty",timeoutTicks=400)
    public void shortagePauseCancelAndReload(GameTestHelper h) {
        var be=setup(h);be.setTarget(AEItemKey.of(Items.CRAFTING_TABLE));
        h.startSequence().thenWaitUntil(()->check(be.online(),"Network online"))
            .thenExecute(be::analyze).thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"Ready: "+be.error()))
            .thenExecute(be::startOrResume).thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.NO_BLANKS,"Must wait for blanks"))
            .thenExecute(()->{
                be.pause();check(be.state()==AutoPatternTerminalPart.State.PAUSED,"Manual pause");
                be.inventory().setStackInSlot(0,AEItems.BLANK_PATTERN.stack(16));
            }).thenIdle(3).thenExecute(()->{
                check(be.inventory().getStackInSlot(0).getCount()==16,"Paused task must not encode");be.startOrResume();
            }).thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.COMPLETE,"Resumed task finishes"))
            .thenExecute(()->{
                var tag=new net.minecraft.nbt.CompoundTag();be.writeToNBT(tag,h.getLevel().registryAccess());
                var restored=restore(h,tag);
                check(restored.state()==AutoPatternTerminalPart.State.STALE,"Reload requires reanalysis");
                check(restored.inventory().getStackInSlot(0).getCount()==be.inventory().getStackInSlot(0).getCount(),"Inventory persists");
                be.cancel();check(!be.inventory().getStackInSlot(1).isEmpty(),"Cancel preserves encoded outputs");
            }).thenSucceed();
    }
    @GameTest(template="empty",timeoutTicks=400,batch="reload")
    public void fullOutputAndDatapackInvalidation(GameTestHelper h) {
        var be=setup(h);be.setTarget(AEItemKey.of(Items.STICK));be.inventory().setStackInSlot(0,AEItems.BLANK_PATTERN.stack(16));
        for(int i=1;i<=AutoPatternTerminalPart.OUTPUT_PATTERN_SLOTS;i++)be.inventory().setStackInSlot(i,new ItemStack(Items.COBBLESTONE,64));
        h.startSequence().thenWaitUntil(()->check(be.online(),"Network online"))
            .thenExecute(be::analyze)
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.WAITING_SELECTION,"Sticks have plank and bamboo recipes"))
            .thenExecute(()->be.chooseRecipe(AEItemKey.of(Items.STICK),"minecraft:stick"))
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"Ready"))
            .thenExecute(be::startOrResume).thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.FULL,"Must pause when full"))
            .thenExecute(()->{
                check(be.inventory().getStackInSlot(0).getCount()==16,"Full output must not consume blanks");
                be.inventory().setStackInSlot(1,ItemStack.EMPTY);
            }).thenWaitUntil(()->check(be.inventory().getStackInSlot(0).getCount()<16,"Automatically resumes when space is available"))
            .thenExecute(()->{ AEAutoPatterns.reloadEpoch++; })
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.STALE,"Datapack reload invalidates plan"))
            .thenSucceed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public void shapedShapelessAndContainerEncoding(GameTestHelper h) {
        var adapter=new CraftingRecipeAdapter(h.getLevel(),false,false,false);while(!adapter.indexStep(64)){}
        for(var item:List.of(Items.CRAFTING_TABLE,Items.STICK,Items.OAK_PLANKS,Items.CAKE)) {
            var key=AEItemKey.of(item);var candidates=adapter.find(key);check(!candidates.isEmpty(),"Recipe indexed: "+item);
            var c=candidates.getFirst();var inputs=c.ingredients().stream().map(List::getFirst).toList();
            var encoded=adapter.encode(c.payload(),inputs,key,h.getLevel());var decoded=PatternDetailsHelper.decodePattern(encoded,h.getLevel());
            check(decoded!=null&&decoded.getPrimaryOutput().what().equals(key),"AE decodes: "+item);
            if(item==Items.CAKE)check(Arrays.stream(decoded.getInputs()).anyMatch(in->Arrays.stream(in.getPossibleInputs()).anyMatch(v->in.getRemainingKey(v.what())!=null)),"Bucket remainder retained");
            if(item==Items.STICK)check(decoded.getPrimaryOutput().amount()==4,"Batch result count retained");
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=400)
    public void networkExistingPatternAndAssembler(GameTestHelper h) {
        var be=setup(h);
        var providerPos=TERMINAL.east();var assemblerPos=providerPos.east();var chestPos=assemblerPos.south();
        h.setBlock(providerPos,AEBlocks.PATTERN_PROVIDER.block());h.setBlock(assemblerPos,AEBlocks.MOLECULAR_ASSEMBLER.block());
        h.setBlock(chestPos,net.minecraft.world.level.block.Blocks.CHEST);
        var adapter=new CraftingRecipeAdapter(h.getLevel());while(!adapter.indexStep(64)){}
        var key=AEItemKey.of(Items.CRAFTING_TABLE);var c=adapter.find(key).getFirst();
        var inputs=c.ingredients().stream().map(List::getFirst).toList();
        var encoded=adapter.encode(c.payload(),inputs,key,h.getLevel());
        var provider=(appeng.blockentity.crafting.PatternProviderBlockEntity)h.getBlockEntity(providerPos);
        var assembler=(appeng.blockentity.crafting.MolecularAssemblerBlockEntity)h.getBlockEntity(assemblerPos);
        var decoded=PatternDetailsHelper.decodePattern(encoded,h.getLevel());
        be.setTarget(key);be.inventory().setStackInSlot(0,AEItems.BLANK_PATTERN.stack(16));
        h.startSequence().thenWaitUntil(()->check(be.online()&&provider.getMainNode().isActive(),"Network ready"))
            .thenExecute(()->provider.getLogic().getPatternInv().setItemDirect(0,encoded))
            .thenWaitUntil(()->check(be.node().getGrid().getCraftingService().isCraftable(key),"Provider registers generated pattern"))
            .thenExecute(be::analyze).thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"Plan ready: "+be.error()))
            .thenExecute(()->{
                check(be.nodes().get(key).choice().kind()==DependencyPlanner.Kind.NETWORK,"Existing network root is skipped");
                check(be.total()>0,"Still plans missing plank dependency");
                var table=new KeyCounter[decoded.getInputs().length];
                for(int i=0;i<table.length;i++) {
                    table[i]=new KeyCounter();var in=decoded.getInputs()[i];var stack=in.getPossibleInputs()[0];
                    table[i].add(stack.what(),stack.amount()*in.getMultiplier());
                }
                check(assembler.pushPattern(decoded,table,net.minecraft.core.Direction.SOUTH),"Assembler accepts generated pattern");
            }).thenWaitUntil(()->{
                var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)h.getBlockEntity(chestPos);
                boolean found=false;for(int i=0;i<chest.getContainerSize();i++)if(chest.getItem(i).is(Items.CRAFTING_TABLE))found=true;
                check(found,"Assembler produces real crafting table");
            }).thenExecute(()->{
                provider.getLogic().getPatternInv().setItemDirect(0,ItemStack.EMPTY);
            }).thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.STALE,"Removed selected network pattern invalidates plan"))
            .thenSucceed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public void workstationPatternsEncodeAndAssemble(GameTestHelper h) {
        var adapter=new CraftingRecipeAdapter(h.getLevel());while(!adapter.indexStep(64)){}
        for(var item:List.of(Items.STONE_SLAB,Items.NETHERITE_PICKAXE)) {
            var key=AEItemKey.of(item);
            var c=adapter.find(key).stream().filter(v->item==Items.STONE_SLAB
                ? v.payload().recipe().value() instanceof net.minecraft.world.item.crafting.StonecutterRecipe
                : v.payload().recipe().value() instanceof net.minecraft.world.item.crafting.SmithingTransformRecipe).findFirst().orElseThrow();
            verifyWorkstation(h,adapter,c,key,item==Items.STONE_SLAB?2:1);
            var wrong=new ArrayList<AEKey>(c.ingredients().stream().map(List::getFirst).toList());wrong.set(0,AEItemKey.of(Items.DIRT));
            boolean rejected=false;try {adapter.encode(c.payload(),wrong,key,h.getLevel());}catch(IllegalStateException expected){rejected=true;}
            check(rejected,"Invalid workstation ingredients must be rejected");
        }
        var trim=h.getLevel().getRecipeManager().getAllRecipesFor(net.minecraft.world.item.crafting.RecipeType.SMITHING).stream()
            .filter(r->r.value() instanceof net.minecraft.world.item.crafting.SmithingTrimRecipe).findFirst().orElseThrow();
        var template=net.minecraft.core.registries.BuiltInRegistries.ITEM.stream().map(ItemStack::new).filter(trim.value()::isTemplateIngredient).findFirst().orElseThrow();
        var frame=new net.minecraft.world.item.crafting.SmithingRecipeInput(template,new ItemStack(Items.DIAMOND_CHESTPLATE),new ItemStack(Items.GOLD_INGOT));
        var target=AEItemKey.of(trim.value().assemble(frame,h.getLevel().registryAccess()));
        check(target!=null,"Trim target has a concrete output");
        check(adapter.hasRecipe(target),"Dynamic trim target discoverable including data components");
        verifyWorkstation(h,adapter,adapter.find(target).getFirst(),target,1);
        h.succeed();
    }
    private void verifyWorkstation(GameTestHelper h,CraftingRecipeAdapter adapter,
            DependencyPlanner.Choice<AEKey,PatternRecipe> choice,AEKey key,int count) {
        var inputs=choice.ingredients().stream().map(List::getFirst).toList();
        var encoded=adapter.encode(choice.payload(),inputs,key,h.getLevel());
        var decoded=PatternDetailsHelper.decodePattern(encoded,h.getLevel());
        check(decoded!=null&&decoded.getPrimaryOutput().what().equals(key),"Workstation output decodes exactly");
        check(decoded.getPrimaryOutput().amount()==count,"Workstation batch output retained");
        check(decoded instanceof appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern,"Native pattern usable in molecular assembler");
        var grid=new ItemStack[9];Arrays.fill(grid,ItemStack.EMPTY);
        if(inputs.size()==1)grid[4]=((AEItemKey)inputs.getFirst()).toStack();
        else for(int i=0;i<3;i++)grid[3+i]=((AEItemKey)inputs.get(i)).toStack();
        var output=((appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern)decoded).assemble(
            net.minecraft.world.item.crafting.CraftingInput.of(3,3,Arrays.asList(grid)),h.getLevel());
        check(key.equals(AEItemKey.of(output))&&output.getCount()==count,"Assembler recipe executes with exact output components and count");
        var inv=new net.neoforged.neoforge.items.ItemStackHandler(AutoPatternTerminalPart.OUTPUT_PATTERN_SLOTS + 1);inv.setStackInSlot(1,encoded);
        check(new PatternLookup(null,h.getLevel(),inv).exists(key),"Output buffer deduplicates workstation pattern");
    }

    @GameTest(template="empty",timeoutTicks=200)
    public void processingPatternsValidateAndPreserveRecipeLore(GameTestHelper h) {
        var disabled=new CraftingRecipeAdapter(h.getLevel());while(!disabled.indexStep(64)){}
        var enabled=new CraftingRecipeAdapter(h.getLevel(),true);while(!enabled.indexStep(64)){}
        var key=AEItemKey.of(Items.BAKED_POTATO);
        check(disabled.find(key).isEmpty(),"Processing recipes excluded by default");
        var choices=enabled.find(key);
        check(choices.size()==3,"Potato has furnace, smoker and campfire routes");
        for(var choice:choices) {
            var inputs=choice.ingredients().stream().map(List::getFirst).toList();
            var stack=choice.payload().adapter().encode(choice.payload(),inputs,key,h.getLevel());
            check(AEItems.PROCESSING_PATTERN.is(stack),"Native AE processing pattern generated");
            var decoded=PatternDetailsHelper.decodePattern(stack,h.getLevel());
            check(decoded!=null&&decoded.getPrimaryOutput().what().equals(key)&&decoded.getPrimaryOutput().amount()==1,"Processing output roundtrip");
            var lore=stack.get(net.minecraft.core.component.DataComponents.LORE);
            check(lore!=null&&lore.lines().size()==1&&lore.lines().getFirst().getString().contains(choice.id()),"Recipe ID attached to generated item");
            check(net.minecraft.ChatFormatting.AQUA.getColor().equals(lore.lines().getFirst().getStyle().getColor().getValue()),"Recipe ID highlighted aqua");
            var restored=ItemStack.parseOptional(h.getLevel().registryAccess(),(net.minecraft.nbt.CompoundTag)stack.save(h.getLevel().registryAccess()));
            check(ItemStack.isSameItemSameComponents(restored,stack),"Recipe lore survives item persistence");
            var buffer=new net.neoforged.neoforge.items.ItemStackHandler(AutoPatternTerminalPart.OUTPUT_PATTERN_SLOTS + 1);buffer.setStackInSlot(1,restored);
            check(new PatternLookup(null,h.getLevel(),buffer).exists(key),"Annotated processing pattern deduplicates");
            boolean rejected=false;try {
                choice.payload().adapter().encode(choice.payload(),List.of(AEItemKey.of(Items.DIRT)),key,h.getLevel());
            }catch(IllegalStateException expected){rejected=true;}
            check(rejected,"Invalid processing input rejected");
        }
        var blasting=enabled.find(AEItemKey.of(Items.IRON_INGOT)).stream()
            .filter(c->c.payload().recipe().value() instanceof net.minecraft.world.item.crafting.BlastingRecipe).findFirst().orElseThrow();
        var blasted=blasting.payload().adapter().encode(blasting.payload(),blasting.ingredients().stream().map(List::getFirst).toList(),AEItemKey.of(Items.IRON_INGOT),h.getLevel());
        check(PatternDetailsHelper.decodePattern(blasted,h.getLevel())!=null,"Blasting route encodes");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=400)
    public void processingToggleReplansAndPersists(GameTestHelper h) {
        var be=setup(h);be.setTarget(AEItemKey.of(Items.BAKED_POTATO));be.inventory().setStackInSlot(0,AEItems.BLANK_PATTERN.stack(4));
        check(!be.allowProcessing(),"Terminal processing default off");
        h.startSequence().thenWaitUntil(()->check(be.online(),"Online"))
            .thenExecute(be::analyze).thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"Default plan ready"))
            .thenExecute(()->{check(be.total()==0,"Off produces no cooking patterns");be.toggleProcessing();})
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.WAITING_SELECTION,"Multiple processing routes require a choice: "+be.error()))
            .thenExecute(()->{check(be.recipeOptions(be.target()).size()==3,"Three distinct cooking routes");be.nextRecipe(be.target());})
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"Selected cooking route"))
            .thenExecute(()->check(be.total()==1,"Exactly one cooking recipe selected"))
            .thenExecute(be::toggleProcessing)
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"Disabling clears only cooking route lock"))
            .thenExecute(()->{check(be.total()==0,"Disabled again no generation");be.toggleProcessing();})
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.WAITING_SELECTION,"Enabled again"))
            .thenExecute(()->be.nextRecipe(be.target()))
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"Enabled again with an explicit route"))
            .thenExecute(be::startOrResume)
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.COMPLETE,"Processing completes: "+be.error()))
            .thenExecute(()->{
                check(be.inventory().getStackInSlot(0).getCount()==3,"One blank consumed");
                check(AEItems.PROCESSING_PATTERN.is(be.inventory().getStackInSlot(1)),"Result retained in buffer");
                var tag=new net.minecraft.nbt.CompoundTag();be.writeToNBT(tag,h.getLevel().registryAccess());
                var restored=restore(h,tag);
                check(restored.allowProcessing()&&restored.state()==AutoPatternTerminalPart.State.STALE,"Processing option persists and reload requires reanalysis");
                be.toggleProcessing();
            }).thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"Existing processing pattern reusable when disabled"))
            .thenExecute(()->check(be.total()==0&&be.inventory().getStackInSlot(0).getCount()==3,"Disabling keeps results and does not consume blanks"))
            .thenSucceed();
    }

    @GameTest(template="empty",timeoutTicks=400)
    public void processingAutoUploadWithoutProviderUsesOutputBuffer(GameTestHelper h) {
        var be=setup(h);be.setTarget(AEItemKey.of(Items.BAKED_POTATO));be.setAutoUpload(true);
        be.inventory().setStackInSlot(0,AEItems.BLANK_PATTERN.stack(2));
        h.startSequence().thenWaitUntil(()->check(be.online(),"Online"))
            .thenExecute(be::analyze)
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"Default plan ready: "+be.state()+" "+be.error()))
            .thenExecute(be::toggleProcessing)
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.WAITING_SELECTION,"Processing route selection required: "+be.state()+" "+be.error()))
            .thenExecute(()->be.nextRecipe(be.target()))
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"Processing route selected: "+be.state()+" "+be.error()))
            .thenExecute(be::startOrResume)
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.COMPLETE,"Missing provider must not pause: "+be.state()+" "+be.error()))
            .thenExecute(()->{
                check(be.uploadTargets().isEmpty(),"Fixture must have no pattern provider");
                check(be.inventory().getStackInSlot(0).getCount()==1,"Local fallback must consume exactly one blank");
                check(AEItems.PROCESSING_PATTERN.is(be.inventory().getStackInSlot(1)),"Missing provider must retain the processing pattern in the output buffer");
                check(be.uploadStatus().contains("output buffer"),"Fallback status must explain local buffering");
            }).thenSucceed();
    }

    @GameTest(template="empty",timeoutTicks=500)
    public void processingAutoUploadOfflineSelectionDoesNotFillOutputBuffer(GameTestHelper h) {
        var be=setup(h);h.setBlock(TERMINAL.east(),AEBlocks.PATTERN_PROVIDER.block());
        var provider=(appeng.blockentity.crafting.PatternProviderBlockEntity)h.getBlockEntity(TERMINAL.east());
        h.startSequence().thenWaitUntil(()->check(be.online()&&be.uploadTargets().size()==1,"Provider online"))
            .thenExecute(()->{
                be.configureProvider(PatternDeployment.identity(provider),"single");
                h.setBlock(TERMINAL.east(),net.minecraft.world.level.block.Blocks.AIR);
            })
            .thenWaitUntil(()->check(be.uploadTargets().isEmpty(),"Selected provider must be offline"))
            .thenExecute(()->{
                be.setTarget(AEItemKey.of(Items.BAKED_POTATO));be.toggleProcessing();be.setAutoUpload(true);
                be.inventory().setStackInSlot(0,AEItems.BLANK_PATTERN.stack(2));be.analyze();
            })
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.WAITING_SELECTION,"Processing route selection required: "+be.state()+" "+be.error()))
            .thenExecute(()->be.nextRecipe(be.target()))
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"Processing route selected: "+be.state()+" "+be.error()))
            .thenExecute(be::startOrResume)
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.OFFLINE,"Offline selected provider must remain a failure: "+be.state()+" "+be.error()))
            .thenExecute(()->{
                check(be.inventory().getStackInSlot(0).getCount()==2,"Offline selected provider must not consume a blank");
                for(int i=1;i<=AutoPatternTerminalPart.OUTPUT_PATTERN_SLOTS;i++)check(be.inventory().getStackInSlot(i).isEmpty(),"Offline selected provider must not fill the output buffer");
            }).thenSucceed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public void nativeSubstitutionFlagsAndFluidInputs(GameTestHelper h) {
        for(boolean items:List.of(false,true))for(boolean fluids:List.of(false,true)) {
            var adapter=new CraftingRecipeAdapter(h.getLevel(),false,items,fluids);while(!adapter.indexStep(64)){}
            for(var item:List.of(Items.CAKE,Items.BARRIER,Items.CRAFTING_TABLE,Items.STONE_SLAB,Items.NETHERITE_PICKAXE)) {
                var key=AEItemKey.of(item);
                var choice=adapter.find(key).stream().filter(c->item!=Items.STONE_SLAB
                    ||c.payload().recipe().value() instanceof net.minecraft.world.item.crafting.StonecutterRecipe).findFirst().orElseThrow();
                var inputs=choice.ingredients().stream().map(List::getFirst).toList();
                var encoded=adapter.encode(choice.payload(),inputs,key,h.getLevel());
                var decoded=PatternDetailsHelper.decodePattern(encoded,h.getLevel());
                if(decoded instanceof appeng.crafting.pattern.AECraftingPattern crafting) {
                    check(crafting.canSubstitute()==items&&crafting.canSubstituteFluids()==fluids,"Crafting flags match native toggles");
                    if(item==Items.BARRIER) {
                        boolean fluidFound=false;
                        for(int slot=0;slot<9;slot++)if(crafting.getValidFluid(slot)!=null)fluidFound=true;
                        check(fluidFound==fluids,"AE replaces water bucket only when fluid substitution enabled");
                    }
                    if(item==Items.CRAFTING_TABLE&&items)
                        check(crafting.isItemValid(0,AEItemKey.of(Items.BIRCH_PLANKS),h.getLevel()),"Native tag substitution accepts alternative plank");
                } else if(decoded instanceof appeng.crafting.pattern.AEStonecuttingPattern stone)
                    check(stone.canSubstitute()==items,"Stonecutting item substitution flag");
                else if(decoded instanceof appeng.crafting.pattern.AESmithingTablePattern smith)
                    check(smith.canSubstitute()==items,"Smithing item substitution flag");
                else check(false,"Expected native pattern");
            }
        }
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=400)
    public void substitutionSettingsReplanPersistAndPreview(GameTestHelper h) {
        var be=setup(h);be.setTarget(AEItemKey.of(Items.BARRIER));
        check(!be.substitute()&&be.substituteFluids(),"AE defaults: items off, fluids on");
        h.startSequence().thenWaitUntil(()->check(be.online(),"Online"))
            .thenExecute(be::analyze).thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"Fluid recipe plan ready: "+be.error()))
            .thenExecute(()->{
                check(be.fluidInputs(be.target()).size()==1,"Native water-bucket slot highlighted");
                var revision=be.revision();be.startOrResume();be.setSubstitution(false,true);
                check(be.state()==AutoPatternTerminalPart.State.INDEXING&&be.revision()>revision,"Toggle stops task and reanalyzes");
                be.setSubstitution(true,false);
            }).thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"Reanalysis completes"))
            .thenExecute(()->{
                check(be.fluidInputs(be.target()).isEmpty(),"Disabled fluid toggle clears green preview");
                var saved=new net.minecraft.nbt.CompoundTag();be.writeToNBT(saved,h.getLevel().registryAccess());
                var restored=restore(h,saved);
                check(restored.substitute()&&!restored.substituteFluids(),"Both choices persist");
                check(restored.state()==AutoPatternTerminalPart.State.STALE,"Reload requires reanalysis");
                saved.remove("substitute");saved.remove("substituteFluids");
                restored.readFromNBT(saved,h.getLevel().registryAccess());
                check(!restored.substitute()&&restored.substituteFluids(),"Old saves adopt AE defaults");
            }).thenSucceed();
    }

    @GameTest(template="empty",timeoutTicks=400)
    public void topologyVersionInvalidatesWithoutConsumingBlanks(GameTestHelper h) {
        var part=setup(h);part.setTarget(AEItemKey.of(Items.CRAFTING_TABLE));part.inventory().setStackInSlot(0,AEItems.BLANK_PATTERN.stack(8));
        h.startSequence().thenWaitUntil(()->check(part.online(),"AE part online"))
            .thenExecute(part::analyze).thenWaitUntil(()->check(part.state()==AutoPatternTerminalPart.State.READY,"Ready: "+part.state()+" "+part.error()))
            .thenExecute(()->appeng.api.parts.PartHelper.setPart(h.getLevel(),h.absolutePos(TERMINAL.south()),null,null,AEParts.GLASS_CABLE.item(appeng.api.util.AEColor.TRANSPARENT)))
            .thenWaitUntil(()->check(part.state()==AutoPatternTerminalPart.State.STALE,"Topology generation must invalidate plan"))
            .thenExecute(()->check(part.inventory().getStackInSlot(0).getCount()==8,"Invalidation consumes no blanks"))
            .thenSucceed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public void sharedIndexAndBrokenAdapterIsolation(GameTestHelper h) {
        var level=h.getLevel();var settings=RecipeAdapter.Settings.defaults();
        check(SharedCraftingIndex.get(level,settings)==SharedCraftingIndex.get(level,settings),"Same world and settings share built-in index");
        var broken=new RecipeAdapter() {
            @Override public String adapterId(){return "test:broken";}
            @Override public boolean indexStep(int budget){throw new IllegalStateException("Expected adapter-isolation test");}
            @Override public List<DependencyPlanner.Choice<AEKey,PatternRecipe>> find(AEKey key){throw new IllegalStateException("Disabled adapter must not be queried");}
            @Override public ItemStack encode(PatternRecipe p,List<AEKey> inputs,AEKey key,net.minecraft.world.level.Level l){throw new UnsupportedOperationException();}
        };
        var composite=new CompositeRecipeAdapter(List.of(broken,SharedCraftingIndex.get(level,settings)));
        while(!composite.indexStep(64)){}
        check(!composite.find(AEItemKey.of(Items.CRAFTING_TABLE)).isEmpty(),"Healthy adapter remains usable");
        check(!composite.failures().isEmpty()&&!composite.timings().isEmpty(),"Failure and slow-call diagnostics retained");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public void onlyPrimaryBufferOutputsCountAsExistingPatterns(GameTestHelper h) {
        var primary=AEItemKey.of(Items.IRON_INGOT);var secondary=AEItemKey.of(Items.GOLD_INGOT);
        var stack=PatternDetailsHelper.encodeProcessingPattern(
            List.of(new GenericStack(AEItemKey.of(Items.RAW_IRON),1)),
            List.of(new GenericStack(primary,3),new GenericStack(secondary,2)));
        var inv=new net.neoforged.neoforge.items.ItemStackHandler(AutoPatternTerminalPart.OUTPUT_PATTERN_SLOTS + 1);inv.setStackInSlot(1,stack);
        var lookup=new PatternLookup(null,h.getLevel(),inv);
        var decoded=PatternDetailsHelper.decodePattern(stack,h.getLevel());
        check(decoded!=null&&decoded.getOutputs().size()==2,"Secondary output remains in decoded recipe detail");
        check(decoded.getPrimaryOutput().what().equals(primary)&&decoded.getPrimaryOutput().amount()==3,"Primary output round-trips exactly");
        check(decoded.getOutputs().get(1).what().equals(secondary)&&decoded.getOutputs().get(1).amount()==2,"Secondary output quantity round-trips exactly");
        check(lookup.exists(primary),"Primary buffer output must count as reusable");
        check(!lookup.exists(secondary),"Secondary buffer output must not count as reusable");
        inv.setStackInSlot(1,ItemStack.EMPTY);
        check(!lookup.exists(primary)&&!lookup.exists(secondary),"Changed buffer slots must be decoded again");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=400)
    public void secondaryBufferOutputDoesNotSkipTerminalAnalysis(GameTestHelper h) {
        var be=setup(h);
        var primary=AEItemKey.of(Items.IRON_INGOT);var target=AEItemKey.of(Items.CRAFTING_TABLE);
        var stack=PatternDetailsHelper.encodeProcessingPattern(
            List.of(new GenericStack(AEItemKey.of(Items.DIRT),1)),
            List.of(new GenericStack(primary,2),new GenericStack(target,1)));
        be.inventory().setStackInSlot(1,stack);be.setTarget(target);
        h.startSequence().thenWaitUntil(()->check(be.online(),"Network ready"))
            .thenExecute(be::analyze)
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"Analysis ready: "+be.state()+" "+be.error()))
            .thenExecute(()->{
                var root=be.nodes().get(target);
                check(root!=null,"Target must be analyzed");
                check(root.choice().kind()==DependencyPlanner.Kind.GENERATE,"Secondary buffer output must not mark target reusable");
                check(be.total()>0,"Terminal must still plan the secondary target recipe");
            }).thenSucceed();
    }

    @GameTest(template="empty",timeoutTicks=400)
    public void onlyPrimaryNetworkOutputsCountAsExistingPatterns(GameTestHelper h) {
        var be=setup(h);h.setBlock(TERMINAL.east(),AEBlocks.PATTERN_PROVIDER.block());
        var provider=(appeng.blockentity.crafting.PatternProviderBlockEntity)h.getBlockEntity(TERMINAL.east());
        var primary=AEItemKey.of(Items.IRON_INGOT);var secondary=AEItemKey.of(Items.CRAFTING_TABLE);
        var pattern=PatternDetailsHelper.encodeProcessingPattern(
            List.of(new GenericStack(AEItemKey.of(Items.DIRT),1)),
            List.of(new GenericStack(primary,2),new GenericStack(secondary,1)));
        h.startSequence().thenWaitUntil(()->check(be.online()&&provider.getMainNode().isActive(),"Network ready"))
            .thenExecute(()->provider.getLogic().getPatternInv().setItemDirect(0,pattern))
            .thenWaitUntil(()->check(be.node().getGrid().getCraftingService().isCraftable(primary),"Provider registers primary output"))
            .thenExecute(()->{
                var lookup=new PatternLookup(be.node().getGrid(),h.getLevel(),new net.neoforged.neoforge.items.ItemStackHandler(AutoPatternTerminalPart.OUTPUT_PATTERN_SLOTS + 1));
                check(lookup.exists(primary),"Primary network output must count as reusable");
                check(!lookup.exists(secondary),"Secondary network output must not count as reusable");
                be.setTarget(secondary);be.analyze();
            })
            .thenWaitUntil(()->check(be.state()==AutoPatternTerminalPart.State.READY,"Analysis ready: "+be.state()+" "+be.error()))
            .thenExecute(()->{
                var root=be.nodes().get(secondary);
                check(root!=null,"Secondary target must be analyzed");
                check(root.choice().kind()==DependencyPlanner.Kind.GENERATE,"Secondary network output must not mark target reusable");
                check(be.total()>0,"Terminal must still plan the secondary target recipe");
            }).thenSucceed();
    }

    @GameTest(template="empty",timeoutTicks=300)
    public void routeGroupsRequireExplicitProviderToggle(GameTestHelper h) {
        var be=setup(h);h.setBlock(TERMINAL.east(),AEBlocks.PATTERN_PROVIDER.block());
        var provider=(appeng.blockentity.crafting.PatternProviderBlockEntity)h.getBlockEntity(TERMINAL.east());
        var providerId=PatternDeployment.identity(provider);
        h.startSequence().thenWaitUntil(()->check(be.online()&&be.uploadTargets().size()==1,"Provider visible"))
            .thenExecute(()->{
                var groupId=be.createRecipeRouteGroup("Manual route");
                check(groupId!=null,"Route group must be created");
                check(be.recipeRouteGroups().getFirst().providerIds().isEmpty(),"Creating a group must not add providers");
                check(be.addRecipesToRouteGroup(groupId,List.of(ResourceLocation.parse("minecraft:stick"))),"Recipe must be added");
                check(be.recipeRouteGroups().getFirst().providerIds().isEmpty(),"Adding recipes must not add providers");
                check(be.toggleRouteGroupProvider(groupId,providerId),"Explicit provider toggle must succeed");
                check(be.recipeRouteGroups().getFirst().providerIds().equals(List.of(providerId)),"Only explicit toggle adds the provider");
            }).thenSucceed();
    }
}
