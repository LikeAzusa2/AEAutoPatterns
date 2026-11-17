package com.likeazusa2.aeautopatterns.logic;

import java.util.*;
import java.util.function.Function;

/** Incremental, deterministic depth-first solver. Each step performs bounded graph work. */
public final class DependencyPlanner<K, P> {
    public enum Kind { GENERATE, NETWORK, BUFFER, EXTERNAL, UNSUPPORTED, NEEDS_RECIPE }
    public record Choice<K,P>(String id, Kind kind, List<List<K>> ingredients, P payload) {}
    public record Node<K,P>(K key, Choice<K,P> choice, List<K> children) {}
    private final Function<K,List<Choice<K,P>>> source;
    private final Map<K,List<Choice<K,P>>> choiceCache=new HashMap<>();
    private final LinkedHashMap<K,Node<K,P>> nodes = new LinkedHashMap<>();
    private final Deque<Frame> stack = new ArrayDeque<>();
    private final Set<K> active=new HashSet<>();
    private final ArrayList<K> insertionOrder=new ArrayList<>();
    private final int maxDepth, maxNodes,maxAttempts;
    private Map<K,Integer> components=Map.of();
    private Set<K> feasible=Set.of();
    private Map<K,Node<K,P>> seeds=Map.of();
    private final Map<K,Integer> seedHeights=new HashMap<>();
    private long cacheHits,reused;
    private record Failure<K>(String reason,List<K> path,String recipe) {}
    private final LinkedHashMap<Object,Failure<K>> failures=new LinkedHashMap<>(32,.75f,true);
    private record Context<K>(K key,List<Object> ancestors,Map<K,Object> committed) {}
    private int attempts;
    private String error = "";
    private String lastFailure = "";
    private List<K> failurePath = List.of();
    private String failureRecipe = "";
    private boolean done;
    private java.util.function.Predicate<K> pinned=key->false;
    public DependencyPlanner<K,P> pinned(java.util.function.Predicate<K> predicate) {pinned=predicate;return this;}
    private final class Frame {
        final K key;
        List<Choice<K,P>> choices;
        int candidate, child, mark;
        int[] variants;
        List<List<K>> available;
        List<K> selected;
        boolean initialized;
        Object context;
        boolean seedChecked;
        Frame(K key) { this.key=key; mark=nodes.size(); choices=choiceCache.get(key); }
        Choice<K,P> choice() { return choices.get(candidate); }
        List<K> children() {
            if(selected!=null)return selected;
            var result=new ArrayList<K>();
            for(int i=0;i<variants.length;i++) result.add(available.get(i).get(variants[i]));
            return selected=List.copyOf(result);
        }
    }
    public DependencyPlanner(K root, Function<K,List<Choice<K,P>>> source) { this(root, source,512,16384); }
    public DependencyPlanner(K root, Function<K,List<Choice<K,P>>> source,int depth,int count) {
        this(root,source,depth,count,262144);
    }
    public DependencyPlanner(K root, Function<K,List<Choice<K,P>>> source,int depth,int count,int attempts) {
        this.source=source; maxDepth=depth; maxNodes=count;maxAttempts=attempts;push(root);
    }
    public void graphHints(Map<K,Integer> components,Set<K> feasible,Map<K,Node<K,P>> seeds) {
        this.components=components;this.feasible=feasible;this.seeds=seeds;
        // AnalysisSession removes the complete reverse closure over ALL candidate edges.
        // Only retain closed, previously solved DAGs. Auto-external compression leaves
        // depend on their old ancestors, so neither they nor their parents certify reuse.
        for(var seed:seeds.values()) {
            if(seed.choice().id().equals("auto_external_cycle"))continue;
            int height=1;boolean closed=true;
            for(var child:seed.children()) {
                var childHeight=seedHeights.get(child);if(childHeight==null){closed=false;break;}
                height=Math.max(height,childHeight+1);
            }
            if(closed)seedHeights.put(seed.key(),height);
        }
    }
    public int attempts(){return attempts;}
    public long cacheHits(){return cacheHits;}
    public long reused(){return reused;}
    private void push(K key){stack.push(new Frame(key));active.add(key);}
    private Frame pop(){var f=stack.pop();active.remove(f.key);return f;}
    private void commit(Node<K,P> node){if(!nodes.containsKey(node.key()))insertionOrder.add(node.key());nodes.put(node.key(),node);}
    public boolean done() { return done; }
    public String error() { return error; }
    public List<K> failurePath() { return failurePath; }
    public String failureRecipe() { return failureRecipe; }
    public Map<K,Node<K,P>> nodes() { return Collections.unmodifiableMap(nodes); }
    public List<Node<K,P>> queue() {
        if(!error.isEmpty())return List.of();
        if(nodes.values().stream().anyMatch(n->n.choice().kind()==Kind.NEEDS_RECIPE))return List.of();
        return nodes.values().stream().filter(n->n.choice().kind()==Kind.GENERATE).toList();
    }
    public void step(int budget) {
        stepUntil(System.nanoTime()+2_000_000L,budget);
    }
    public void stepUntil(long deadline,int budget) {
        for(int i=0;i<budget && !done;i++) { advance();if(System.nanoTime()>=deadline)break; }
    }
    private void advance() {
        if(stack.isEmpty()) { done=true; return; }
        var f=stack.peek();
        if(!f.seedChecked) {
            f.seedChecked=true;
            var seed=seeds.get(f.key);
            var height=seedHeights.get(f.key);
            if(seed!=null&&height!=null&&stack.size()+height-1<=maxDepth) {
                // Incrementally traverse a certified subtree without reparsing choices,
                // rebuilding variants or creating failure contexts at every clean node.
                f.choices=List.of(seed.choice());f.selected=seed.children();
                f.available=seed.children().stream().map(List::of).toList();
                f.variants=new int[f.available.size()];f.initialized=true;reused++;
            }
            if(!f.initialized&&stack.size()<=64&&nodes.size()<=128) {
                var ancestors=new ArrayList<Object>();stack.stream().skip(1).forEach(parent->{
                    ancestors.add(parent.key);ancestors.add(parent.choice().id());ancestors.add(parent.children());
                });
                var committed=new HashMap<K,Object>();nodes.forEach((key,node)->committed.put(key,List.of(node.choice().id(),node.children())));
                f.context=new Context<>(f.key,List.copyOf(ancestors),Map.copyOf(committed));
                var failure=failures.get(f.context);
                if(failure!=null){cacheHits++;failurePath=failure.path();failureRecipe=failure.recipe();lastFailure=failure.reason();reject(failure.reason());return;}
            }
        }
        if(f.choices==null) {
            f.choices=choiceCache.get(f.key);
            if(f.choices==null) {
                var resolved=source.apply(f.key);
                if(resolved==null)return;
                f.choices=List.copyOf(resolved);choiceCache.put(f.key,f.choices);
            }
        }
        if(!f.initialized) {
            if(++attempts>maxAttempts) { fail("Search limit: "+maxAttempts+". Choose a recipe or external input."); return; }
            if(f.candidate>=f.choices.size()) { reject("No valid route: "+f.key); return; }
            f.variants=new int[f.choice().ingredients().size()];
            f.selected=null;
            f.mark=nodes.size(); f.child=0; f.initialized=true;
            if(f.choice().ingredients().stream().anyMatch(List::isEmpty)) { nextCandidate(f); return; }
            // Filter only against the current path, never globally: the same material
            // may have a valid route when reached from another mod's recipe branch.
            var available=new ArrayList<List<K>>();
            for(var group:f.choice().ingredients()) {
                var filtered=group.stream().filter(key->!active.contains(key)).distinct()
                    .sorted(Comparator.comparingInt(key->feasible.contains(key)?0:1)).toList();
                if(filtered.isEmpty()) {
                    if(!pinned.test(f.key)&&f.candidate==f.choices.size()-1&&isReverseCompressionCycle(f,group)) {
                        acceptAsExternal(f,"auto_external_cycle");
                        return;
                    }
                    recordCycle(group.getFirst());nextCandidate(f);return;
                }
                available.add(filtered);
            }
            f.available=available;
        }
        var children=f.children();
        if(f.child>=children.size()) {
            if(nodes.size()>=maxNodes) { fail("Node limit: "+maxNodes); return; }
            commit(new Node<>(f.key,f.choice(),List.copyOf(children)));
            pop();
            if(stack.isEmpty()) done=true;
            return;
        }
        var child=children.get(f.child);
        if(nodes.containsKey(child)) { f.child++; return; }
        if(active.contains(child)) {
            recordCycle(child); retry(f); return;
        }
        if(stack.size()>=maxDepth) { fail("Depth limit: "+maxDepth); return; }
        push(child);
    }
    /**
     * If an unpacking route points back to an ancestor which consumes this same
     * material repeatedly, stop expanding the material and treat it as supplied.
     * Example: 9 redstone -> redstone block, while redstone-from-block needs that
     * block again. This is a common source of useless compression/decompression
     * loops in large recipe sets.
     */
    private boolean isReverseCompressionCycle(Frame child,List<K> candidateInputs) {
        for(var ancestor:stack) {
            if(ancestor==child||!ancestor.initialized||ancestor.variants.length==0)continue;
            long repeated=ancestor.children().stream().filter(key->Objects.equals(key,child.key)).count();
            if(repeated>1&&candidateInputs.stream().anyMatch(key->Objects.equals(key,ancestor.key)))return true;
        }
        return false;
    }
    private void acceptAsExternal(Frame frame,String id) {
        rollback(frame.mark);
        commit(new Node<>(frame.key,new Choice<>(id,com.likeazusa2.aeautopatterns.logic.DependencyPlanner.Kind.EXTERNAL,List.of(),null),List.of()));
        pop();
        if(stack.isEmpty())done=true;
    }
    private void rollback(int mark) {
        while(insertionOrder.size()>mark)nodes.remove(insertionOrder.removeLast());
    }
    private void retry(Frame f) {
        f.selected=null;
        int failedSlot=Math.min(f.child,f.variants.length-1);
        rollback(f.mark); f.child=0;
        // Inputs after the failing slot cannot repair its dependency route.
        // Skip that entire suffix instead of enumerating its Cartesian product
        // (notably nine interchangeable ingots in compression recipes).
        for(int i=failedSlot+1;i<f.variants.length;i++)f.variants[i]=0;
        for(int i=failedSlot;i>=0;i--) {
            if(++f.variants[i]<f.available.get(i).size()) {
                if(++attempts>maxAttempts) fail("Search limit: "+maxAttempts);
                return;
            }
            f.variants[i]=0;
        }
        nextCandidate(f);
    }
    private void nextCandidate(Frame f) {
        f.candidate++; f.initialized=false;
        if(f.candidate>=f.choices.size()) reject(lastFailure.isEmpty()?"No valid route: "+f.key:lastFailure);
    }
    private void recordCycle(K child) {
        var path=new ArrayList<K>();stack.descendingIterator().forEachRemaining(parent->path.add(parent.key));
        path.add(child);failurePath=List.copyOf(path);
        failureRecipe=stack.peek().choice().id();lastFailure="Cycle: "+path;
    }
    private void reject(String reason) {
        if(!reason.equals(lastFailure))captureFailure();
        lastFailure=reason;
        var failed=pop();
        if(failed.context!=null){failures.put(failed.context,new Failure<>(reason,failurePath,failureRecipe));if(failures.size()>512)failures.remove(failures.keySet().iterator().next());}
        rollback(failed.mark);
        if(stack.isEmpty()) fail(reason); else retry(stack.peek());
    }
    private void captureFailure() {
        var path=new ArrayList<K>();stack.descendingIterator().forEachRemaining(parent->path.add(parent.key));
        failurePath=List.copyOf(path);
        var frame=stack.peek();
        failureRecipe=frame!=null&&frame.choices!=null&&frame.candidate<frame.choices.size()?frame.choice().id():"";
    }
    private void fail(String reason) {
        if(!reason.equals(lastFailure)||failurePath.isEmpty())captureFailure();
        error=reason; done=true;
    }
}
