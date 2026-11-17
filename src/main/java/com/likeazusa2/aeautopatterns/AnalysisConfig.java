package com.likeazusa2.aeautopatterns;

import net.neoforged.neoforge.common.ModConfigSpec;
import com.likeazusa2.aeautopatterns.logic.AnalysisSession;

public final class AnalysisConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue DEPTH,NODES,EDGES,ATTEMPTS;
    public static final ModConfigSpec.DoubleValue GLOBAL_MS,SESSION_MS;
    static {
        var b=new ModConfigSpec.Builder();b.push("analysis");
        DEPTH=b.comment("Maximum selected dependency depth.").defineInRange("maxDepth",512,16,4096);
        NODES=b.comment("Maximum discovered candidate materials, independent of the 4096 display limit.").defineInRange("maxMaterials",16384,128,131072);
        EDGES=b.defineInRange("maxCandidateEdges",262144,1024,4194304);
        ATTEMPTS=b.defineInRange("maxSearchAttempts",262144,1024,4194304);
        GLOBAL_MS=b.comment("Soft shared server analysis budget per tick; single adapter calls cannot be interrupted.").defineInRange("globalMillisecondsPerTick",4.0,.25,20.0);
        SESSION_MS=b.defineInRange("sessionMillisecondsPerTick",2.0,.1,10.0);
        b.pop();SPEC=b.build();
    }
    public static AnalysisSession.Limits limits(){return new AnalysisSession.Limits(DEPTH.get(),NODES.get(),EDGES.get(),ATTEMPTS.get());}
    private AnalysisConfig() {}
}
