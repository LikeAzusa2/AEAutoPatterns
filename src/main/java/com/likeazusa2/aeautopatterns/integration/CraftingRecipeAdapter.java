package com.likeazusa2.aeautopatterns.integration;
import java.util.*;
import appeng.api.stacks.*;
import appeng.api.crafting.PatternDetailsHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import com.likeazusa2.aeautopatterns.logic.DependencyPlanner;

public final class CraftingRecipeAdapter implements RecipeAdapter {
    private final Map<AEKey,List<RecipeHolder<?>>> index=new HashMap<>();
    private final List<RecipeHolder<SmithingTrimRecipe>> trims=new ArrayList<>();
    private final Map<AEKey,List<DependencyPlanner.Choice<AEKey,PatternRecipe>>> trimCache=new HashMap<>();
    private final Set<AEKey> unsupported=new HashSet<>();
    private final Set<net.minecraft.world.item.Item> knownOutputs=new HashSet<>();
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("aeautopatterns.crafting");
    private final Iterator<RecipeHolder<?>> pending;
    private final Level level;
    private final ProcessingRecipeAdapter processing;
    private final boolean substitute,substituteFluids;
    public CraftingRecipeAdapter(Level level) {this(level,false,false,true);}
    public CraftingRecipeAdapter(Level level,boolean allowProcessing) {this(level,allowProcessing,false,true);}
    public CraftingRecipeAdapter(Level level,boolean allowProcessing,boolean substitute,boolean substituteFluids) {
        this.substitute=substitute;this.substituteFluids=substituteFluids;
        this.level=level;processing=allowProcessing?new ProcessingRecipeAdapter(level):null;
        // RecipeManager exposes an immutable values view. Filter one holder per
        // index step instead of collecting/sorting the entire pack in the constructor.
        pending=level.getRecipeManager().getRecipes().iterator();
    }
    public boolean indexStep(int budget) {
        for(int i=0;i<budget&&pending.hasNext();i++) {
            var holder=pending.next(); var r=holder.value();
            if(r.getType()!=RecipeType.CRAFTING&&r.getType()!=RecipeType.STONECUTTING&&r.getType()!=RecipeType.SMITHING)continue;
            if(r instanceof SmithingTrimRecipe) {
                @SuppressWarnings("unchecked") var trim=(RecipeHolder<SmithingTrimRecipe>)(RecipeHolder<?>)holder;
                trims.add(trim);continue;
            }
            AEItemKey key=null;
            try {
            var result=r.getResultItem(level.registryAccess());
            if(result.isEmpty()) continue;
            key=AEItemKey.of(result);knownOutputs.add(result.getItem());
            // AE stores the recipe ID and invokes matches/assemble itself. Modded
            // subclasses and ordinary CraftingRecipe implementations need no whitelist.
            var slots=ingredients(r);
            boolean shapeFits=!(r instanceof ShapedRecipe shaped)
                || shaped.getWidth()>0&&shaped.getWidth()<=3&&shaped.getHeight()>0&&shaped.getHeight()<=3;
            if(!(r instanceof CraftingRecipe || r instanceof StonecutterRecipe || r instanceof SmithingTransformRecipe)
                || r instanceof CraftingRecipe crafting&&!crafting.canCraftInDimensions(3,3)
                || !shapeFits || slots.size()>9 || slots.stream().allMatch(Ingredient::isEmpty)
                || slots.stream().filter(i2->!i2.isEmpty()).anyMatch(i2->Arrays.stream(i2.getItems()).noneMatch(i2::test))) unsupported.add(key);
            else index.computeIfAbsent(key,k->new ArrayList<>()).add(holder);
            } catch(RuntimeException ex) {
                if(key!=null)unsupported.add(key);
                LOG.warn("Cannot index crafting recipe {} ({})",holder.id(),r.getClass().getName(),ex);
            }
        }
        return !pending.hasNext()&&(processing==null||processing.indexStep(budget));
    }
    public boolean hasRecipe(AEKey key) { return index.containsKey(key)||!trimChoices(key).isEmpty()||(processing!=null&&processing.hasRecipe(key)); }
    @Override public String adapterId() { return "aeautopatterns:vanilla-crafting"; }
    @Override public Set<AdapterCapability> capabilities() {
        var result=EnumSet.of(AdapterCapability.ITEMS,AdapterCapability.ALTERNATIVES,AdapterCapability.VALIDATION);
        if(substituteFluids) result.add(AdapterCapability.FLUIDS);
        if(processing!=null) result.add(AdapterCapability.AE_PROCESSING_ENCODING);
        return Set.copyOf(result);
    }
    @Override public List<PatternRecipe> processingRecipes() {
        return processing==null?List.of():processing.processingRecipes();
    }
    public boolean unsupported(AEKey key) {
        return unsupported.contains(key) || key instanceof AEItemKey item
            && knownOutputs.contains(item.getItem()) && !index.containsKey(key) && trimChoices(key).isEmpty();
    }
    @Override public List<DependencyPlanner.Choice<AEKey,PatternRecipe>> find(AEKey key) {
        var result=new ArrayList<DependencyPlanner.Choice<AEKey,PatternRecipe>>();
        for(var recipe:index.getOrDefault(key,List.of())) {
            var groups=new ArrayList<List<AEKey>>();
            for(var ingredient:ingredients(recipe.value())) {
                if(ingredient.isEmpty()) continue;
                groups.add(Arrays.stream(ingredient.getItems()).filter(ingredient::test).map(AEItemKey::of).map(k->(AEKey)k).distinct().toList());
            }
            result.add(new DependencyPlanner.Choice<>(recipe.id().toString(),DependencyPlanner.Kind.GENERATE,groups,
                new PatternRecipe(recipe,ItemStack.EMPTY,this)));
        }
        result.addAll(trimChoices(key));
        if(processing!=null)result.addAll(processing.find(key));
        result.sort(Comparator.comparing(DependencyPlanner.Choice::id));
        return result;
    }
    public List<Ingredient> ingredients(Recipe<?> recipe) {
        if(recipe instanceof SmithingTransformRecipe || recipe instanceof SmithingTrimRecipe) {
            var ops=level.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
            var json=recipe instanceof SmithingTransformRecipe smith
                ? RecipeSerializer.SMITHING_TRANSFORM.codec().codec().encodeStart(ops,smith).getOrThrow().getAsJsonObject()
                : RecipeSerializer.SMITHING_TRIM.codec().codec().encodeStart(ops,(SmithingTrimRecipe)recipe).getOrThrow().getAsJsonObject();
            return List.of("template","base","addition").stream()
                .map(name->Ingredient.CODEC.parse(ops,json.get(name)).getOrThrow()).toList();
        }
        return recipe.getIngredients();
    }
    private List<DependencyPlanner.Choice<AEKey,PatternRecipe>> trimChoices(AEKey key) {
        return trimCache.computeIfAbsent(key,k->{
            var result=new ArrayList<DependencyPlanner.Choice<AEKey,PatternRecipe>>();
            if(!(k instanceof AEItemKey item) || !item.toStack().has(net.minecraft.core.component.DataComponents.TRIM)) return result;
            var base=item.toStack();base.remove(net.minecraft.core.component.DataComponents.TRIM);
            for(var holder:trims) {
                var recipe=holder.value();var slots=ingredients(recipe);
                if(!slots.get(1).test(base))continue;
                for(var template:slots.get(0).getItems()) for(var addition:slots.get(2).getItems()) {
                    var input=new SmithingRecipeInput(template,base,addition);
                    if(!recipe.matches(input,level))continue;
                    var output=recipe.assemble(input,level.registryAccess());
                    if(output.isEmpty() || !key.equals(AEItemKey.of(output)))continue;
                    var groups=List.of(List.<AEKey>of(AEItemKey.of(template)),List.<AEKey>of(AEItemKey.of(base)),List.<AEKey>of(AEItemKey.of(addition)));
                    result.add(new DependencyPlanner.Choice<>(holder.id().toString(),DependencyPlanner.Kind.GENERATE,groups,new PatternRecipe(holder,ItemStack.EMPTY,this)));
                    break;
                }
            }
            return List.copyOf(result);
        });
    }
    @SuppressWarnings("unchecked")
    @Override public ItemStack encode(PatternRecipe recipe,List<AEKey> inputs,AEKey expected,Level level) {
        var current=level.getRecipeManager().byKey(recipe.recipe().id()).orElseThrow();
        if(current!=recipe.recipe()) throw new IllegalStateException("Recipe changed; analyze again");
        var raw=recipe.recipe().value();
        if(raw instanceof StonecutterRecipe || raw instanceof SmithingRecipe)
            return encodeWorkstation(recipe,inputs,expected,level);
        var r=(CraftingRecipe)raw;
        var stacks=new ItemStack[9]; Arrays.fill(stacks,ItemStack.EMPTY);
        int width=r instanceof ShapedRecipe shaped?shaped.getWidth():3;
        int next=0;
        for(int i=0;i<r.getIngredients().size();i++) {
            if(r.getIngredients().get(i).isEmpty()) continue;
            if(next>=inputs.size() || !(inputs.get(next++) instanceof AEItemKey item)
                || !r.getIngredients().get(i).test(item.toStack())) throw new IllegalStateException("Invalid ingredient for "+recipe.recipe().id());
            stacks[(i/width)*3+i%width]=item.toStack();
        }
        if(next!=inputs.size())throw new IllegalStateException("Unexpected extra ingredients for "+recipe.recipe().id());
        // Match the normalized input, exactly as the AE crafting pattern decoder does.
        var normalized=CraftingInput.ofPositioned(3,3,Arrays.asList(stacks)).input();
        if(!r.matches(normalized,level)) throw new IllegalStateException("Recipe does not match selected inputs");
        var output=r.assemble(normalized,level.registryAccess());
        if(output.isEmpty() || !expected.equals(AEItemKey.of(output)))
            throw new IllegalStateException("Recipe "+recipe.recipe().id()+" does not produce the exact target components with the selected inputs");
        var repeated=r.assemble(CraftingInput.ofPositioned(3,3,Arrays.stream(stacks).map(ItemStack::copy).toList()).input(),level.registryAccess());
        if(!ItemStack.matches(output,repeated))throw new IllegalStateException("Non-deterministic crafting recipe: "+recipe.recipe().id());
        var encoded=PatternDetailsHelper.encodeCraftingPattern((RecipeHolder<CraftingRecipe>)(RecipeHolder<?>)recipe.recipe(),stacks,output,substitute,substituteFluids);
        var decoded=PatternDetailsHelper.decodePattern(encoded,level);
        if(decoded==null || !decoded.getPrimaryOutput().what().equals(expected)
            || decoded.getPrimaryOutput().amount()!=output.getCount()) throw new IllegalStateException("AE pattern validation failed: "+recipe.recipe().id());
        var expectedInputs=new HashMap<AEKey,Long>();
        for(var stack:stacks)if(!stack.isEmpty())expectedInputs.merge(AEItemKey.of(stack),(long)stack.getCount(),Long::sum);
        var decodedInputs=new HashMap<AEKey,Long>();
        // Public compressed inputs may intentionally prefer fluid substitution.
        // Compare the concrete encoded crafting grid, preserving item components.
        for(var stack:encoded.get(appeng.api.ids.AEComponents.ENCODED_CRAFTING_PATTERN).inputs())
            if(!stack.isEmpty())decodedInputs.merge(AEItemKey.of(stack),(long)stack.getCount(),Long::sum);
        if(!expectedInputs.equals(decodedInputs))throw new IllegalStateException("AE crafting inputs changed: "+recipe.recipe().id());
        return encoded;
    }
    @SuppressWarnings("unchecked")
    private ItemStack encodeWorkstation(PatternRecipe recipe,List<AEKey> inputs,AEKey expected,Level level) {
        var r=recipe.recipe().value();
        int count=r instanceof StonecutterRecipe?1:3;
        if(inputs.size()!=count || inputs.stream().anyMatch(k->!(k instanceof AEItemKey)))
            throw new IllegalStateException("Invalid workstation inputs");
        ItemStack output;
        ItemStack encoded;
        if(r instanceof StonecutterRecipe stone) {
            var in=(AEItemKey)inputs.getFirst();
            var frame=new SingleRecipeInput(in.toStack());
            if(!stone.matches(frame,level)) throw new IllegalStateException("Stonecutting inputs no longer match");
            output=stone.assemble(frame,level.registryAccess());
            if(output.isEmpty() || !expected.equals(AEItemKey.of(output))) throw new IllegalStateException("Recipe output changed");
            encoded=PatternDetailsHelper.encodeStonecuttingPattern(
                (RecipeHolder<StonecutterRecipe>)(RecipeHolder<?>)recipe.recipe(),in,AEItemKey.of(output),substitute);
        } else {
            var smith=(SmithingRecipe)r;
            var template=(AEItemKey)inputs.get(0);var base=(AEItemKey)inputs.get(1);var addition=(AEItemKey)inputs.get(2);
            var frame=new SmithingRecipeInput(template.toStack(),base.toStack(),addition.toStack());
            if(!smith.matches(frame,level)) throw new IllegalStateException("Smithing inputs no longer match");
            output=smith.assemble(frame,level.registryAccess());
            if(output.isEmpty() || !expected.equals(AEItemKey.of(output))) throw new IllegalStateException("Recipe output changed");
            encoded=PatternDetailsHelper.encodeSmithingTablePattern(
                (RecipeHolder<SmithingRecipe>)(RecipeHolder<?>)recipe.recipe(),template,base,addition,AEItemKey.of(output),substitute);
        }
        var decoded=PatternDetailsHelper.decodePattern(encoded,level);
        if(decoded==null || !decoded.getPrimaryOutput().what().equals(expected)
            || decoded.getPrimaryOutput().amount()!=output.getCount())
            throw new IllegalStateException("AE workstation pattern validation failed");
        return encoded;
    }

}
