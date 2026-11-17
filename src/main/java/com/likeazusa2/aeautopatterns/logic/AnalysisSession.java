package com.likeazusa2.aeautopatterns.logic;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/** Incremental AND/OR graph discovery, iterative SCCs, feasibility propagation and solving. */
public final class AnalysisSession<K,P> {
    public enum Phase { DISCOVER, ORDERING, COMPONENTS, PROPAGATE, SOLVE, COMPLETE, ERROR }
    public record Limits(int depth,int nodes,int edges,int attempts) {}
    public record Metrics(String phase,int materials,long edges,int components,long steps,long attempts,long cacheHits,long reused,long nanos) {}
    private final K root;
    private final Function<K,List<DependencyPlanner.Choice<K,P>>> source;
    private final Predicate<K> pinned;
    private final Limits limits;
    private final LinkedHashMap<K,List<DependencyPlanner.Choice<K,P>>> graph=new LinkedHashMap<>();
    private final Map<K,Set<K>> outgoing=new HashMap<>(),reverse=new HashMap<>();
    private final LinkedHashSet<K> pending=new LinkedHashSet<>();
    private final Set<K> known=new HashSet<>();
    private final Map<K,K> discoveryParents=new HashMap<>();
    private List<K> graphFailurePath=List.of();
    private final Map<K,Integer> components=new HashMap<>();
    private final List<K> order=new ArrayList<>();
    private final Set<K> seen=new HashSet<>(),feasible=new HashSet<>();
    private final Deque<Visit<K>> visits=new ArrayDeque<>();
    private Iterator<K> roots;
    private int componentCount,orderIndex;
    private boolean reversePass;
    private boolean ambiguous;
    private Iterator<K> expansion;
    private K expanding;
    private Phase phase=Phase.DISCOVER;
    private String error="";
    private DependencyPlanner<K,P> planner;
    private long edges,steps,nanos,sourceHits,reused;
    private final Map<K,List<DependencyPlanner.Choice<K,P>>> cached;
    private final Map<K,DependencyPlanner.Node<K,P>> seeds;
    private Map<K,Integer> previousComponents=Map.of();
    private Set<K> previousFeasible=Set.of();
    private final Set<K> clean=new HashSet<>();
    private final Map<Integer,Integer> componentIds=new HashMap<>();
    private Iterator<K> reuseRoots;
    private final Map<K,List<Group<K>>> waiting=new HashMap<>();
    private final Deque<K> propagation=new ArrayDeque<>();
    private Iterator<Map.Entry<K,List<DependencyPlanner.Choice<K,P>>>> propagationRoots;
    private java.util.function.BooleanSupplier ready=()->true;
    private java.util.function.BiFunction<K,List<DependencyPlanner.Choice<K,P>>,List<DependencyPlanner.Choice<K,P>>> ordering=(key,choices)->choices;
    private Iterator<Map.Entry<K,List<DependencyPlanner.Choice<K,P>>>> refreshing;
    private Iterator<Group<K>> notifications;
    private K propagationKey;
    private Iterator<DependencyPlanner.Choice<K,P>> rules=Collections.emptyIterator();
    private Iterator<List<K>> groups=Collections.emptyIterator();
    private Iterator<K> alternatives=Collections.emptyIterator();
    private Rule<K> currentRule;
    private Group<K> currentGroup;
    private final Set<K> groupKeys=new HashSet<>();
    private record Visit<K>(K key,Iterator<K> children) {}
    private static final class Rule<K> { final K output; int missing; Rule(K k,int count){output=k;missing=count;} }
    private static final class Group<K> { final Rule<K> rule; boolean satisfied; Group(Rule<K> r){rule=r;} }

    public AnalysisSession(K root,Function<K,List<DependencyPlanner.Choice<K,P>>> source,Predicate<K> pinned,Limits limits) {
        this(root,source,pinned,limits,Map.of(),Map.of());
    }
    private AnalysisSession(K root,Function<K,List<DependencyPlanner.Choice<K,P>>> source,Predicate<K> pinned,Limits limits,
            Map<K,List<DependencyPlanner.Choice<K,P>>> cached,Map<K,DependencyPlanner.Node<K,P>> seeds) {
        this.root=root;this.source=source;this.pinned=pinned;this.limits=limits;this.cached=cached;this.seeds=seeds;
        pending.add(root);known.add(root);
    }
    public AnalysisSession<K,P> revise(K changed,Function<K,List<DependencyPlanner.Choice<K,P>>> newSource) {
        var dirty=new HashSet<K>();var todo=new ArrayDeque<K>();dirty.add(changed);todo.add(changed);
        while(!todo.isEmpty())for(var parent:reverse.getOrDefault(todo.remove(),Set.of()))if(dirty.add(parent))todo.add(parent);
        var keep=new LinkedHashMap<>(graph);dirty.forEach(keep::remove);
        var retained=new LinkedHashMap<K,DependencyPlanner.Node<K,P>>();
        nodes().forEach((key,node)->{if(!dirty.contains(key))retained.put(key,node);});
        var next=new AnalysisSession<>(root,newSource,pinned,limits,keep,retained);next.ready=ready;next.ordering=ordering;
        next.previousComponents=Map.copyOf(components);next.previousFeasible=Set.copyOf(feasible);return next;
    }
    public AnalysisSession<K,P> awaitOrdering(java.util.function.BooleanSupplier ready){this.ready=ready;return this;}
    /** Reorder the discovered choices only; recommendations must never change graph edges. */
    public AnalysisSession<K,P> orderWith(java.util.function.BiFunction<K,List<DependencyPlanner.Choice<K,P>>,List<DependencyPlanner.Choice<K,P>>> ordering){this.ordering=ordering;return this;}
    public Phase phase(){return phase;}
    public Set<K> materialKeys(){return Collections.unmodifiableSet(graph.keySet());}
    public boolean done(){return phase==Phase.COMPLETE||phase==Phase.ERROR;}
    public String error(){return error.isEmpty()&&planner!=null?planner.error():error;}
    public Map<K,DependencyPlanner.Node<K,P>> nodes(){return planner==null?Map.of():planner.nodes();}
    public List<DependencyPlanner.Node<K,P>> queue(){return planner==null?List.of():planner.queue();}
    public List<K> failurePath(){return planner==null?graphFailurePath:planner.failurePath();}
    public String failureRecipe(){return planner==null?"":planner.failureRecipe();}
    public Metrics metrics(){return new Metrics(phase.name(),known.size(),edges,componentCount,steps,
        planner==null?0:planner.attempts(),sourceHits+(planner==null?0:planner.cacheHits()),reused,nanos);}
    public void step(long deadline) {
        long start=System.nanoTime();
        try {
            int operations=0;
            while(!done()&&System.nanoTime()<deadline&&operations++<4096) {
                steps++;
                switch(phase) {
                    case DISCOVER -> {if(!discover())return;}
                    case ORDERING -> {
                        if(!ready.getAsBoolean())return;
                        if(refreshing.hasNext()) {
                            var entry=refreshing.next();
                            // JEI can reorder validated candidates, never add client recipes.
                            entry.setValue(List.copyOf(ordering.apply(entry.getKey(),entry.getValue())));
                        } else if(!ambiguous)beginSolver();
                        else {phase=Phase.COMPONENTS;roots=graph.keySet().iterator();reuseRoots=graph.keySet().iterator();}
                    }
                    case COMPONENTS -> componentStep();
                    case PROPAGATE -> propagationStep();
                    case SOLVE -> {
                        planner.stepUntil(deadline,4096);
                        if(planner.done()){phase=planner.error().isEmpty()?Phase.COMPLETE:Phase.ERROR;reused=planner.reused();}
                        return;
                    }
                    default -> {return;}
                }
            }
        } finally {nanos+=System.nanoTime()-start;}
    }
    private boolean discover() {
        if(expansion!=null) {
            if(expansion.hasNext()) {
                var child=expansion.next();if(++edges>limits.edges()){failAt("Candidate edge limit: "+limits.edges(),expanding);return true;}
                outgoing.computeIfAbsent(expanding,k->new LinkedHashSet<>()).add(child);
                reverse.computeIfAbsent(child,k->new LinkedHashSet<>()).add(expanding);
                if(known.add(child)) {
                    discoveryParents.put(child,expanding);
                    if(known.size()>limits.nodes()){failAt("Candidate material limit: "+limits.nodes(),child);return true;}
                    pending.add(child);
                }
            } else expansion=null;
            return true;
        }
        if(pending.isEmpty()) {phase=Phase.ORDERING;refreshing=graph.entrySet().iterator();return true;}
        // A null result denotes asynchronous lookup; rotate instead of blocking siblings.
        int checked=0;
        for(var it=pending.iterator();it.hasNext()&&checked++<32;) {
            var key=it.next();var choices=cached.get(key);
            if(choices!=null)sourceHits++;else choices=source.apply(key);
            if(choices==null)continue;
            ambiguous|=choices.size()>1||choices.stream().anyMatch(c->c.ingredients().stream().anyMatch(group->group.size()>1));
            graph.put(key,List.copyOf(choices));it.remove();expanding=key;
            expansion=choices.stream().flatMap(c->c.ingredients().stream()).flatMap(Collection::stream).iterator();
            return true;
        }
        // Rotate pending keys so a slow batch does not starve later discoveries.
        var rotate=new ArrayList<K>();var it=pending.iterator();
        for(int i=0;i<checked&&it.hasNext();i++){rotate.add(it.next());it.remove();}pending.addAll(rotate);
        return false;
    }
    private void componentStep() {
        if(reuseRoots.hasNext()) {
            var key=reuseRoots.next();var old=previousComponents.get(key);
            // Clean candidates cannot reach dirty candidates: revise removed their
            // complete reverse closure. Their SCC and feasibility results stay valid.
            if(cached.containsKey(key)&&old!=null) {
                clean.add(key);seen.add(key);
                int id=componentIds.computeIfAbsent(old,k->++componentCount);components.put(key,id);
                if(previousFeasible.contains(key))feasible.add(key);
            }
            return;
        }
        if(!visits.isEmpty()) {
            var visit=visits.peek();
            if(visit.children().hasNext()) {
                var child=visit.children().next();
                if(reversePass?!components.containsKey(child):seen.add(child)) {
                    if(reversePass)components.put(child,componentCount);
                    visits.push(new Visit<>(child,(reversePass?reverse:outgoing).getOrDefault(child,Set.of()).iterator()));
                }
            } else {visits.pop();if(!reversePass)order.add(visit.key());}
            return;
        }
        if(!reversePass) {
            if(roots.hasNext()){var k=roots.next();if(seen.add(k))visits.push(new Visit<>(k,outgoing.getOrDefault(k,Set.of()).iterator()));}
            else {reversePass=true;orderIndex=order.size()-1;}
        } else if(orderIndex>=0) {
            var k=order.get(orderIndex--);if(!components.containsKey(k)){componentCount++;components.put(k,componentCount);visits.push(new Visit<>(k,reverse.getOrDefault(k,Set.of()).iterator()));}
        } else {phase=Phase.PROPAGATE;propagationRoots=graph.entrySet().iterator();}
    }
    private void propagationStep() {
        if(alternatives.hasNext()) {
            var child=alternatives.next();
            if(groupKeys.add(child)&&!currentGroup.satisfied) {
                if(feasible.contains(child)) {
                    currentGroup.satisfied=true;if(--currentGroup.rule.missing==0)markFeasible(currentGroup.rule.output);
                } else waiting.computeIfAbsent(child,k->new ArrayList<>()).add(currentGroup);
            }
        } else if(groups.hasNext()) {
            currentGroup=new Group<>(currentRule);groupKeys.clear();alternatives=groups.next().iterator();
        } else if(rules.hasNext()) {
            var choice=rules.next();
            if(choice.kind()==DependencyPlanner.Kind.UNSUPPORTED)return;
            currentRule=new Rule<>(propagationKey,choice.ingredients().size());
            if(currentRule.missing==0)markFeasible(currentRule.output);
            else groups=choice.ingredients().iterator();
        } else if(propagationRoots.hasNext()) {
            var entry=propagationRoots.next();propagationKey=entry.getKey();if(!clean.contains(propagationKey))rules=entry.getValue().iterator();
        } else if(notifications!=null&&notifications.hasNext()) {
            var group=notifications.next();if(!group.satisfied){group.satisfied=true;if(--group.rule.missing==0)markFeasible(group.rule.output);}
        } else if(!propagation.isEmpty())notifications=waiting.getOrDefault(propagation.remove(),List.of()).iterator();
        else {
            beginSolver();
        }
    }
    private void beginSolver() {
        // No OR choices means there is nothing to rank or backtrack. The iterative
        // solver already detects cycles; avoid SCC/worklist overhead on simple DAGs.
        planner=new DependencyPlanner<>(root,graph::get,limits.depth(),limits.nodes(),limits.attempts()).pinned(pinned);
        planner.graphHints(components,feasible,seeds);phase=Phase.SOLVE;
    }
    private void markFeasible(K key){if(feasible.add(key))propagation.add(key);}
    private void failAt(String reason,K key) {
        var path=new ArrayList<K>();
        for(K at=key;at!=null;at=discoveryParents.get(at))path.add(at);
        Collections.reverse(path);graphFailurePath=List.copyOf(path);fail(reason);
    }
    private void fail(String reason){error=reason;phase=Phase.ERROR;}
}
