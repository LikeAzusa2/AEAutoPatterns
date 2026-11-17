package com.likeazusa2.aeautopatterns.integration;

import appeng.api.networking.*;
import net.minecraft.nbt.CompoundTag;

/** A topology generation counter; no scanning and no persistent grid data. */
public final class AnalysisGridService implements IGridServiceProvider,IGridService {
    private long version;
    public AnalysisGridService(IGrid grid) {}
    @Override public void addNode(IGridNode node,CompoundTag savedData){version++;}
    @Override public void removeNode(IGridNode node){version++;}
    public long version(){return version;}
}
