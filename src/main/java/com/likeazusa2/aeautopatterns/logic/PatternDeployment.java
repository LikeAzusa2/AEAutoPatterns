package com.likeazusa2.aeautopatterns.logic;

import java.nio.charset.StandardCharsets;
import java.util.*;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.parts.IPart;
import appeng.helpers.patternprovider.PatternContainer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Nameable;
import net.minecraft.world.item.ItemStack;

/** Server-side bridge to the native AE pattern-provider inventory API. */
public final class PatternDeployment {
    public record Target(int index, PatternContainer container, String name, int freeSlots, long sortOrder) {}
    public enum Result { INSERTED, DUPLICATE, NO_TARGET, FULL, OFFLINE }
    public record Selection(String id,boolean craftingOnly) {}
    private PatternDeployment() {}

    public static List<Target> targets(IGrid grid) {
        if (grid == null) return List.of();
        var found = new ArrayList<PatternContainer>();
        try {
            for (var machineClass : grid.getMachineClasses()) {
                if (!PatternContainer.class.isAssignableFrom(machineClass)) continue;
                @SuppressWarnings("unchecked") var type = (Class<? extends PatternContainer>) machineClass;
                for (var container : grid.getActiveMachines(type)) {
                    if (container == null || container.getGrid() != grid || !container.isVisibleInTerminal()) continue;
                    var inv = container.getTerminalPatternInventory();
                    if (inv == null || inv.size() == 0) continue;
                    if(!found.contains(container))found.add(container);
                }
            }
        } catch (Throwable ignored) { return List.of(); }
        found.sort(Comparator.comparingLong(PatternContainer::getTerminalSortOrder)
            .thenComparing(c -> displayName(c).getString()));
        var result = new ArrayList<Target>(found.size());
        for (int i = 0; i < found.size(); i++) {
            var c = found.get(i); result.add(new Target(i, c, displayName(c).getString(), freeSlots(c), c.getTerminalSortOrder()));
        }
        return List.copyOf(result);
    }

    public record TargetGroup(String id, String name, List<Target> members) {
        public Target representative() { return members.getFirst(); }
    }

    public static List<TargetGroup> groupTargets(List<Target> targets) {
        var groups = new ArrayList<TargetGroup>();
        var groupedByName = new HashMap<String, List<Target>>();
        for (var target : targets) {
            var name = target.name();
            if (name.isBlank() || isDefaultProviderName(target.container(), name)) {
                groups.add(new TargetGroup(identity(target.container()), target.name(), List.of(target)));
            } else {
                groupedByName.computeIfAbsent(name, key -> {
                    var members = new ArrayList<Target>();
                    var id = "named:" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
                    groups.add(new TargetGroup(id, key, members));
                    return members;
                }).add(target);
            }
        }
        return groups.stream().map(g -> new TargetGroup(g.id(), g.name(), List.copyOf(g.members()))).toList();
    }

    private static boolean isDefaultProviderName(PatternContainer container, String name) {
        var item = providerItem(container);
        // The terminal group icon can belong to an adjacent machine; use the provider's own item.
        if (!item.isEmpty() && new ItemStack(item.getItem()).getHoverName().getString().equals(name)) return true;
        return container instanceof Nameable nameable && !nameable.hasCustomName()
            && nameable.getName().getString().equals(name);
    }

    public static Result deploy(IGrid grid, int targetIndex, ItemStack pattern) {
        if (grid == null || pattern == null || pattern.isEmpty()) return Result.OFFLINE;
        var list = targets(grid);
        if (list.isEmpty()) return Result.NO_TARGET;
        if (targetIndex < 0 || targetIndex >= list.size()) return Result.NO_TARGET;
        return insert(list.get(targetIndex).container(),pattern);
    }
    public static Result deploy(IGrid grid,List<Selection> selections,boolean autoContinue,ItemStack pattern,net.minecraft.world.level.Level level) {
        if(grid==null)return Result.OFFLINE;
        if(pattern==null||pattern.isEmpty())return Result.NO_TARGET;
        boolean crafting=appeng.api.crafting.PatternDetailsHelper.decodePattern(pattern,level)
            instanceof appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
        var available=new HashMap<String,Target>();
        for(var target:targets(grid))available.put(identity(target.container()),target);
        var eligible=new ArrayList<Target>();
        for(var selection:selections) {
            var target=available.get(selection.id());
            if(target!=null&&(!selection.craftingOnly()||crafting))eligible.add(target);
        }
        if(eligible.isEmpty())return Result.NO_TARGET;
        // Check all selected providers first, including full providers, so routing
        // changes cannot duplicate a pattern already installed in this selection.
        for(var target:eligible)if(contains(target.container().getTerminalPatternInventory(),pattern))return Result.DUPLICATE;
        for(int i=0;i<eligible.size();i++) {
            var target=eligible.get(i);
            if(i>0&&(!autoContinue||!crafting||selections.stream().noneMatch(s->s.id().equals(identity(target.container()))&&s.craftingOnly())))continue;
            var result=insert(target.container(),pattern);
            if(result!=Result.FULL)return result;
            if(!autoContinue)return Result.FULL;
        }
        return Result.FULL;
    }
    private static boolean contains(InternalInventory inv,ItemStack pattern) {
        for(int i=0;i<inv.size();i++)if(ItemStack.isSameItemSameComponents(inv.getStackInSlot(i),pattern))return true;
        return false;
    }
    private static Result insert(PatternContainer container,ItemStack pattern) {
        var inv = container.getTerminalPatternInventory();
        if (inv == null) return Result.NO_TARGET;
        for (int i = 0; i < inv.size(); i++) {
            var current = inv.getStackInSlot(i);
            if (!current.isEmpty() && ItemStack.isSameItemSameComponents(current, pattern)) return Result.DUPLICATE;
        }
        if (!simulateInsert(inv, pattern)) return Result.FULL;
        var remain = pattern.copy();
        for (int i = 0; i < inv.size() && !remain.isEmpty(); i++) remain = inv.insertItem(i, remain, false);
        return remain.isEmpty() ? Result.INSERTED : Result.FULL;
    }

    public static String identity(PatternContainer container) {
        net.minecraft.world.level.block.entity.BlockEntity entity=null;
        if(container instanceof appeng.helpers.patternprovider.PatternProviderLogicHost host)entity=host.getBlockEntity();
        else if(container instanceof net.minecraft.world.level.block.entity.BlockEntity block)entity=block;
        if(entity!=null&&entity.getLevel()!=null) {
            String side="block";
            if(entity instanceof appeng.api.parts.IPartHost host)for(var direction:net.minecraft.core.Direction.values())
                if(host.getPart(direction)==container){side=direction.getName();break;}
            return entity.getLevel().dimension().location()+"/"+entity.getBlockPos().asLong()+"/"+side;
        }
        return container.getClass().getName()+"/"+container.getTerminalSortOrder();
    }
    public static ItemStack icon(PatternContainer container) {
        var item = providerItem(container);
        return item.isEmpty() ? appeng.core.definitions.AEBlocks.PATTERN_PROVIDER.stack() : item;
    }
    private static ItemStack providerItem(PatternContainer container) {
        if(container instanceof appeng.helpers.patternprovider.PatternProviderLogicHost host) {
            var key=host.getTerminalIcon();if(key!=null)return key.toStack();
        }
        if(container instanceof IPart part)return new ItemStack(part.getPartItem());
        if(container instanceof net.minecraft.world.level.block.entity.BlockEntity entity)return new ItemStack(entity.getBlockState().getBlock());
        return ItemStack.EMPTY;
    }
    public static String location(PatternContainer container) {
        if(container instanceof appeng.helpers.patternprovider.PatternProviderLogicHost host) {
            var entity=host.getBlockEntity();var pos=entity.getBlockPos();
            return entity.getLevel().dimension().location()+" · "+pos.getX()+", "+pos.getY()+", "+pos.getZ();
        }
        return identity(container);
    }

    public static int freeSlots(PatternContainer c) {
        var inv = c == null ? null : c.getTerminalPatternInventory();
        if (inv == null) return 0;
        int count = 0; for (int i = 0; i < inv.size(); i++) if (inv.getStackInSlot(i).isEmpty()) count++;
        return count;
    }

    private static boolean simulateInsert(InternalInventory inv, ItemStack pattern) {
        return inv.simulateAdd(pattern).isEmpty();
    }

    private static Component displayName(PatternContainer c) {
        try { var group = c.getTerminalGroup(); if (group != null && group.name() != null) return group.name(); } catch (Throwable ignored) {}
        return icon(c).getHoverName();
    }
}
