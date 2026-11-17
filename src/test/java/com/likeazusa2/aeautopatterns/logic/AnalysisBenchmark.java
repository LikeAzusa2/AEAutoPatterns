package com.likeazusa2.aeautopatterns.logic;

import java.util.*;
import java.util.function.Function;

/** Reproducible synthetic graph benchmark. No Minecraft/adapter/JEI timing is simulated. */
public final class AnalysisBenchmark {
    private static final AnalysisSession.Limits LIMITS=new AnalysisSession.Limits(512,16384,262144,262144);
    private static DependencyPlanner.Choice<Integer,String> c(String id,List<List<Integer>> inputs) {
        return new DependencyPlanner.Choice<>(id,DependencyPlanner.Kind.GENERATE,inputs,id);
    }
    private static DependencyPlanner.Choice<Integer,String> c(String id,Integer... children) {
        return c(id,Arrays.stream(children).map(List::of).toList());
    }
    private record Result(long nanos,long attempts,long queries,long reuse,int nodes) {}
    private static Result run(Function<Integer,List<DependencyPlanner.Choice<Integer,String>>> source,boolean graph) {
        long[] queries={0};Function<Integer,List<DependencyPlanner.Choice<Integer,String>>> counted=k->{queries[0]++;return source.apply(k);};
        long start=System.nanoTime();
        if(graph) {
            var s=new AnalysisSession<>(0,counted,k->false,LIMITS);finish(s);
            if(!s.error().isEmpty())throw new AssertionError(s.error());
            return new Result(System.nanoTime()-start,s.metrics().attempts(),queries[0],s.metrics().reused(),s.nodes().size());
        }
        var s=new DependencyPlanner<>(0,counted,512,16384,262144);
        while(!s.done())s.stepUntil(System.nanoTime()+2_000_000,4096);
        if(!s.error().isEmpty())throw new AssertionError(s.error());
        return new Result(System.nanoTime()-start,s.attempts(),queries[0],s.reused(),s.nodes().size());
    }
    private static void finish(AnalysisSession<Integer,String> s) {
        int ticks=0;while(!s.done()&&ticks++<100000)s.step(System.nanoTime()+2_000_000);
        if(!s.done())throw new AssertionError("did not terminate");
    }
    private static void bench(String name,Function<Integer,List<DependencyPlanner.Choice<Integer,String>>> source) {
        for(int i=0;i<5;i++){run(source,false);run(source,true);}
        var dfs=new ArrayList<Result>();var graph=new ArrayList<Result>();
        for(int i=0;i<9;i++){dfs.add(run(source,false));graph.add(run(source,true));}
        dfs.sort(Comparator.comparingLong(Result::nanos));graph.sort(Comparator.comparingLong(Result::nanos));
        var d=dfs.get(4);var g=graph.get(4);
        System.out.printf(Locale.ROOT,"%s,%.3f,%.3f,%d,%d,%d,%d,%d%n",name,d.nanos()/1e6,g.nanos()/1e6,d.attempts(),g.attempts(),d.queries(),g.queries(),g.nodes());
    }
    public static void main(String[] args) {
        System.out.println("JVM="+System.getProperty("java.version")+"; OS="+System.getProperty("os.name")+"; processors="+Runtime.getRuntime().availableProcessors());
        System.out.println("case,dfs_ms,graph_ms,dfs_attempts,graph_attempts,dfs_queries,graph_queries,selected_nodes");
        bench("chain512",k->List.of(k==511?c("leaf"):c("next",k+1)));
        bench("shared16384",k->List.of(k==0?c("root",java.util.stream.IntStream.range(1,8192).mapToObj(List::of).toList())
            :k<8192?c("branch",8192+k):k==8192?c("shared"):c("leaf",8192)));
        var variants=java.util.stream.IntStream.range(1000,1257).boxed().toList();
        bench("tag_cycles_200x256",k->List.of(k==0?c("root",java.util.stream.IntStream.range(1,201).mapToObj(List::of).toList())
            :k<1000?c("branch",List.of(variants)):k<1256?c("cycle",k):c("exit")));
        bench("compression9x64",k->List.of(k==0?c("root",java.util.stream.IntStream.range(1,65).mapToObj(List::of).toList())
            :k<100?c("pack",Collections.nCopies(9,List.of(100+k))):c("unpack",k-100)));
        var changed=new HashSet<Integer>();
        Function<Integer,List<DependencyPlanner.Choice<Integer,String>>> tree=k->List.of(k<511?c("fork",k*2+1,k*2+2):c(changed.contains(k)?"edited":"leaf"));
        for(int warm=0;warm<5;warm++){var s=new AnalysisSession<>(0,tree,k->false,LIMITS);finish(s);finish(s.revise(700,tree));}
        var full=new ArrayList<Result>();var edits=new ArrayList<Result>();
        for(int i=0;i<9;i++) {
            var s=new AnalysisSession<>(0,tree,k->false,LIMITS);finish(s);changed.add(700);
            long[] queries={0};long start=System.nanoTime();var revised=s.revise(700,k->{queries[0]++;return tree.apply(k);});finish(revised);
            edits.add(new Result(System.nanoTime()-start,revised.metrics().attempts(),queries[0],revised.metrics().reused(),revised.nodes().size()));
            full.add(run(tree,true));changed.clear();
        }
        full.sort(Comparator.comparingLong(Result::nanos));edits.sort(Comparator.comparingLong(Result::nanos));var f=full.get(4);var e=edits.get(4);
        System.out.printf(Locale.ROOT,"edit1023_full_vs_incremental,%.3f,%.3f,%d,%d,%d,%d,%d; reused=%d%n",f.nanos()/1e6,e.nanos()/1e6,f.attempts(),e.attempts(),f.queries(),e.queries(),e.nodes(),e.reuse());
    }
}
