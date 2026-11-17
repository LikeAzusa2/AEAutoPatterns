package com.likeazusa2.aeautopatterns.integration;

import java.lang.reflect.Method;
import java.util.*;
import appeng.api.stacks.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.Level;
import com.likeazusa2.aeautopatterns.logic.DependencyPlanner;

/**
 * Conservative optional bridge for mods that expose ordinary item Ingredients and result stacks.
 * It never guesses fluid/chemical fields: those recipes remain available to a dedicated provider.
 */
public final class ReflectiveModRecipeAdapter implements RecipeAdapter {
    private final String id, packagePrefix;
    private final int priority;
    private final GenericProcessingAdapter delegate;
    private final List<AdaptedRecipe> recipes;

    public ReflectiveModRecipeAdapter(String id, String packagePrefix, int priority, Level level) {
        this.id=id;this.packagePrefix=packagePrefix;this.priority=priority;
        recipes=discover(level);
        delegate=new GenericProcessingAdapter(id, priority, recipes);
    }
    @Override public String adapterId(){return id;}
    @Override public int priority(){return priority;}
    @Override public Set<AdapterCapability> capabilities(){
        return Set.of(AdapterCapability.ITEMS,AdapterCapability.MULTI_INPUT,AdapterCapability.MULTI_OUTPUT,
            AdapterCapability.VALIDATION,AdapterCapability.AE_PROCESSING_ENCODING);
    }
    @Override public List<DependencyPlanner.Choice<AEKey,PatternRecipe>> find(AEKey output){
        return delegate.find(output).stream().map(c -> new DependencyPlanner.Choice<>(c.id(),c.kind(),c.ingredients(),
            new PatternRecipe(c.payload().recipe(),c.payload().existing(),this,c.payload().adapted()))).toList();
    }
    @Override public ItemStack encode(PatternRecipe recipe,List<AEKey> inputs,AEKey expected,Level level){
        return delegate.encode(new PatternRecipe(recipe.recipe(),recipe.existing(),delegate,recipe.adapted()),inputs,expected,level);
    }
    @Override public boolean hasRecipe(AEKey key){return !find(key).isEmpty();}

    private List<AdaptedRecipe> discover(Level level) {
        var result=new ArrayList<AdaptedRecipe>();
        try {
            Object manager=level.getRecipeManager();
            Method all=Arrays.stream(manager.getClass().getMethods()).filter(m->m.getName().equals("getRecipes")&&m.getParameterCount()==0).findFirst().orElse(null);
            if(all==null)return List.of();
            Object value=all.invoke(manager); if(!(value instanceof Iterable<?> iterable))return List.of();
            for(Object holder:iterable){
                Object recipe=call(holder,"value");
                if(recipe==null||recipe instanceof CraftingRecipe||!recipe.getClass().getName().startsWith(packagePrefix))continue;
                ResourceLocation id=asId(call(holder,"id")); if(id==null)continue;
                ItemStack output=output(recipe,level); if(output.isEmpty())continue;
                Object rawIngredients=call(recipe,"getIngredients"); if(!(rawIngredients instanceof List<?> list)||list.isEmpty())continue;
                var inputs=new ArrayList<StackSpec>(); boolean valid=true;
                for(Object raw:list){
                    if(!(raw instanceof Ingredient ingredient)||ingredient.isEmpty()){valid=false;break;}
                    var items=ingredient.getItems();if(items.length==0){valid=false;break;}
                    var alternatives=Arrays.stream(items).map(AEItemKey::of).map(k->(AEKey)k).distinct().toList();
                    inputs.add(new StackSpec(alternatives.getFirst(),1,StackRole.CONSUMED,alternatives.subList(1,alternatives.size())));
                }
                if(valid)result.add(new AdaptedRecipe(id.getNamespace()+":"+id.getPath(),id,RecipeKind.MACHINE,inputs,
                    List.of(new StackSpec(AEItemKey.of(output),output.getCount(),StackRole.CONSUMED)),List.of(),
                    MachineRequirements.none(ResourceLocation.parse(id.getNamespace()+":machine")),recipe));
            }
        } catch(ReflectiveOperationException|RuntimeException ignored) { }
        return List.copyOf(result);
    }
    private static Object call(Object object,String name){
        try { return Arrays.stream(object.getClass().getMethods()).filter(m->m.getName().equals(name)&&m.getParameterCount()==0).findFirst().map(m->{try{return m.invoke(object);}catch(Exception e){return null;}}).orElse(null); }
        catch(RuntimeException e){return null;}
    }
    private static ResourceLocation asId(Object value){return value instanceof ResourceLocation id?id: value==null?null:ResourceLocation.tryParse(value.toString());}
    private static ItemStack output(Object recipe,Level level){
        try {
            for(Method method:recipe.getClass().getMethods()) if(method.getName().equals("getResultItem")) {
                Object value=method.getParameterCount()==1?method.invoke(recipe,level.registryAccess()):method.invoke(recipe);
                if(value instanceof ItemStack stack)return stack;
            }
        } catch(Exception ignored) { }
        return ItemStack.EMPTY;
    }
}
