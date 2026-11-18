package com.likeazusa2.aeautopatterns.network;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.likeazusa2.aeautopatterns.AEAutoPatterns;
import com.likeazusa2.aeautopatterns.menu.AutoPatternMenu;
import net.minecraft.world.item.ItemStack;
public final class TerminalPackets {
    public record JeiBatch(int container,int revision,int batch,CompoundTag data,boolean response) implements CustomPacketPayload {
        public static final Type<JeiBatch> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(AEAutoPatterns.ID,"jei_batch"));
        public static final StreamCodec<RegistryFriendlyByteBuf,JeiBatch> CODEC=StreamCodec.of((b,p)->{
            b.writeVarInt(p.container);b.writeVarInt(p.revision);b.writeVarInt(p.batch);b.writeBoolean(p.response);ByteBufCodecs.COMPOUND_TAG.encode(b,p.data);
        },b->{int container=b.readVarInt(),revision=b.readVarInt(),batch=b.readVarInt();boolean response=b.readBoolean();
            var data=ByteBufCodecs.COMPOUND_TAG.decode(b);if(data.getList("entries",10).size()>32)throw new IllegalArgumentException("JEI batch limit");
            return new JeiBatch(container,revision,batch,data,response);});
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Action(int container,int revision,String action,int index,String value) implements CustomPacketPayload {
        public static final Type<Action> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(AEAutoPatterns.ID,"action"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Action> CODEC=StreamCodec.of((b,p)->{
            b.writeVarInt(p.container);b.writeVarInt(p.revision);b.writeUtf(p.action,24);b.writeVarInt(p.index);b.writeUtf(p.value,256);
        },b->new Action(b.readVarInt(),b.readVarInt(),b.readUtf(24),b.readVarInt(),b.readUtf(256)));
        @Override public Type<? extends CustomPacketPayload> type(){ return TYPE; }
    }
    public record JeiTarget(int container,int revision,CompoundTag stack) implements CustomPacketPayload {
        public static final Type<JeiTarget> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(AEAutoPatterns.ID,"jei_target"));
        public static final StreamCodec<RegistryFriendlyByteBuf,JeiTarget> CODEC=StreamCodec.composite(
            ByteBufCodecs.VAR_INT,JeiTarget::container,ByteBufCodecs.VAR_INT,JeiTarget::revision,
            ByteBufCodecs.COMPOUND_TAG,JeiTarget::stack,JeiTarget::new);
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record BulkExternal(int container,int revision,java.util.List<Integer> materialIds) implements CustomPacketPayload {
        public static final Type<BulkExternal> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(AEAutoPatterns.ID,"bulk_external"));
        public static final StreamCodec<RegistryFriendlyByteBuf,BulkExternal> CODEC=StreamCodec.of((b,p)->{
            b.writeVarInt(p.container);b.writeVarInt(p.revision);int count=Math.min(16384,p.materialIds.size());
            b.writeVarInt(count);for(int i=0;i<count;i++)b.writeVarInt(p.materialIds.get(i));
        },b->{int container=b.readVarInt(),revision=b.readVarInt(),count=b.readVarInt();
            if(count<0||count>16384)throw new IllegalArgumentException("Too many selected materials");
            var ids=new java.util.ArrayList<Integer>(count);for(int i=0;i<count;i++)ids.add(b.readVarInt());
            return new BulkExternal(container,revision,java.util.List.copyOf(ids));});
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Snapshot(int container,CompoundTag data) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(AEAutoPatterns.ID,"snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Snapshot> CODEC=StreamCodec.composite(ByteBufCodecs.VAR_INT,Snapshot::container,ByteBufCodecs.COMPOUND_TAG,Snapshot::data,Snapshot::new);
        @Override public Type<? extends CustomPacketPayload> type(){ return TYPE; }
    }
    public record IngredientBatch(int container, CompoundTag data) implements CustomPacketPayload {
        public static final Type<IngredientBatch> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AEAutoPatterns.ID, "ingredient_batch"));
        public static final StreamCodec<RegistryFriendlyByteBuf, IngredientBatch> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, IngredientBatch::container, ByteBufCodecs.COMPOUND_TAG, IngredientBatch::data, IngredientBatch::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record TreeBatch(int container,CompoundTag data) implements CustomPacketPayload {
        public static final Type<TreeBatch> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(AEAutoPatterns.ID,"tree_batch"));
        public static final StreamCodec<RegistryFriendlyByteBuf,TreeBatch> CODEC=StreamCodec.composite(ByteBufCodecs.VAR_INT,TreeBatch::container,ByteBufCodecs.COMPOUND_TAG,TreeBatch::data,TreeBatch::new);
        @Override public Type<? extends CustomPacketPayload> type(){ return TYPE; }
    }
    public record JeiQuery(int container,int revision,CompoundTag key) implements CustomPacketPayload {
        public static final Type<JeiQuery> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(AEAutoPatterns.ID,"jei_query"));
        public static final StreamCodec<RegistryFriendlyByteBuf,JeiQuery> CODEC=StreamCodec.composite(
            ByteBufCodecs.VAR_INT,JeiQuery::container,ByteBufCodecs.VAR_INT,JeiQuery::revision,
            ByteBufCodecs.COMPOUND_TAG,JeiQuery::key,JeiQuery::new);
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record JeiCandidates(int container,int revision,CompoundTag key,java.util.List<String> recipeIds) implements CustomPacketPayload {
        public static final Type<JeiCandidates> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(AEAutoPatterns.ID,"jei_candidates"));
        public static final StreamCodec<RegistryFriendlyByteBuf,JeiCandidates> CODEC=StreamCodec.of((buf,p)->{
            buf.writeVarInt(p.container);buf.writeVarInt(p.revision);ByteBufCodecs.COMPOUND_TAG.encode(buf,p.key);
            int count=Math.min(256,p.recipeIds.size());buf.writeVarInt(count);for(int i=0;i<count;i++)buf.writeUtf(p.recipeIds.get(i),256);
        },buf->{int container=buf.readVarInt(),revision=buf.readVarInt();var key=ByteBufCodecs.COMPOUND_TAG.decode(buf);
            int count=buf.readVarInt();if(count<0||count>256)throw new IllegalArgumentException("Too many JEI recipe candidates");
            var ids=new java.util.ArrayList<String>(count);for(int i=0;i<count;i++)ids.add(buf.readUtf(256));
            return new JeiCandidates(container,revision,key,java.util.List.copyOf(ids));});
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var r=event.registrar("4");
        r.playBidirectional(JeiBatch.TYPE,JeiBatch.CODEC,new net.neoforged.neoforge.network.handling.DirectionalPayloadHandler<>(
            (p,c)->c.enqueueWork(()->{if(!p.response())com.likeazusa2.aeautopatterns.client.JeiCompat.enqueue(p);}),
            (p,c)->c.enqueueWork(()->{
                if(p.response()&&c.player().containerMenu instanceof AutoPatternMenu m&&m.containerId==p.container()&&m.stillValid(c.player()))
                    m.terminal().receiveJeiBatch(p.revision(),p.batch(),p.data(),c.player().getUUID());
            })));
        r.playToServer(Action.TYPE,Action.CODEC,(p,c)->c.enqueueWork(()->{
            if(c.player().containerMenu instanceof AutoPatternMenu m && m.containerId==p.container() && m.stillValid(c.player())) m.action(p);
        }));
        r.playToServer(JeiTarget.TYPE,JeiTarget.CODEC,(p,c)->c.enqueueWork(()->{
            if(c.player().containerMenu instanceof AutoPatternMenu m && m.containerId==p.container() && m.stillValid(c.player())
                && p.revision()==m.terminal().revision()) {
                var stack=ItemStack.parseOptional(c.player().registryAccess(),p.stack());
                if(!stack.isEmpty()) {m.terminal().setCursorTarget(stack);m.sync();}
            }
        }));
        r.playToServer(BulkExternal.TYPE,BulkExternal.CODEC,(p,c)->c.enqueueWork(()->{
            if(c.player().containerMenu instanceof AutoPatternMenu m && m.containerId==p.container() && m.stillValid(c.player()))
                m.bulkExternal(p);
        }));
        r.playToServer(JeiCandidates.TYPE,JeiCandidates.CODEC,(p,c)->c.enqueueWork(()->{
            if(c.player().containerMenu instanceof AutoPatternMenu m && m.containerId==p.container() && m.stillValid(c.player()))
                m.receiveJeiCandidates(p.revision(),p.key(),p.recipeIds());
        }));
        r.playToClient(IngredientBatch.TYPE, IngredientBatch.CODEC, (packet, context) -> context.enqueueWork(() -> {
            if (context.player().containerMenu instanceof AutoPatternMenu menu && menu.containerId == packet.container()) {
                menu.receiveIngredients(packet.data());
            }
        }));
        r.playToClient(TreeBatch.TYPE,TreeBatch.CODEC,(p,c)->c.enqueueWork(()->{
            if(c.player().containerMenu instanceof AutoPatternMenu m && m.containerId==p.container())m.receiveTree(p.data());
        }));
        r.playToClient(JeiQuery.TYPE,JeiQuery.CODEC,(p,c)->c.enqueueWork(()->
            com.likeazusa2.aeautopatterns.client.JeiCompat.answer(p,c.player())
        ));
        r.playToClient(Snapshot.TYPE,Snapshot.CODEC,(p,c)->c.enqueueWork(()->{
            if(c.player().containerMenu instanceof AutoPatternMenu m && m.containerId==p.container()) m.view=p.data();
        }));
    }
}
