package com.likeazusa2.aeautopatterns.logic;
import java.util.*;
import appeng.api.crafting.*;
import appeng.api.networking.IGrid;
import appeng.api.stacks.*;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.ItemStackHandler;
import com.likeazusa2.aeautopatterns.integration.PatternRecipe;

public final class PatternLookup {
    private final IGrid grid;
    private final Level level;
    private final ItemStackHandler inventory;
    private final List<net.minecraft.world.item.ItemStack> snapshot=new ArrayList<>();
    private final List<IPatternDetails> decoded=new ArrayList<>();
    private final Map<AEKey,Boolean> presence=new HashMap<>();
    public PatternLookup(IGrid grid, Level level, ItemStackHandler inventory) { this.grid=grid;this.level=level;this.inventory=inventory; }
    public List<DependencyPlanner.Choice<AEKey,PatternRecipe>> find(AEKey key) {
        var result=new ArrayList<DependencyPlanner.Choice<AEKey,PatternRecipe>>();
        if(grid!=null) for(var pattern:grid.getCraftingService().getCraftingFor(key)) add(result,pattern,DependencyPlanner.Kind.NETWORK);
        for(int i=1;i<inventory.getSlots();i++) {
            int at=i-1;var stack=inventory.getStackInSlot(i);
            if(snapshot.size()<=at){snapshot.add(stack.copy());decoded.add(PatternDetailsHelper.decodePattern(stack,level));}
            else if(!net.minecraft.world.item.ItemStack.matches(snapshot.get(at),stack)) {
                snapshot.set(at,stack.copy());decoded.set(at,PatternDetailsHelper.decodePattern(stack,level));presence.clear();
            }
            var pattern=decoded.get(at);
            if(pattern!=null && pattern.getOutputs().stream().anyMatch(out->out.what().equals(key))) add(result,pattern,DependencyPlanner.Kind.BUFFER);
        }
        result.sort(Comparator.comparing((DependencyPlanner.Choice<AEKey,PatternRecipe> c)->c.kind().ordinal()).thenComparing(DependencyPlanner.Choice::id));
        return result;
    }
    private void add(List<DependencyPlanner.Choice<AEKey,PatternRecipe>> list, IPatternDetails pattern,DependencyPlanner.Kind kind) {
        var id=kind.name()+":"+pattern.getDefinition().toTagGeneric(level.registryAccess());
        if(list.stream().anyMatch(c->c.id().equals(id))) return;
        var ingredients=new ArrayList<List<AEKey>>();
        for(var in:pattern.getInputs()) ingredients.add(Arrays.stream(in.getPossibleInputs()).map(GenericStack::what).distinct().toList());
        list.add(new DependencyPlanner.Choice<>(id,kind,ingredients,new PatternRecipe(null,pattern.getDefinition().toStack(),null)));
    }
    public boolean exists(AEKey key) { return !find(key).isEmpty(); }
    public boolean existsCached(AEKey key){return presence.computeIfAbsent(key,this::exists);}
    public void invalidate(){presence.clear();}
}
