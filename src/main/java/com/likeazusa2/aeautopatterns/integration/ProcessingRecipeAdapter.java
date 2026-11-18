package com.likeazusa2.aeautopatterns.integration;

import java.util.*;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.*;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import com.likeazusa2.aeautopatterns.logic.DependencyPlanner;

/** Deterministic one-input cooking recipes; fuel and machine setup remain external. */
public final class ProcessingRecipeAdapter implements RecipeAdapter {
    private final Level level;
    private final Iterator<RecipeHolder<?>> pending;
    private final Map<AEKey,List<RecipeHolder<?>>> index=new HashMap<>();
    private final List<PatternRecipe> catalog=new ArrayList<>();
    private List<PatternRecipe> catalogSnapshot;
    public ProcessingRecipeAdapter(Level level) {
        this.level=level;
        pending=level.getRecipeManager().getRecipes().iterator();
    }
    public boolean indexStep(int budget) {
        for(int i=0;i<budget&&pending.hasNext();i++) {
            var holder=pending.next();var recipe=holder.value();
            if(recipe.getClass()!=SmeltingRecipe.class&&recipe.getClass()!=BlastingRecipe.class
                &&recipe.getClass()!=SmokingRecipe.class&&recipe.getClass()!=CampfireCookingRecipe.class)continue;
            var output=recipe.getResultItem(level.registryAccess());
            if(!output.isEmpty()&&recipe.getIngredients().size()==1&&!recipe.getIngredients().getFirst().isEmpty()) {
                index.computeIfAbsent(AEItemKey.of(output),k->new ArrayList<>()).add(holder);
                catalog.add(new PatternRecipe(holder,ItemStack.EMPTY,this));
            }
        }
        if(pending.hasNext())return false;
        if(catalogSnapshot==null)catalogSnapshot=List.copyOf(catalog);
        return true;
    }
    public boolean hasRecipe(AEKey key) {return index.containsKey(key);}
    @Override public String adapterId() { return "aeautopatterns:vanilla-processing"; }
    @Override public Set<AdapterCapability> capabilities() {
        return Set.of(AdapterCapability.ITEMS,AdapterCapability.VALIDATION,AdapterCapability.AE_PROCESSING_ENCODING);
    }
    @Override public List<DependencyPlanner.Choice<AEKey,PatternRecipe>> find(AEKey key) {
        var result=new ArrayList<DependencyPlanner.Choice<AEKey,PatternRecipe>>();
        for(var holder:index.getOrDefault(key,List.of())) {
            var inputs=Arrays.stream(holder.value().getIngredients().getFirst().getItems())
                .map(AEItemKey::of).map(k->(AEKey)k).distinct().toList();
            result.add(new DependencyPlanner.Choice<>(holder.id().toString(),DependencyPlanner.Kind.GENERATE,
                List.of(inputs),new PatternRecipe(holder,ItemStack.EMPTY,this)));
        }
        result.sort(Comparator.comparing(DependencyPlanner.Choice::id));return result;
    }
    @Override public List<PatternRecipe> processingRecipes() {
        return catalogSnapshot==null?List.of():catalogSnapshot;
    }
    @Override public ItemStack encode(PatternRecipe payload,List<AEKey> inputs,AEKey expected,Level level) {
        if(level.getRecipeManager().byKey(payload.recipe().id()).orElse(null)!=payload.recipe())
            throw new IllegalStateException("Recipe changed; analyze again");
        if(inputs.size()!=1||!(inputs.getFirst() instanceof AEItemKey item))
            throw new IllegalStateException("Invalid processing input");
        var recipe=(AbstractCookingRecipe)payload.recipe().value();
        var frame=new SingleRecipeInput(item.toStack());
        if(!recipe.matches(frame,level))throw new IllegalStateException("Processing recipe no longer matches");
        var output=recipe.assemble(frame,level.registryAccess());
        if(output.isEmpty()||!expected.equals(AEItemKey.of(output)))throw new IllegalStateException("Processing output changed");
        var encoded=PatternDetailsHelper.encodeProcessingPattern(List.of(new GenericStack(item,1)),
            List.of(new GenericStack(expected,output.getCount())));
        encoded.set(DataComponents.LORE,new ItemLore(List.of(Component.literal(payload.recipe().id().toString())
            .withStyle(ChatFormatting.AQUA).withStyle(style->style.withItalic(false)))));
        var decoded=PatternDetailsHelper.decodePattern(encoded,level);
        if(decoded==null||!decoded.getPrimaryOutput().what().equals(expected)||decoded.getPrimaryOutput().amount()!=output.getCount()
            ||decoded.getInputs().length!=1||decoded.getInputs()[0].getMultiplier()!=1
            ||!decoded.getInputs()[0].getPossibleInputs()[0].what().equals(item)
            ||decoded.getInputs()[0].getPossibleInputs()[0].amount()!=1)
            throw new IllegalStateException("AE processing pattern validation failed");
        return encoded;
    }
}
