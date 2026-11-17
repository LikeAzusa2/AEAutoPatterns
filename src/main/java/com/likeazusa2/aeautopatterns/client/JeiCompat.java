package com.likeazusa2.aeautopatterns.client;

import net.minecraft.world.entity.player.Player;
import com.likeazusa2.aeautopatterns.network.TerminalPackets;

/** JEI-free client bridge so the terminal remains loadable when JEI is absent. */
public final class JeiCompat {
    private static final java.util.Deque<Batch> batches=new java.util.ArrayDeque<>();
    private static final class Batch {
        final TerminalPackets.JeiBatch query;
        final net.minecraft.nbt.ListTag results=new net.minecraft.nbt.ListTag();
        int position,characters;
        Batch(TerminalPackets.JeiBatch query){this.query=query;}
    }
    static {net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e)->tick());}
    private static volatile boolean available;
    private JeiCompat() {}
    public static boolean available() { return available; }
    public static void setAvailable(boolean value) { available=value; }
    public static void enqueue(TerminalPackets.JeiBatch query){if(batches.size()<4)batches.add(new Batch(query));}
    private static void tick() {
        var player=net.minecraft.client.Minecraft.getInstance().player;
        if(player==null){batches.clear();return;}
        long deadline=System.nanoTime()+1_000_000;
        for(int work=0;work<2&&!batches.isEmpty()&&System.nanoTime()<deadline;work++) {
            var batch=batches.peek();var query=batch.query;
            if(!(player.containerMenu instanceof com.likeazusa2.aeautopatterns.menu.AutoPatternMenu menu)||menu.containerId!=query.container()||menu.view.getInt("revision")!=query.revision()) {
                batches.remove();continue;
            }
            var entries=query.data().getList("entries",10);
            if(batch.position<entries.size()) {
                var entry=entries.getCompound(batch.position++);var result=new net.minecraft.nbt.CompoundTag();result.put("key",entry.getCompound("key"));
                var ids=new net.minecraft.nbt.ListTag();
                try {
                    var type=Class.forName("com.likeazusa2.aeautopatterns.client.JeiRecipeQueries");
                    @SuppressWarnings("unchecked") var found=(java.util.List<String>)type.getMethod("lookup",net.minecraft.nbt.CompoundTag.class,Player.class).invoke(null,entry.getCompound("key"),player);
                    for(var id:found)if(id.length()<=256&&batch.characters+id.length()<=8192){ids.add(net.minecraft.nbt.StringTag.valueOf(id));batch.characters+=id.length();}
                } catch(ReflectiveOperationException|RuntimeException ignored) {}
                result.put("ids",ids);batch.results.add(result);
            }
            if(batch.position>=entries.size()) {
                var data=new net.minecraft.nbt.CompoundTag();data.put("entries",batch.results);
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new TerminalPackets.JeiBatch(query.container(),query.revision(),query.batch(),data,true));batches.remove();
            }
        }
    }
    public static void answer(TerminalPackets.JeiQuery query,Player player) {
        try {
            Class<?> queryClass=Class.forName("com.likeazusa2.aeautopatterns.client.JeiRecipeQueries");
            queryClass.getMethod("answer",TerminalPackets.JeiQuery.class,Player.class).invoke(null,query,player);
        } catch(ReflectiveOperationException ignored) { }
    }
}
