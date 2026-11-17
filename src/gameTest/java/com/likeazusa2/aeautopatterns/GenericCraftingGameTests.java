package com.likeazusa2.aeautopatterns;

import java.util.*;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.*;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import com.likeazusa2.aeautopatterns.integration.*;
import com.likeazusa2.aeautopatterns.logic.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;
import net.neoforged.neoforge.gametest.*;

/** Runtime recipes emulate mod wrappers without adding dependencies to the release JAR. */
@GameTestHolder(AEAutoPatterns.ID)
@PrefixGameTestTemplate(false)
public final class GenericCraftingGameTests {
    private static void check(boolean condition,String message) {
        if(!condition)throw new GameTestAssertException(message);
    }
    private static RecipeHolder<?> holder(String name,Recipe<?> recipe) {
        return new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath(AEAutoPatterns.ID,name),recipe);
    }
    private void withRecipes(GameTestHelper h,List<RecipeHolder<?>> extra,Runnable test) {
        var manager=h.getLevel().getRecipeManager();
        var original=List.copyOf(manager.getRecipes());
        var all=new ArrayList<RecipeHolder<?>>(original);all.addAll(extra);
        try {manager.replaceRecipes(all);test.run();}
        finally {manager.replaceRecipes(original);}
    }
    private CraftingRecipeAdapter indexed(GameTestHelper h) {
        var adapter=new CraftingRecipeAdapter(h.getLevel(),false,false,false);
        while(!adapter.indexStep(64)){}return adapter;
    }
    @GameTest(template="empty",timeoutTicks=200)
    public void moddedShapedTransfersComponents(GameTestHelper h) {
        var source=new ItemStack(Items.DIAMOND_SWORD);source.setDamageValue(37);
        var target=new ItemStack(Items.NETHERITE_SWORD);target.setDamageValue(37);
        var pattern=ShapedRecipePattern.of(Map.of('S',DataComponentIngredient.of(true,source),'I',Ingredient.of(Items.NETHERITE_INGOT)),"SI");
        var recipe=new ShapedRecipe("test",CraftingBookCategory.MISC,pattern,target) {
            @Override public ItemStack assemble(CraftingInput input,HolderLookup.Provider registries) {
                var output=new ItemStack(Items.NETHERITE_SWORD);
                output.setDamageValue(input.getItem(0).getDamageValue());return output;
            }
        };
        var holder=holder("component_transfer_test",recipe);
        withRecipes(h,List.of(holder),()->{
            var adapter=indexed(h);var key=AEItemKey.of(target);
            var choice=adapter.find(key).stream().filter(c->c.id().equals(holder.id().toString())).findFirst().orElseThrow();
            var inputs=choice.ingredients().stream().map(List::getFirst).toList();
            check(inputs.getFirst().equals(AEItemKey.of(source)),"Custom ingredient retains exact input components");
            var encoded=adapter.encode(choice.payload(),inputs,key,h.getLevel());
            var decoded=PatternDetailsHelper.decodePattern(encoded,h.getLevel());
            check(decoded!=null&&decoded.getPrimaryOutput().what().equals(key),"AE retains transferred output components");
            var grid=new ArrayList<ItemStack>(Collections.nCopies(9,ItemStack.EMPTY));grid.set(0,source);grid.set(1,new ItemStack(Items.NETHERITE_INGOT));
            var actual=((IMolecularAssemblerSupportedPattern)decoded).assemble(CraftingInput.of(3,3,grid),h.getLevel());
            check(ItemStack.matches(actual,target),"AE assembler executes wrapped shaped recipe with transferred components");
            boolean rejected=false;
            try {adapter.encode(choice.payload(),inputs,AEItemKey.of(Items.NETHERITE_SWORD),h.getLevel());}
            catch(IllegalStateException expected){rejected=true;}
            check(rejected,"Different output components must not be silently accepted");
        });h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public void shapelessSubclassAndDelegatingRecipe(GameTestHelper h) {
        var ingredients=NonNullList.of(Ingredient.EMPTY,Ingredient.of(Items.DIRT),Ingredient.of(Items.DIAMOND));
        var shapeless=new ShapelessRecipe("test",CraftingBookCategory.MISC,new ItemStack(Items.CLAY_BALL,3),ingredients) {};
        var delegated=new CraftingRecipe() {
            @Override public CraftingBookCategory category(){return CraftingBookCategory.MISC;}
            @Override public NonNullList<Ingredient> getIngredients(){return shapeless.getIngredients();}
            @Override public boolean matches(CraftingInput input,Level level){return shapeless.matches(input,level);}
            @Override public ItemStack assemble(CraftingInput input,HolderLookup.Provider access){return new ItemStack(Items.BRICK,2);}
            @Override public ItemStack getResultItem(HolderLookup.Provider access){return new ItemStack(Items.BRICK,2);}
            @Override public boolean canCraftInDimensions(int width,int height){return width*height>=2;}
            @Override public RecipeSerializer<?> getSerializer(){return RecipeSerializer.SHAPELESS_RECIPE;}
        };
        var holders=List.of(holder("shapeless_subclass_test",shapeless),holder("delegating_test",delegated));
        withRecipes(h,holders,()->{
            var adapter=indexed(h);
            for(var holder:holders) {
                var output=holder.value().getResultItem(h.getLevel().registryAccess());var key=AEItemKey.of(output);
                var choice=adapter.find(key).stream().filter(c->c.id().equals(holder.id().toString())).findFirst().orElseThrow();
                var encoded=adapter.encode(choice.payload(),choice.ingredients().stream().map(List::getFirst).toList(),key,h.getLevel());
                var decoded=PatternDetailsHelper.decodePattern(encoded,h.getLevel());
                check(decoded!=null&&decoded.getPrimaryOutput().amount()==output.getCount(),"Custom crafting batch output survives AE decode");
            }
        });h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public void brokenRecipeDoesNotDisableOtherCrafting(GameTestHelper h) {
        var shape=ShapedRecipePattern.of(Map.of('D',Ingredient.of(Items.DIRT)),"D");
        var broken=new ShapedRecipe("test",CraftingBookCategory.MISC,shape,new ItemStack(Items.BRICK)) {
            @Override public NonNullList<Ingredient> getIngredients(){throw new IllegalStateException("Test broken mod ingredient");}
        };
        var good=new ShapedRecipe("test",CraftingBookCategory.MISC,shape,new ItemStack(Items.BRICK)) {};
        var unsupported=new ShapedRecipe("test",CraftingBookCategory.MISC,shape,new ItemStack(Items.COMMAND_BLOCK)) {
            @Override public NonNullList<Ingredient> getIngredients(){throw new IllegalStateException("Test unsupported component recipe");}
        };
        withRecipes(h,List.of(holder("broken_recipe_test",broken),holder("valid_recipe_test",good),holder("unsupported_recipe_test",unsupported)),()->{
            var adapter=indexed(h);
            check(!adapter.find(AEItemKey.of(Items.BRICK)).isEmpty(),"Broken recipe cannot hide valid route to same output");
            check(!adapter.find(AEItemKey.of(Items.CRAFTING_TABLE)).isEmpty(),"Vanilla crafting still indexed after failure");
            check(adapter.unsupported(AEItemKey.of(Items.COMMAND_BLOCK)),"Known unparseable recipe is unsupported, not external");
        });h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public void layeredSharedRecipesWithCyclesEncodeWholePlan(GameTestHelper h) {
        // Eight tiers of six materials: each consumes two next-tier materials
        // and the same base. Each also has an earlier, cyclic recipe candidate.
        var items=net.minecraft.core.registries.BuiltInRegistries.ITEM.stream()
            .filter(i->i!=Items.AIR).limit(49).toList();
        var recipes=new ArrayList<RecipeHolder<?>>();
        for(int i=0;i<48;i++) {
            var cycle=NonNullList.of(Ingredient.EMPTY,Ingredient.of(items.get(0)),Ingredient.of(items.get(48)));
            recipes.add(holder("stress_00_cycle_"+i,new ShapelessRecipe("test",CraftingBookCategory.MISC,new ItemStack(items.get(i)),cycle)));
            var slots=NonNullList.<Ingredient>create();
            if(i/6<7) {
                slots.add(Ingredient.of(items.get(i+6)));
                slots.add(Ingredient.of(items.get((i/6+1)*6+(i+1)%6)));
            }
            slots.add(Ingredient.of(items.get(48)));
            recipes.add(holder("stress_10_safe_"+i,new ShapelessRecipe("test",CraftingBookCategory.MISC,new ItemStack(items.get(i)),slots) {}));
        }
        withRecipes(h,recipes,()->{
            var adapter=indexed(h);var root=AEItemKey.of(items.getFirst());
            var base=AEItemKey.of(items.get(48));
            java.util.function.Function<AEKey,List<DependencyPlanner.Choice<AEKey,PatternRecipe>>> source=key->{
                if(key.equals(base))return List.of(new DependencyPlanner.Choice<>("external",DependencyPlanner.Kind.EXTERNAL,List.of(),null));
                return adapter.find(key).stream().filter(c->c.id().contains(":stress_")).toList();
            };
            var plan=new AnalysisSession<>(root,source,k->false,new AnalysisSession.Limits(512,16384,262144,262144));
            finish(plan);
            check(plan.error().isEmpty(),"Mixed cyclic/acyclic multi-stage plan succeeds: "+plan.error());
            check(plan.queue().size()==33,"Eight-tier shared plan has exactly 33 unique new patterns");
            check(plan.nodes().size()==34,"Shared base remains one external material");
            var positions=new HashMap<AEKey,Integer>();
            var buffer=new net.neoforged.neoforge.items.ItemStackHandler(64);
            int at=0;
            for(var node:plan.queue()) {
                check(node.choice().id().contains("stress_10_safe_"),"Cycle routes must be rejected, not silently externalized");
                for(var child:node.children())check(child.equals(base)||positions.containsKey(child),"Dependencies precede parent encoding");
                check(positions.put(node.key(),at)==null,"Shared recipe must be encoded once");
                var encoded=adapter.encode(node.choice().payload(),node.children(),node.key(),h.getLevel());
                buffer.setStackInSlot(++at,encoded);
                var decoded=PatternDetailsHelper.decodePattern(encoded,h.getLevel());
                check(decoded!=null&&decoded.getPrimaryOutput().what().equals(node.key()),"Every generated pattern decodes exactly");
                var grid=new ArrayList<ItemStack>(Collections.nCopies(9,ItemStack.EMPTY));
                for(int slot=0;slot<node.children().size();slot++)grid.set(slot,((AEItemKey)node.children().get(slot)).toStack());
                var actual=((IMolecularAssemblerSupportedPattern)decoded).assemble(CraftingInput.of(3,3,grid),h.getLevel());
                check(node.key().equals(AEItemKey.of(actual)),"Every stage executes through AE assembler interface");
            }
            var lookup=new PatternLookup(null,h.getLevel(),buffer);
            var repeated=new AnalysisSession<AEKey,PatternRecipe>(root,key->{
                var existing=lookup.find(key);return existing.isEmpty()?source.apply(key):existing;
            },k->false,new AnalysisSession.Limits(512,16384,262144,262144));
            finish(repeated);
            check(repeated.error().isEmpty()&&repeated.queue().isEmpty(),"Repeat analysis creates no duplicate patterns");
            check(repeated.nodes().size()==34,"Existing patterns still recursively expand every dependency");
        });h.succeed();
    }
    private void finish(AnalysisSession<AEKey,PatternRecipe> plan) {
        for(int step=0;step<10000&&!plan.done();step++)plan.step(System.nanoTime()+2_000_000);
        check(plan.done(),"Complex analysis must terminate");
    }
}
