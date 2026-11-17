package com.likeazusa2.aeautopatterns.logic;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.likeazusa2.aeautopatterns.logic.DependencyPlanner.Kind.*;

class MindMapLayoutTest {
    private MindMapLayout.Result<String> layout(Map<String,List<String>> graph) {
        return MindMapLayout.arrange("A",key->graph.getOrDefault(key,List.of()));
    }
    @Test void sharedMaterialExpandsIndependentlyButEncodingRemainsDeduplicated() {
        var graph=Map.of("A",List.of("B","C"),"B",List.of("D"),"C",List.of("D"),"D",List.of("E"));
        var result=layout(graph);var nodes=result.nodes();
        assertEquals(List.of("A","B","C","D","D","E","E"),nodes.stream().map(MindMapLayout.DisplayNode::key).toList());
        assertEquals(List.of(1,2),nodes.getFirst().children());
        assertEquals(1,nodes.get(3).parentId());assertEquals(2,nodes.get(4).parentId());
        assertEquals(3,nodes.get(5).parentId());assertEquals(4,nodes.get(6).parentId());
        assertNotEquals(nodes.get(3).id(),nodes.get(4).id());assertEquals(nodes.get(3).key(),nodes.get(4).key());
        var planner=new DependencyPlanner<String,String>("A",key->List.of(
            new DependencyPlanner.Choice<>(key,key.equals("E")?EXTERNAL:GENERATE,
                graph.getOrDefault(key,List.of()).stream().map(List::of).toList(),key)));
        planner.step(100);
        assertEquals("",planner.error());assertEquals(List.of("D","B","C","A"),planner.queue().stream().map(DependencyPlanner.Node::key).toList());
    }
    @Test void sameParentDuplicatesMergeAndUnreachableProblemsNeverBecomeRoots() {
        var result=layout(Map.of("A",Collections.nCopies(9,"B"),"B",List.of("C"),"problem",List.of("other")));
        assertEquals(List.of("A","B","C"),result.nodes().stream().map(MindMapLayout.DisplayNode::key).toList());
        assertEquals(1,result.nodes().stream().filter(n->n.parentId()==-1).count());
        assertEquals(0,result.nodes().getFirst().y());
    }
    @Test void unevenBranchesStaySeparatedAlignedAndCenteredOnImmediateChildren() {
        var result=layout(Map.of("A",List.of("B","C","D"),"B",List.of("E","F","G"),
            "C",List.of("H"),"H",List.of("I","J","K"),"D",List.of("L","M")));
        var nodes=result.nodes();
        for(var n:nodes) {
            assertEquals(n.id(),nodes.indexOf(n));
            if(n.parentId()>=0)assertEquals(nodes.get(n.parentId()).y()+86,n.y());
            if(!n.children().isEmpty())assertEquals(
                (nodes.get(n.children().getFirst()).x()+nodes.get(n.children().getLast()).x())/2,n.x());
        }
        var levels=new HashMap<Integer,List<Integer>>();
        for(var n:nodes)levels.computeIfAbsent(n.y(),key->new ArrayList<>()).add(n.x());
        for(var xs:levels.values()) {
            xs.sort(Integer::compareTo);
            for(int i=1;i<xs.size();i++)assertTrue(xs.get(i)-xs.get(i-1)>=128+28);
        }
        assertEquals(result,layout(Map.of("A",List.of("B","C","D"),"B",List.of("E","F","G"),
            "C",List.of("H"),"H",List.of("I","J","K"),"D",List.of("L","M"))));
    }
    @Test void sharedMaterialsAtDifferentDepthsRemainBelowEachOwnParent() {
        var result=layout(Map.of("A",List.of("D","B"),"B",List.of("C"),"C",List.of("D"),"D",List.of("E")));
        var occurrences=result.nodes().stream().filter(n->n.key().equals("D")).toList();
        assertEquals(2,occurrences.size());assertEquals(86,occurrences.get(0).y());assertEquals(258,occurrences.get(1).y());
        for(var n:occurrences)assertEquals(n.y()+86,result.nodes().get(n.children().getFirst()).y());
    }
    @Test void displayCapBoundsExponentialExpansionAndMarksOmittedBranches() {
        var result=MindMapLayout.arrange(0,key->key>=60?List.of():List.of(key+1,key+2));
        assertEquals(4096,result.nodes().size());assertTrue(result.limited());
        assertTrue(result.nodes().stream().anyMatch(n->n.stop()==MindMapLayout.Stop.LIMIT));
        for(var n:result.nodes())for(int child:n.children()) {
            assertTrue(child<4096);assertEquals(n.id(),result.nodes().get(child).parentId());
        }
    }
    @Test void cyclesStopAtTheParentWithoutCreatingUpwardEdges() {
        var result=layout(Map.of("A",List.of("B"),"B",List.of("A")));
        assertEquals(2,result.nodes().size());assertFalse(result.limited());
        assertEquals(MindMapLayout.Stop.CYCLE,result.nodes().get(1).stop());
        assertTrue(result.nodes().get(1).children().isEmpty());
    }
    @Test void streamedOccurrencesKeepCoordinatesAndParentIdentityAcrossBatches() {
        var result=MindMapLayout.arrange(0,key->key<8?List.of(key+1,key+2):List.of());
        var reveal=new TreeReveal<MindMapLayout.DisplayNode<Integer>>();reveal.reset(result.nodes());
        var received=new ArrayList<MindMapLayout.DisplayNode<Integer>>();
        for(int tick=0;tick<1000&&received.size()<result.nodes().size();tick++) {
            var batch=reveal.tick();assertTrue(batch.size()<=TreeReveal.MAX_BATCH_SIZE);
            for(var node:batch) {
                if(node.parentId()>=0)assertTrue(node.parentId()<received.size());
                assertEquals(result.nodes().get(node.id()),node);received.add(node);
            }
        }
        assertEquals(result.nodes(),received);
        var changed=MindMapLayout.arrange(0,key->List.of());reveal.reset(changed.nodes());
        assertEquals(0,reveal.position());for(int i=0;i<TreeReveal.INTERVAL_TICKS-1;i++)assertTrue(reveal.tick().isEmpty());
        assertEquals(changed.nodes(),reveal.tick());
    }
    @Test void editingOneMaterialRebuildsAllOfItsDisplayOccurrences() {
        var graph=new HashMap<>(Map.of("A",List.of("B","C"),"B",List.of("D"),"C",List.of("D"),"D",List.of("E")));
        var before=layout(graph);graph.put("D",List.of("F"));var after=layout(graph);
        assertEquals(2,before.nodes().stream().filter(n->n.key().equals("E")).count());
        assertEquals(0,after.nodes().stream().filter(n->n.key().equals("E")).count());
        assertEquals(2,after.nodes().stream().filter(n->n.key().equals("F")).count());
        for(var n:after.nodes())if(n.key().equals("D"))assertEquals("F",after.nodes().get(n.children().getFirst()).key());
    }
}
