package com.likeazusa2.aeautopatterns.logic;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static com.likeazusa2.aeautopatterns.logic.DependencyPlanner.Kind.*;

class AnalysisSessionTest {
    private static final AnalysisSession.Limits LIMITS=new AnalysisSession.Limits(512,16384,262144,262144);
    private static DependencyPlanner.Choice<Integer,String> c(String id,Integer... children) {
        return new DependencyPlanner.Choice<>(id,GENERATE,Arrays.stream(children).map(List::of).toList(),id);
    }
    private static <K,P> void finish(AnalysisSession<K,P> session) {
        for(int i=0;i<100000&&!session.done();i++)session.step(System.nanoTime()+20_000_000);
        assertTrue(session.done(),"Session must terminate");
    }
    @Test void accepts512LevelsButReports513AndLimitsEdges() {
        var p=new AnalysisSession<>(0,k->List.of(k==511?c("leaf"):c("next",k+1)),k->false,LIMITS);
        finish(p);assertEquals("",p.error());assertEquals(512,p.nodes().size());assertEquals(511,p.queue().getFirst().key());
        var q=new AnalysisSession<>(0,k->List.of(k==512?c("leaf"):c("next",k+1)),k->false,LIMITS);
        finish(q);assertTrue(q.error().contains("Depth"));assertTrue(q.queue().isEmpty());
        var edge=new AnalysisSession<>(0,k->List.of(c("many",1,2,3)),k->false,new AnalysisSession.Limits(512,100,2,100));
        finish(edge);assertTrue(edge.error().contains("edge"));assertTrue(edge.queue().isEmpty());
        assertEquals(List.of(0),edge.failurePath());
    }
    @Test void wideSharedGraphHas16384UniqueNodesAndStableOrder() {
        Function<Integer,List<DependencyPlanner.Choice<Integer,String>>> source=k->List.of(k==0
            ?new DependencyPlanner.Choice<>("root",GENERATE,java.util.stream.IntStream.range(1,8192).mapToObj(i->List.of(i)).toList(),"root")
            :k<8192?c("branch",8192+k):k==8192?c("shared"):c("leaf",8192));
        var p=new AnalysisSession<>(0,source,k->false,LIMITS);finish(p);
        assertEquals("",p.error());assertEquals(16384,p.nodes().size());
        var queue=p.queue();var positions=new HashMap<Integer,Integer>();for(int i=0;i<queue.size();i++)positions.put(queue.get(i).key(),i);
        for(var n:p.nodes().values())for(var child:n.children())assertTrue(positions.get(child)<positions.get(n.key()));
    }
    @Test void asyncDiscoveryContinuesSiblingsAndOrderingWaits() {
        var ready=new java.util.concurrent.atomic.AtomicBoolean();var called=new HashSet<Integer>();
        var p=new AnalysisSession<>(0,k->{called.add(k);return k==0?List.of(c("root",1,2)):k==1&&!ready.get()?null:List.of(c("leaf"));},k->false,LIMITS);
        for(int i=0;i<10;i++)p.step(System.nanoTime()+20_000_000);
        assertTrue(called.contains(2));assertFalse(p.done());ready.set(true);finish(p);assertEquals("",p.error());
        ready.set(false);var waiting=new AnalysisSession<>(0,k->List.of(c("leaf")),k->false,LIMITS).awaitOrdering(ready::get);
        waiting.step(System.nanoTime()+20_000_000);assertEquals(AnalysisSession.Phase.ORDERING,waiting.phase());
        ready.set(true);finish(waiting);assertEquals("",waiting.error());
    }
    @Test void cycleWithExitAndLockedCycleAndCompressionRemainCorrect() {
        var graph=Map.of(0,List.of(c("root",1)),1,List.of(c("cycle",0),c("exit",2)),2,List.of(c("leaf")));
        var p=new AnalysisSession<>(0,graph::get,k->false,LIMITS);finish(p);assertEquals("",p.error());assertEquals("exit",p.nodes().get(1).choice().id());
        var q=new AnalysisSession<>(0,k->k==1?List.of(c("cycle",0)):graph.get(k),k->k==1,LIMITS);
        finish(q);assertTrue(q.error().contains("Cycle"));assertEquals(List.of(0,1,0),q.failurePath());
        var compression=new AnalysisSession<>(0,k->List.of(k==0?c("pack",1,1,1,1,1,1,1,1,1):c("unpack",0)),k->false,LIMITS);
        finish(compression);assertEquals("",compression.error());assertEquals(EXTERNAL,compression.nodes().get(1).choice().kind());
    }
    @Test void reviseReusesUnaffectedCandidatesAndEqualsFullAnalysis() {
        var graph=new HashMap<Integer,List<DependencyPlanner.Choice<Integer,String>>>();
        graph.put(0,List.of(c("root",1,2)));graph.put(1,List.of(c("old",3)));graph.put(2,List.of(c("unrelated",4)));
        graph.put(3,List.of(c("leaf")));graph.put(4,List.of(c("leaf")));graph.put(5,List.of(c("newLeaf")));
        var calls=new HashMap<Integer,Integer>();Function<Integer,List<DependencyPlanner.Choice<Integer,String>>> source=k->{calls.merge(k,1,Integer::sum);return graph.get(k);};
        var p=new AnalysisSession<>(0,source,k->false,LIMITS);finish(p);calls.clear();graph.put(1,List.of(c("new",5)));
        var revised=p.revise(1,source);finish(revised);var full=new AnalysisSession<>(0,graph::get,k->false,LIMITS);finish(full);
        assertEquals("",revised.error());assertEquals(full.nodes(),revised.nodes());assertFalse(calls.containsKey(2));assertFalse(calls.containsKey(4));
        assertTrue(revised.metrics().reused()>0);assertFalse(revised.nodes().containsKey(3));
    }
    @Test void newlyIntroducedCycleCannotReuseIncompatibleOldBranch() {
        var graph=new HashMap<Integer,List<DependencyPlanner.Choice<Integer,String>>>();
        graph.put(0,List.of(c("root",1,2)));graph.put(1,List.of(c("leaf")));graph.put(2,List.of(c("viaRoot",0),c("leaf")));
        var p=new AnalysisSession<>(0,graph::get,k->false,LIMITS);finish(p);
        graph.put(1,List.of(c("new",2)));var revised=p.revise(1,graph::get);finish(revised);
        var full=new AnalysisSession<>(0,graph::get,k->false,LIMITS);finish(full);assertEquals(full.nodes(),revised.nodes());
    }
    @Test void limitsAndCandidateReorderingRemainAuthoritative() {
        var nodes=new AnalysisSession<>(0,k->List.of(c("next",k+1)),k->false,new AnalysisSession.Limits(512,4,100,100));
        finish(nodes);assertTrue(nodes.error().contains("material"));assertEquals(List.of(0,1,2,3,4),nodes.failurePath());assertTrue(nodes.queue().isEmpty());
        var attempts=new AnalysisSession<>(0,k->List.of(k==0?c("root",1):c("leaf")),k->false,new AnalysisSession.Limits(512,100,100,1));
        finish(attempts);assertTrue(attempts.error().contains("Search"));assertTrue(attempts.queue().isEmpty());
        var calls=new AtomicInteger();
        var order=new AnalysisSession<Integer,String>(0,k->{calls.incrementAndGet();return List.of(c("first"),c("preferred"));},k->false,LIMITS)
            .orderWith((key,choices)->choices.reversed());
        finish(order);assertEquals("preferred",order.nodes().get(0).choice().id());assertEquals(1,calls.get(),"Ordering cannot reparse or add server candidates");
    }
    @Test void unsupportedAndSelectionRemainExplicitAndDoNotInventExternal() {
        var p=new AnalysisSession<>(0,k->k==0?List.of(c("root",1,2)):List.of(new DependencyPlanner.Choice<Integer,String>("pending",k==1?UNSUPPORTED:NEEDS_RECIPE,List.of(),null)),k->false,LIMITS);
        finish(p);assertEquals(UNSUPPORTED,p.nodes().get(1).choice().kind());assertEquals(NEEDS_RECIPE,p.nodes().get(2).choice().kind());assertTrue(p.queue().isEmpty());
    }
    @Test void randomSmallGraphsAgreeWithIndependentExhaustiveOracle() {
        var random=new Random(713058);
        for(int trial=0;trial<500;trial++) {
            int count=5;var graph=new HashMap<Integer,List<DependencyPlanner.Choice<Integer,String>>>();
            for(int k=0;k<count;k++) {
                var choices=new ArrayList<DependencyPlanner.Choice<Integer,String>>();
                for(int r=0;r<1+random.nextInt(2);r++) {
                    var groups=new ArrayList<List<Integer>>();
                    for(int s=0;s<random.nextInt(3);s++) {
                        var variants=new LinkedHashSet<Integer>();variants.add(random.nextInt(count));if(random.nextBoolean())variants.add(random.nextInt(count));groups.add(List.copyOf(variants));
                    }
                    choices.add(new DependencyPlanner.Choice<>("r"+k+"_"+r,GENERATE,List.copyOf(groups),"r"));
                }
                graph.put(k,choices);
            }
            var p=new AnalysisSession<>(0,graph::get,k->true,LIMITS);finish(p);
            boolean possible=oracle(0,graph,new HashSet<>());
            assertEquals(possible,p.error().isEmpty(),"trial="+trial+" graph="+graph+" error="+p.error());
            if(possible) {
                assertEquals(GENERATE,p.nodes().get(0).choice().kind());
                assertTrue(acyclic(0,p.nodes(),new HashSet<>(),new HashSet<>()));
            }
        }
    }
    @Test void randomIncrementalEditsMatchFreshFeasibilityAndNeverIntroduceCycles() {
        var random=new Random(94831);
        for(int trial=0;trial<300;trial++) {
            var graph=new LinkedHashMap<Integer,List<DependencyPlanner.Choice<Integer,String>>>();
            for(int k=0;k<12;k++)graph.put(k,List.of(c("r"+k,random.nextInt(12)),c("leaf"+k)));
            var p=new AnalysisSession<>(0,graph::get,k->true,LIMITS);finish(p);
            int changed=random.nextInt(12);
            graph.put(changed,random.nextBoolean()?List.of(c("edited",random.nextInt(12))):List.of(c("edited",random.nextInt(12)),c("exit")));
            var revised=p.revise(changed,graph::get);finish(revised);
            var full=new AnalysisSession<>(0,graph::get,k->true,LIMITS);finish(full);
            assertEquals(full.error().isEmpty(),revised.error().isEmpty(),"trial="+trial);
            if(revised.error().isEmpty())assertTrue(acyclic(0,revised.nodes(),new HashSet<>(),new HashSet<>()));
        }
    }
    private boolean oracle(int key,Map<Integer,List<DependencyPlanner.Choice<Integer,String>>> graph,Set<Integer> path) {
        if(!path.add(key))return false;
        try {
            for(var c:graph.get(key)) {
                boolean valid=true;
                for(var group:c.ingredients()) {
                    boolean found=false;for(var v:group)if(oracle(v,graph,path)){found=true;break;}
                    if(!found){valid=false;break;}
                }
                if(valid)return true;
            }
            return false;
        } finally {path.remove(key);}
    }
    private boolean acyclic(int key,Map<Integer,DependencyPlanner.Node<Integer,String>> nodes,Set<Integer> path,Set<Integer> done) {
        if(done.contains(key))return true;if(!path.add(key))return false;
        for(var child:nodes.get(key).children())if(!acyclic(child,nodes,path,done))return false;
        path.remove(key);done.add(key);return true;
    }
}
