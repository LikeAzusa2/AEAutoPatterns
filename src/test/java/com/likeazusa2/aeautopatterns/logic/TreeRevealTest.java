package com.likeazusa2.aeautopatterns.logic;

import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TreeRevealTest {
    @Test void sendsAtMost128NodesEveryThreeTicksAndPreservesOrder() {
        var reveal=new TreeReveal<Integer>();
        var source=IntStream.range(0,4096).boxed().toList();reveal.reset(source);
        var received=new ArrayList<Integer>();var counts=new ArrayDeque<Integer>();
        for(int tick=1;tick<=4200;tick++) {
            var batch=reveal.tick();received.addAll(batch);counts.addLast(batch.size());
            if(counts.size()>TreeReveal.INTERVAL_TICKS)counts.removeFirst();
            assertTrue(batch.size()<=TreeReveal.MAX_BATCH_SIZE);
            assertTrue(counts.stream().mapToInt(Integer::intValue).sum()<=TreeReveal.MAX_BATCH_SIZE);
            if(tick<TreeReveal.INTERVAL_TICKS)assertTrue(batch.isEmpty());
        }
        assertEquals(source,received);assertEquals(4096,reveal.position());
    }
    @Test void newPlanDiscardsPendingNodesAndRestartsTheBudget() {
        var reveal=new TreeReveal<Integer>();
        reveal.reset(IntStream.range(0,41).boxed().toList());
        for(int i=0;i<TreeReveal.INTERVAL_TICKS;i++)reveal.tick();
        assertEquals(41,reveal.position());
        reveal.reset(List.of(100,101));
        assertEquals(0,reveal.position());assertEquals(2,reveal.size());
        for(int i=0;i<TreeReveal.INTERVAL_TICKS-1;i++)assertTrue(reveal.tick().isEmpty());
        assertEquals(List.of(100,101),reveal.tick());
        for(int i=0;i<40;i++)assertTrue(reveal.tick().isEmpty());
    }
}
