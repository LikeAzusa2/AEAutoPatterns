package com.likeazusa2.aeautopatterns.logic;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/** Fair rotation with a shared soft deadline; an individual task call is not preemptible. */
public final class AnalysisScheduler {
    private int cursor;
    public <T> void tick(List<T> tasks,long globalNanos,long sessionNanos,LongSupplier clock,BiConsumer<T,Long> run) {
        if(tasks.isEmpty()){cursor=0;return;}
        long deadline=clock.getAsLong()+globalNanos;
        int start=Math.floorMod(cursor,tasks.size()),processed=0;
        while(processed<tasks.size()&&clock.getAsLong()<deadline) {
            var task=tasks.get((start+processed++)%tasks.size());
            run.accept(task,Math.min(deadline,clock.getAsLong()+sessionNanos));
        }
        cursor=(start+Math.max(1,processed))%tasks.size();
    }
}
