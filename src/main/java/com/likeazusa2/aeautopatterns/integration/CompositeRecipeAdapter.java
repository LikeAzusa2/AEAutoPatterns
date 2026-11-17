package com.likeazusa2.aeautopatterns.integration;

import java.util.*;
import appeng.api.stacks.AEKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import com.likeazusa2.aeautopatterns.logic.DependencyPlanner;

/** Merges built-in and ServiceLoader integrations without coupling the planner to machines. */
public final class CompositeRecipeAdapter implements RecipeAdapter {
    private final List<RecipeAdapter> delegates;
    private final Map<AEKey,List<DependencyPlanner.Choice<AEKey,PatternRecipe>>> candidates=new HashMap<>();
    private final Set<RecipeAdapter> indexed = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<RecipeAdapter> disabled = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String,Timing> timings=new LinkedHashMap<>();
    private final Map<String,String> failures=new LinkedHashMap<>();
    private final Set<AEKey> failedOutputs=new HashSet<>();
    private int indexingCursor;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("aeautopatterns.analysis.adapters");
    public record Timing(long calls,long nanos,long maxNanos) {}
    public Map<String,Timing> timings(){return Map.copyOf(timings);}
    public Map<String,String> failures(){return Map.copyOf(failures);}
    private void record(RecipeAdapter adapter,String operation,long started) {
        long elapsed=System.nanoTime()-started;String id=adapter.adapterId()+"/"+operation;
        var previous=timings.getOrDefault(id,new Timing(0,0,0));
        timings.put(id,new Timing(previous.calls()+1,previous.nanos()+elapsed,Math.max(previous.maxNanos(),elapsed)));
        if(elapsed>4_000_000&&previous.maxNanos()<=4_000_000)
            LOG.warn("Slow recipe adapter {}: {}ms in one call (soft tick budget)",id,elapsed/1_000_000.0);
    }
    private void failed(RecipeAdapter adapter,String operation,RuntimeException ex) {
        String id=adapter.adapterId()+"/"+operation;
        if(failures.putIfAbsent(id,ex.toString())==null)LOG.warn("Recipe adapter failed: {}",id,ex);
    }
    public CompositeRecipeAdapter(Collection<? extends RecipeAdapter> adapters) {
        delegates = adapters.stream().map(a -> (RecipeAdapter) a)
            .sorted(Comparator.comparingInt(RecipeAdapter::priority).reversed().thenComparing(RecipeAdapter::adapterId)).toList();
    }
    @Override public String adapterId() { return "composite"; }
    @Override public int priority() { return delegates.stream().mapToInt(RecipeAdapter::priority).max().orElse(0); }
    @Override public Set<AdapterCapability> capabilities() {
        var result=EnumSet.noneOf(AdapterCapability.class);
        delegates.forEach(a -> result.addAll(a.capabilities()));
        return Set.copyOf(result);
    }
    @Override public boolean indexStep(int budget) {
        // A single delegate call is the scheduling boundary, including third-party adapters.
        for(int i=0;i<delegates.size();i++) {
            var adapter=delegates.get(indexingCursor++%delegates.size());
            if(indexed.contains(adapter))continue;
            long started=System.nanoTime();
            try {if(adapter.indexStep(budget))indexed.add(adapter);}
            catch(RuntimeException ex){failed(adapter,"index",ex);disabled.add(adapter);indexed.add(adapter);}
            finally {record(adapter,"index",started);}
            break;
        }
        return indexed.size()==delegates.size();
    }
    @Override public List<DependencyPlanner.Choice<AEKey, PatternRecipe>> find(AEKey output) {
        if(indexed.size()==delegates.size()&&candidates.containsKey(output))return candidates.get(output);
        var all = new ArrayList<DependencyPlanner.Choice<AEKey, PatternRecipe>>();
        for (var adapter : delegates) {
            if(disabled.contains(adapter))continue;
            long started=System.nanoTime();
            try { all.addAll(adapter.find(output)); }
            catch(RuntimeException ex) {failedOutputs.add(output);failed(adapter,"find",ex);}
            finally {record(adapter,"find",started);}
        }
        all.sort(Comparator.comparing((DependencyPlanner.Choice<AEKey, PatternRecipe> c) -> c.payload() == null || c.payload().adapter() == null ? 0 : -c.payload().adapter().priority()).thenComparing(DependencyPlanner.Choice::id));
        var unique=new LinkedHashMap<String,DependencyPlanner.Choice<AEKey,PatternRecipe>>();
        all.forEach(c->unique.putIfAbsent(c.id(),c));
        var result=List.copyOf(unique.values());if(indexed.size()==delegates.size())candidates.put(output,result);return result;
    }
    @Override public boolean hasRecipe(AEKey output) {
        for(var a:delegates)if(!disabled.contains(a)) {
            long started=System.nanoTime();
            try {if(a.hasRecipe(output))return true;}catch(RuntimeException ex){failedOutputs.add(output);failed(a,"hasRecipe",ex);}
            finally {record(a,"hasRecipe",started);}
        }
        return false;
    }
    @Override public boolean unsupported(AEKey output) {
        if(failedOutputs.contains(output))return true;
        return delegates.stream().filter(a->!disabled.contains(a)).anyMatch(a -> {try{return a.unsupported(output);}catch(RuntimeException ex){failed(a,"unsupported",ex);return true;}});
    }
    @Override public ItemStack encode(PatternRecipe recipe, List<AEKey> inputs, AEKey expected, Level level) {
        if (recipe.adapter() == null) throw new IllegalStateException("Pattern has no encoder");
        return recipe.adapter().encode(recipe, inputs, expected, level);
    }
}
