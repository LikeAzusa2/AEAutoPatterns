package com.likeazusa2.aeautopatterns.logic;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisSchedulerTest {
    @Test void sharedBudgetRotatesFairlyAcrossBusyTerminals() {
        var scheduler=new AnalysisScheduler();var clock=new AtomicLong();var counts=new int[5];
        for(int tick=0;tick<10;tick++) {
            long start=clock.get();
            scheduler.tick(List.of(0,1,2,3,4),4,2,clock::get,(id,deadline)->{
                assertTrue(deadline-clock.get()<=2);counts[id]++;clock.set(deadline);
            });
            assertEquals(4,clock.get()-start);
        }
        assertArrayEquals(new int[]{4,4,4,4,4},counts);
    }
    @Test void singleSlowCallStopsOtherWorkAndNextTickContinuesRotation() {
        var scheduler=new AnalysisScheduler();var clock=new AtomicLong();var visited=new ArrayList<Integer>();
        for(int i=0;i<3;i++)scheduler.tick(List.of(0,1,2),4,2,clock::get,(id,deadline)->{visited.add(id);clock.addAndGet(10);});
        assertEquals(List.of(0,1,2),visited);
        scheduler.tick(List.of(),4,2,clock::get,(id,deadline)->fail());
        scheduler.tick(List.of(8),4,2,clock::get,(id,deadline)->assertEquals(8,id));
    }
}
