package com.likeazusa2.aeautopatterns.logic;

import java.util.List;

/** Fixed server-tick budget for streaming the dependency visualization. */
public final class TreeReveal<T> {
    public static final int INTERVAL_TICKS=3;
    public static final int MAX_BATCH_SIZE=128;
    private List<T> entries=List.of();
    private int ticks,position;
    public void reset(List<T> entries) {
        this.entries=List.copyOf(entries);ticks=0;position=0;
    }
    public List<T> tick() {
        if(++ticks<INTERVAL_TICKS)return List.of();
        ticks=0;
        int from=position;position=Math.min(entries.size(),position+MAX_BATCH_SIZE);
        return entries.subList(from,position);
    }
    public int position() { return position; }
    public int size() { return entries.size(); }
}
