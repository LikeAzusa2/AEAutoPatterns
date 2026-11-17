package com.likeazusa2.aeautopatterns.integration;

import java.lang.reflect.*;
import java.util.*;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import com.likeazusa2.aeautopatterns.logic.DependencyPlanner;

/** Chemical/gas recipes exposed as AE keys by Applied Mekanistics. No optional classes are linked. */
public final class MekanismChemicalRecipeAdapter implements RecipeAdapter {
    private static final String KEY_CLASS="me.ramidzkh.mekae2.ae2.MekanismKey";
    private static final List<String> INPUT_METHODS=List.of("getInput","getLeftInput","getRightInput","getItemInput","getFluidInput","getChemicalInput","getInputSolid");
    private final Level level;
    private final List<RecipeHolder<?>> holders;
    private final Map<AEKey,List<DependencyPlanner.Choice<AEKey,PatternRecipe>>> index=new HashMap<>();
    private int cursor;
    public MekanismChemicalRecipeAdapter(Level level){
        this.level=level;
        var found=new ArrayList<RecipeHolder<?>>();
        for(var holder:level.getRecipeManager().getRecipes())
            if(holder.value().getClass().getName().startsWith("mekanism.common.recipe"))found.add(holder);
        found.sort(Comparator.comparing(h->h.id().toString()));holders=List.copyOf(found);
    }
    @Override public String adapterId(){return "aeautopatterns:applied-mekanistics";}
    @Override public int priority(){return 60;}
    @Override public Set<AdapterCapability> capabilities(){return Set.of(AdapterCapability.ITEMS,AdapterCapability.FLUIDS,
        AdapterCapability.MULTI_INPUT,AdapterCapability.MULTI_OUTPUT,AdapterCapability.PROBABILISTIC_OUTPUTS,
        AdapterCapability.VALIDATION,AdapterCapability.AE_PROCESSING_ENCODING,AdapterCapability.ALTERNATIVES);}
    @Override public boolean indexStep(int budget){
        int end=Math.min(holders.size(),cursor+Math.max(1,budget));
        while(cursor<end)index(holders.get(cursor++));
        return cursor>=holders.size();
    }
    @Override public List<DependencyPlanner.Choice<AEKey,PatternRecipe>> find(AEKey output){return List.copyOf(index.getOrDefault(output,List.of()));}
    @Override public boolean hasRecipe(AEKey output){return index.containsKey(output);}

    private void index(RecipeHolder<?> holder){
        Object recipe=holder.value();
        var inputGroups=new ArrayList<List<StackSpec>>();
        for(String method:INPUT_METHODS){
            Object ingredient=call(recipe,method);if(ingredient==null)continue;
            List<?> reps=representations(ingredient);if(reps.isEmpty())continue;
            var specs=new ArrayList<StackSpec>();
            for(Object rep:reps){var pair=stack(rep);if(pair!=null)specs.add(new StackSpec(pair.key,neededAmount(ingredient,rep,pair.amount),StackRole.CONSUMED));}
            if(!specs.isEmpty())inputGroups.add(List.copyOf(specs));
        }
        if(inputGroups.isEmpty())return;
        Object defs=call(recipe,"getOutputDefinition");if(!(defs instanceof List<?> outputs)||outputs.isEmpty())return;
        var inputs=new ArrayList<StackSpec>();
        for(var group:inputGroups){var first=group.getFirst();var alternatives=group.stream().map(StackSpec::key).distinct().toList();inputs.add(new StackSpec(first.key(),first.amount(),StackRole.CONSUMED,alternatives.subList(1,alternatives.size())));}
        boolean chemicalInput=inputs.stream().anyMatch(input->isMekanismKey(input.key()));
        for(Object definition:outputs){
            var outputStacks=outputSet(definition);if(outputStacks.isEmpty())continue;
            if(!chemicalInput&&outputStacks.stream().noneMatch(output->isMekanismKey(output.key())))continue;
            var first=outputStacks.getFirst();
            var id=holder.id();var adapted=new AdaptedRecipe(adapterId(),id,RecipeKind.MACHINE,inputs,outputStacks,List.of(),
                new MachineRequirements(ResourceLocation.parse("mekanism:chemical_machine"),List.of("Mekanism machine and required energy must be available")),holder);
            var payload=new PatternRecipe(holder,ItemStack.EMPTY,this,adapted);
            var choice=new DependencyPlanner.Choice<AEKey,PatternRecipe>(id.toString(),DependencyPlanner.Kind.GENERATE,
                inputs.stream().map(input->{var all=new ArrayList<AEKey>();all.add(input.key());all.addAll(input.alternatives());return List.copyOf(all);}).toList(),payload);
            index.computeIfAbsent(first.key(),k->new ArrayList<>()).add(choice);
        }
    }

    private List<StackSpec> outputSet(Object definition){
        var result=new ArrayList<StackSpec>();
        var direct=stack(definition);if(direct!=null){result.add(new StackSpec(direct.key,direct.amount,StackRole.CONSUMED));return result;}
        for(String accessor:List.of("item","chemical","fluid","left","right","optionalItem","getItem","getChemical","getFluid","getLeft","getRight")){
            Object value=call(definition,accessor);var parsed=stack(value);if(parsed!=null)result.add(new StackSpec(parsed.key,parsed.amount,StackRole.CONSUMED));
        }
        return List.copyOf(result);
    }
    private List<?> representations(Object ingredient){
        if(ingredient instanceof net.minecraft.world.item.crafting.Ingredient item)return Arrays.asList(item.getItems());
        Object reps=call(ingredient,"getRepresentations");return reps instanceof List<?> values?values:List.of();
    }
    private StackAmount stack(Object value){
        if(value instanceof ItemStack item&&!item.isEmpty())return new StackAmount(AEItemKey.of(item),item.getCount());
        if(value instanceof FluidStack fluid&&!fluid.isEmpty())return new StackAmount(AEFluidKey.of(fluid),fluid.getAmount());
        if(value==null)return null;
        try{
            var loader=Thread.currentThread().getContextClassLoader();
            var keyClass=Class.forName(KEY_CLASS,false,loader==null?getClass().getClassLoader():loader);
            for(var method:keyClass.getMethods())if(method.getName().equals("of")&&method.getParameterCount()==1&&method.getParameterTypes()[0].isAssignableFrom(value.getClass())){
                Object key=method.invoke(null,value);Object amount=call(value,"getAmount");
                if(key instanceof AEKey ae&&amount instanceof Number n&&n.longValue()>0)return new StackAmount(ae,n.longValue());
            }
        }catch(ReflectiveOperationException|LinkageError ignored){}
        return null;
    }
    private static boolean isMekanismKey(AEKey key){return key.getClass().getName().equals(KEY_CLASS);}
    private static long neededAmount(Object ingredient,Object representation,long fallback){
        try{Object amount=ingredient.getClass().getMethod("getNeededAmount",representation.getClass()).invoke(ingredient,representation);
            if(amount instanceof Number n&&n.longValue()>0)return n.longValue();}catch(ReflectiveOperationException|RuntimeException ignored){}
        return fallback;
    }
    @Override public ItemStack encode(PatternRecipe payload,List<AEKey> inputs,AEKey expected,Level level){
        RecipeHolder<?> holder=payload.recipe();
        if(holder==null||level.getRecipeManager().byKey(holder.id()).orElse(null)!=holder)throw new IllegalStateException("Mekanism recipe changed; analyze again");
        ItemStack encoded=new GenericProcessingAdapter(adapterId(),priority(),List.of(payload.adapted())).encode(
            new PatternRecipe(holder,ItemStack.EMPTY,null,payload.adapted()),inputs,expected,level);
        var decoded=PatternDetailsHelper.decodePattern(encoded,level);
        if(decoded==null||decoded.getOutputs().size()!=payload.adapted().outputs().size())throw new IllegalStateException("Applied Mekanistics pattern validation failed");
        return encoded;
    }
    private static Object call(Object object,String name){
        if(object==null)return null;
        try{for(Method method:object.getClass().getMethods())if(method.getName().equals(name)&&method.getParameterCount()==0)return method.invoke(object);}
        catch(ReflectiveOperationException|RuntimeException ignored){}
        return null;
    }
    private record StackAmount(AEKey key,long amount){}
}
