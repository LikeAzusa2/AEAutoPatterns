package com.likeazusa2.aeautopatterns.logic;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.likeazusa2.aeautopatterns.logic.DependencyPlanner.Kind.*;
class DependencyPlannerTest {
    @Test void pendingBranchKeepsIndependentWorkAndBlocksEncoding() {
        var p=solve(Map.of("A",List.of(c("a",GENERATE,"B","C","B")),
            "B",List.of(c("choose",NEEDS_RECIPE)),"C",List.of(c("c",GENERATE,"D"))));
        assertEquals("",p.error());
        assertEquals(NEEDS_RECIPE,p.nodes().get("B").choice().kind());
        assertTrue(p.nodes().containsKey("D"));
        assertEquals(4,p.nodes().size());
        assertTrue(p.queue().isEmpty());
    }
    @Test void confirmedCompressionCycleIsNotSilentlyExternal() {
        var graph=Map.of("A",List.of(c("pack",GENERATE,"B","B")),"B",List.of(c("unpack",GENERATE,"A")));
        var p=new DependencyPlanner<String,String>("A",graph::get).pinned(k->k.equals("B"));
        for(int i=0;i<100&&!p.done();i++)p.step(1);
        assertTrue(p.done());assertTrue(p.error().contains("Cycle"));
        assertTrue(p.nodes().values().stream().noneMatch(n->n.choice().kind()==EXTERNAL));
    }
    private DependencyPlanner.Choice<String,String> c(String id,DependencyPlanner.Kind kind,String... in) {
        return new DependencyPlanner.Choice<>(id,kind,Arrays.stream(in).map(List::of).toList(),id);
    }
    private DependencyPlanner<String,String> solve(Map<String,List<DependencyPlanner.Choice<String,String>>> graph) {
        var p=new DependencyPlanner<String,String>("A", k->graph.getOrDefault(k,List.of(c(k,EXTERNAL))));
        for(int i=0;i<10000&&!p.done();i++) p.step(1);
        assertTrue(p.done()); return p;
    }
    @Test void dependencyOrderAndSharedNodes() {
        var p=solve(Map.of("A",List.of(c("a",GENERATE,"B","C")),"B",List.of(c("b",GENERATE,"D")),"C",List.of(c("c",GENERATE,"D")),"D",List.of(c("d",GENERATE))));
        assertEquals("",p.error()); assertEquals(List.of("D","B","C","A"),p.queue().stream().map(DependencyPlanner.Node::key).toList());
    }
    @Test void existingRootAndIntermediateStillTraverse() {
        var p=solve(Map.of("A",List.of(c("a",NETWORK,"B")),"B",List.of(c("b",BUFFER,"D")),"D",List.of(c("d",GENERATE))));
        assertEquals(List.of("D"),p.queue().stream().map(DependencyPlanner.Node::key).toList());
    }
    @Test void cycleBacktracksAndRollsBackUnusedNodes() {
        var p=solve(Map.of("A",List.of(c("bad",GENERATE,"Z","B"),c("good",GENERATE,"D")),"B",List.of(c("b",GENERATE,"A"))));
        assertEquals("",p.error()); assertFalse(p.nodes().containsKey("Z")); assertEquals("good",p.nodes().get("A").choice().id());
    }
    @Test void ingredientAlternativesAvoidCycle() {
        var alt=new DependencyPlanner.Choice<String,String>("a",GENERATE,List.of(List.of("B","D")),"a");
        var p=solve(Map.of("A",List.of(alt),"B",List.of(c("b",GENERATE,"A"))));
        assertEquals("",p.error()); assertEquals(List.of("D"),p.nodes().get("A").children());
    }
    @Test void unresolvedCycleHasPath() {
        var p=solve(Map.of("A",List.of(c("a",GENERATE,"B")),"B",List.of(c("b",GENERATE,"A"))));
        assertTrue(p.error().contains("Cycle")); assertTrue(p.error().contains("A")); assertTrue(p.queue().isEmpty());
        assertEquals(List.of("A","B","A"),p.failurePath());
        assertEquals("b",p.failureRecipe());
    }
    @Test void failureDiagnosticSurvivesAncestorRollback() {
        var p=solve(Map.of("A",List.of(c("a",GENERATE,"B")),"B",List.of(c("b",GENERATE,"C")),"C",List.of(c("c",GENERATE,"B"))));
        assertEquals(List.of("A","B","C","B"),p.failurePath());
        assertEquals("c",p.failureRecipe());
    }
    @Test void externalStopsTraversal() {
        var p=solve(Map.of("A",List.of(c("external",EXTERNAL)))); assertTrue(p.queue().isEmpty()); assertEquals(1,p.nodes().size());
    }
    @Test void depthAndNodeLimits() {
        var p=new DependencyPlanner<Integer,String>(0,k->List.of(new DependencyPlanner.Choice<>("x",GENERATE,List.of(List.of(k+1)),"x")),4,10);
        while(!p.done()) p.step(1); assertTrue(p.error().contains("Depth"));
        var q=new DependencyPlanner<String,String>("A", k->List.of(c(k,GENERATE,k.equals("A")?new String[]{"B","C"}:new String[]{})),10,2);
        while(!q.done()) q.step(1); assertTrue(q.error().contains("Node"));
    }
    @Test void emptyCandidatesFailWithoutEncoding() { var p=new DependencyPlanner<String,String>("A",k->List.of()); p.step(1); assertFalse(p.error().isEmpty()); }
    @Test void plannerWaitsForRemoteCandidateLookupAndResumes() {
        var ready=new java.util.concurrent.atomic.AtomicBoolean(false);
        var p=new DependencyPlanner<String,String>("A",k->ready.get()?List.of(c("remote",GENERATE)):null);
        p.step(4);assertFalse(p.done());assertTrue(p.nodes().isEmpty());
        ready.set(true);p.step(4);assertTrue(p.done());assertEquals("",p.error());
        assertEquals(List.of("A"),p.queue().stream().map(DependencyPlanner.Node::key).toList());
    }
    @Test void reverseCompressionCycleAutomaticallyMakesRepeatedIngredientExternal() {
        var compressed=new DependencyPlanner.Choice<String,String>("compress",GENERATE,
            Collections.nCopies(9,List.of("redstone")),"compress");
        var unpacked=c("redstone_from_block",GENERATE,"redstone_block");
        var p=new DependencyPlanner<String,String>("redstone_block",key->key.equals("redstone_block")
            ?List.of(compressed):key.equals("redstone")?List.of(unpacked):List.of(c(key,EXTERNAL)));
        for(int i=0;i<100&&!p.done();i++)p.step(1);
        assertTrue(p.done());assertEquals("",p.error());
        assertEquals(EXTERNAL,p.nodes().get("redstone").choice().kind());
        assertEquals("auto_external_cycle",p.nodes().get("redstone").choice().id());
        assertEquals(List.of("redstone_block"),p.queue().stream().map(DependencyPlanner.Node::key).toList());
    }
    @Test void compressionCycleSkipsUnrelatedIngredientCombinations() {
        var slots=new ArrayList<List<String>>();
        slots.add(List.of("ingot"));
        for(int i=1;i<9;i++)slots.add(List.of("ingot","alternative1","alternative2","alternative3"));
        var block=new DependencyPlanner.Choice<String,String>("compress",GENERATE,slots,"compress");
        var graph=Map.of("block",List.of(block),"ingot",List.of(c("unpack",GENERATE,"block")));
        var p=new DependencyPlanner<String,String>("block",k->graph.getOrDefault(k,List.of(c(k,EXTERNAL))));
        p.stepUntil(Long.MAX_VALUE,50);
        assertTrue(p.done(),"A known cycle must not enumerate 4^8 unrelated suffix combinations");
        assertEquals("",p.error());assertEquals(EXTERNAL,p.nodes().get("ingot").choice().kind());
    }
    @Test void compressionCycleFindsAlternativeAndCachesChoices() {
        var slots=new ArrayList<List<String>>();
        for(int i=0;i<9;i++)slots.add(List.of("ingot","ore"));
        var block=new DependencyPlanner.Choice<String,String>("compress",GENERATE,slots,"compress");
        var graph=Map.of("block",List.of(block),"ingot",List.of(c("unpack",GENERATE,"block"),c("from_ore",GENERATE,"ore")));
        var calls=new HashMap<String,Integer>();
        var p=new DependencyPlanner<String,String>("block",k->{calls.merge(k,1,Integer::sum);return graph.getOrDefault(k,List.of(c(k,EXTERNAL)));});
        p.stepUntil(Long.MAX_VALUE,100);
        assertTrue(p.done());assertEquals("",p.error());
        assertEquals(List.of("ingot","block"),p.queue().stream().map(DependencyPlanner.Node::key).toList());
        assertTrue(calls.values().stream().allMatch(count->count==1));
        assertEquals(Collections.nCopies(9,"ingot"),p.nodes().get("block").children());
    }
    @Test void failureInLaterSlotStillBacktracksItsEarlierAlternatives() {
        var choice=new DependencyPlanner.Choice<String,String>("a",GENERATE,List.of(List.of("X","Y"),List.of("B","D")),"a");
        var p=solve(Map.of("A",List.of(choice),"B",List.of(c("cycle",GENERATE,"A"))));
        assertEquals("",p.error());assertEquals(List.of("X","D"),p.nodes().get("A").children());
    }
    @Test void crossModTaggedSlotsDiscardAncestorLoopsBeforeEnumeratingCombinations() {
        var groups=new ArrayList<List<String>>();
        for(int i=0;i<9;i++)groups.add(List.of("alpha:alloy","beta:plate","gamma:raw"));
        var graph=Map.of(
            "alpha:alloy",List.of(c("alpha:assemble",GENERATE,"beta:plate")),
            "beta:plate",List.of(c("beta:press",GENERATE,"gamma:block")),
            "gamma:block",List.of(new DependencyPlanner.Choice<String,String>("gamma:compress",GENERATE,groups,"compress")));
        var p=new DependencyPlanner<String,String>("alpha:alloy",k->graph.getOrDefault(k,List.of(c(k,EXTERNAL))));
        p.stepUntil(Long.MAX_VALUE,100);
        assertTrue(p.done());assertEquals("",p.error());
        assertEquals(Collections.nCopies(9,"gamma:raw"),p.nodes().get("gamma:block").children());
        assertEquals(List.of("gamma:block","beta:plate","alpha:alloy"),p.queue().stream().map(DependencyPlanner.Node::key).toList());
    }
    @Test void crossModPureCycleReportsNamespacedPathWithoutInventingExternalInput() {
        var p=solve(Map.of("A",List.of(c("one:make",GENERATE,"two:part")),
            "two:part",List.of(c("two:make",GENERATE,"three:dust")),
            "three:dust",List.of(c("three:reverse",GENERATE,"A"))));
        assertTrue(p.error().contains("two:part"));assertTrue(p.error().contains("three:dust"));
        assertTrue(p.nodes().isEmpty());
    }
    @Test void pathPruningDoesNotPoisonAnotherBranchUsingTheSameMaterial() {
        var graph=Map.of("A",List.of(c("bad",GENERATE,"X","Z"),c("good",GENERATE,"B")),
            "X",List.of(c("via_B",GENERATE,"B"),c("external",EXTERNAL)),
            "B",List.of(c("via_X",GENERATE,"X")),"Z",List.of(c("cycle",GENERATE,"A")));
        var p=solve(graph);
        assertEquals("",p.error());assertEquals("good",p.nodes().get("A").choice().id());
        assertEquals(List.of("X"),p.nodes().get("B").children());
        assertFalse(p.nodes().containsKey("Z"));
    }
    @Test void manuallyLockedCycleHasNoSilentRecipeFallback() {
        var p=solve(Map.of("A",List.of(c("mod:locked",GENERATE,"mod:B")),
            "mod:B",List.of(c("mod:reverse",GENERATE,"A"))));
        assertTrue(p.error().contains("Cycle"));assertTrue(p.queue().isEmpty());
    }
}
