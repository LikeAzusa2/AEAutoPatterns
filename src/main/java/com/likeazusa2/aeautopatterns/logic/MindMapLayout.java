package com.likeazusa2.aeautopatterns.logic;

import java.util.*;
import java.util.function.Function;

/** A bounded display tree: repeated materials get independent occurrences per parent. */
public final class MindMapLayout {
    public static final int MAX_NODES=4096, NODE_WIDTH=128, COLUMN_STEP=156, LEVEL_STEP=86;
    public enum Stop { NONE, LIMIT, CYCLE }
    public record DisplayNode<K>(int id,K key,int parentId,int x,int y,List<Integer> children,Stop stop) {}
    public record Result<K>(List<DisplayNode<K>> nodes,boolean limited) {}
    private static final class Entry<K> {
        final K key;
        final int parent,depth;
        final List<Integer> children=new ArrayList<>();
        int width=1,left,x;
        Stop stop=Stop.NONE;
        Entry(K key,int parent,int depth) {this.key=key;this.parent=parent;this.depth=depth;}
    }
    public static <K> Result<K> arrange(K root,Function<K,List<K>> children) {
        return arrange(root,children,MAX_NODES);
    }
    public static <K> Result<K> arrange(K root,Function<K,List<K>> children,int limit) {
        if(limit<1)throw new IllegalArgumentException("Display limit must be positive");
        if(root==null)return new Result<>(List.of(),false);
        var entries=new ArrayList<Entry<K>>();entries.add(new Entry<>(root,-1,0));
        boolean limited=false;
        // Breadth first ensures every streamed child already has a visible parent.
        for(int id=0;id<entries.size();id++) {
            var entry=entries.get(id);
            for(var key:new LinkedHashSet<>(children.apply(entry.key))) {
                boolean cycle=false;
                for(int ancestor=id;ancestor>=0;ancestor=entries.get(ancestor).parent)
                    if(Objects.equals(entries.get(ancestor).key,key)){cycle=true;break;}
                if(cycle) {if(entry.stop==Stop.NONE)entry.stop=Stop.CYCLE;continue;}
                if(entries.size()>=limit) {entry.stop=Stop.LIMIT;limited=true;continue;}
                entry.children.add(entries.size());entries.add(new Entry<>(key,id,entry.depth+1));
            }
        }
        // Reserve disjoint subtree columns before placing any parents.
        for(int id=entries.size()-1;id>=0;id--) {
            var entry=entries.get(id);
            if(!entry.children.isEmpty())entry.width=entry.children.stream().mapToInt(child->entries.get(child).width).sum();
        }
        for(var entry:entries) {
            int left=entry.left;
            for(int child:entry.children) {entries.get(child).left=left;left+=entries.get(child).width;}
        }
        // Center each parent on its first and last immediate child, with fixed row heights.
        for(int id=entries.size()-1;id>=0;id--) {
            var entry=entries.get(id);
            entry.x=entry.children.isEmpty()?entry.left*COLUMN_STEP:
                (entries.get(entry.children.getFirst()).x+entries.get(entry.children.getLast()).x)/2;
        }
        var result=new ArrayList<DisplayNode<K>>();
        for(int id=0;id<entries.size();id++) {
            var entry=entries.get(id);
            result.add(new DisplayNode<>(id,entry.key,entry.parent,entry.x,entry.depth*LEVEL_STEP,List.copyOf(entry.children),entry.stop));
        }
        return new Result<>(List.copyOf(result),limited);
    }
}
