package com.likeazusa2.aeautopatterns.menu;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.menu.AEBaseMenu;
import com.likeazusa2.aeautopatterns.integration.AdaptedRecipe;
import com.likeazusa2.aeautopatterns.integration.CraftingRecipeAdapter;
import com.likeazusa2.aeautopatterns.integration.PatternRecipe;
import com.likeazusa2.aeautopatterns.logic.AnalysisSession;
import com.likeazusa2.aeautopatterns.logic.DependencyPlanner;
import com.likeazusa2.aeautopatterns.logic.MindMapLayout;
import com.likeazusa2.aeautopatterns.logic.PatternDeployment;
import com.likeazusa2.aeautopatterns.logic.TreeReveal;
import com.likeazusa2.aeautopatterns.network.TerminalPackets;
import com.likeazusa2.aeautopatterns.part.AutoPatternTerminalPart;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component.Serializer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.network.PacketDistributor;

public final class AutoPatternMenu extends AEBaseMenu {
   public static final int OUTPUT_VISIBLE_ROWS = 3;
   private static final int OUTPUT_COLUMNS = 9;
   public static final int VISIBLE_ROWS = 4;
   public static final int PENDING_VISIBLE_ROWS = 8;
   public static final int INGREDIENT_PAGE_SIZE = 5;
   private final AutoPatternTerminalPart terminal;
   private final Player player;
   private final RecipeRouteMenu routes;
   private final Set<AEKey> collapsed = new HashSet<>();
   private int scrollOffset;
   private int outputScrollOffset;
   private int pendingOffset;
   private int selected;
   private int ticks;
   private int pendingVisibleRows = 8;
   private int rowRevision = -1;
   private int rowNodeCount = -1;
   private int rowMaterialCount = -1;
   private boolean rowsDirty = true;
   private AutoPatternTerminalPart.State rowState;
   private List<AutoPatternMenu.Row> cachedRows = List.of();
   private List<AEKey> cachedPending = List.of();
   private Set<AEKey> cachedUnresolved = Set.of();
   private Map<AEKey, Integer> cachedIds = Map.of();
   private final EnumMap<DependencyPlanner.Kind, Integer> cachedCounts = new EnumMap<>(DependencyPlanner.Kind.class);
   public CompoundTag view = new CompoundTag();
   public final List<CompoundTag> treeNodes = new ArrayList<>();
   public int treeTotal;
   public int treeRevision = -1;
   public boolean treeLimited;
   private boolean treeOpen;
   private boolean providersOpen;
   private String providerSearch = "";
   private int providerOffset;
   private AEKey pickerKey;
   private int pickerPage;
   private int pickerRevision;
   private AutoPatternMenu.IngredientPicker ingredientPicker;
   private int ingredientListId;
   private int sentIngredientCount;
   private final List<AEKey> receivedIngredients = new ArrayList<>();
   private int receivedIngredientListId = -1;
   private int sentTreeRevision = -1;
   private final TreeReveal<MindMapLayout.DisplayNode<AEKey>> treeReveal = new TreeReveal<>();
   private Map<AEKey, Integer> treeIds = Map.of();

   public void receiveIngredients(CompoundTag batch) {
      CompoundTag picker = this.view.getCompound("ingredientPicker");
      int listId = batch.getInt("listId");
      if (this.view.contains("ingredientPicker") && picker.getInt("listId") == listId) {
         if (this.receivedIngredientListId != listId) {
            this.receivedIngredientListId = listId;
            this.receivedIngredients.clear();
         }

         if (batch.getInt("offset") == this.receivedIngredients.size()) {
            for (Tag entry : batch.getList("keys", 10)) {
               this.receivedIngredients.add(AEKey.fromTagGeneric(this.player.registryAccess(), (CompoundTag)entry));
            }
         }
      }
   }

   public List<AEKey> receivedIngredients(int listId) {
      return this.receivedIngredientListId == listId ? this.receivedIngredients : List.of();
   }

   private void openIngredientPicker(AEKey key) {
      if (this.treeReady() && key != null) {
         DependencyPlanner.Node<AEKey, PatternRecipe> node = this.terminal.nodes().get(key);
         List<List<AEKey>> groups = this.terminal.ingredientOptions(key);
         if (node != null && !groups.isEmpty() && groups.size() == node.children().size()) {
            LinkedHashMap<AEKey, List<Integer>> slotsByIngredient = new LinkedHashMap<>();

            for (int slot = 0; slot < groups.size(); slot++) {
               slotsByIngredient.computeIfAbsent(node.children().get(slot), ignored -> new ArrayList<>()).add(slot);
            }

            ArrayList<AutoPatternMenu.IngredientGroup> inputs = new ArrayList<>(slotsByIngredient.size());
            slotsByIngredient.forEach((current, slots) -> {
               LinkedHashSet<AEKey> common = new LinkedHashSet<>(groups.get(slots.getFirst()));
               common.remove(current);

               for (int i = 1; i < slots.size() && !common.isEmpty(); i++) {
                  common.retainAll(new HashSet<>(groups.get(slots.get(i))));
               }

               inputs.add(new AutoPatternMenu.IngredientGroup(current, slots.size(), List.copyOf(common)));
            });
            this.ingredientPicker = new AutoPatternMenu.IngredientPicker(key, node.choice().id(), this.terminal.revision(), List.copyOf(inputs), -1);
            this.ingredientListId++;
            this.sentIngredientCount = 0;
         }
      }
   }

   private void ingredientAction(TerminalPackets.Action action) {
      AutoPatternMenu.IngredientPicker picker = this.ingredientPicker;
      if (picker != null && picker.revision() == this.terminal.revision()) {
         int index = action.index();
         if (action.action().equals("ingredient_slot")) {
            if (index >= 0 && index < picker.inputs().size() && index != picker.slot()) {
               this.ingredientPicker = picker.select(index);
               this.ingredientListId++;
               this.sentIngredientCount = 0;
            }
         } else if (picker.slot() >= 0 && action.value().equals(Integer.toString(this.ingredientListId))) {
            List<AEKey> candidates = picker.candidates();
            if (index >= 0
               && index < this.sentIngredientCount
               && index < candidates.size()
               && this.terminal.replaceIngredient(picker.key(), picker.recipeId(), picker.inputs().get(picker.slot()).current(), candidates.get(index))) {
               this.ingredientPicker = null;
            }
         }
      }
   }

   private void syncIngredientPicker(CompoundTag tag) {
      AutoPatternMenu.IngredientPicker picker = this.ingredientPicker;
      if (picker != null) {
         if (picker.revision() != this.terminal.revision()) {
            this.ingredientPicker = null;
         } else {
            CompoundTag data = new CompoundTag();
            data.putInt("slot", picker.slot());
            data.putInt("listId", this.ingredientListId);
            data.put("product", picker.key().toTagGeneric(this.player.registryAccess()));
            ListTag inputs = new ListTag();

            for (AutoPatternMenu.IngredientGroup input : picker.inputs()) {
               inputs.add(input.current().toTagGeneric(this.player.registryAccess()));
            }

            data.put("inputs", inputs);
            data.putIntArray("slotCounts", picker.inputs().stream().mapToInt(AutoPatternMenu.IngredientGroup::slotCount).toArray());
            data.putInt("candidateCount", picker.candidates().size());
            tag.put("ingredientPicker", data);
         }
      }
   }

   private void streamIngredients(ServerPlayer serverPlayer) {
      AutoPatternMenu.IngredientPicker picker = this.ingredientPicker;
      if (picker != null && picker.revision() == this.terminal.revision()) {
         List<AEKey> candidates = picker.candidates();
         if (this.sentIngredientCount < candidates.size()) {
            int end = Math.min(this.sentIngredientCount + 32, candidates.size());
            ListTag keys = new ListTag();

            for (int i = this.sentIngredientCount; i < end; i++) {
               keys.add(candidates.get(i).toTagGeneric(this.player.registryAccess()));
            }

            CompoundTag batch = new CompoundTag();
            batch.putInt("listId", this.ingredientListId);
            batch.putInt("offset", this.sentIngredientCount);
            batch.put("keys", keys);
            PacketDistributor.sendToPlayer(serverPlayer, new TerminalPackets.IngredientBatch(this.containerId, batch), new CustomPacketPayload[0]);
            this.sentIngredientCount = end;
         }
      }
   }

   private void openPicker(AEKey key) {
      if (!this.terminal.recipeOptions(key).isEmpty()) {
         this.pickerKey = key;
         this.pickerPage = 0;
         this.pickerRevision = this.terminal.revision();
      }
   }

   public AutoPatternTerminalPart terminal() {
      return this.terminal;
   }

   public void requestJei(AEKey key, int revision) {
      if (this.player instanceof ServerPlayer sp) {
         PacketDistributor.sendToPlayer(
            sp, new TerminalPackets.JeiQuery(this.containerId, revision, key.toTagGeneric(this.player.registryAccess())), new CustomPacketPayload[0]
         );
      }
   }

   public void receiveJeiCandidates(int revision, CompoundTag key, List<String> ids) {
      if (this.terminal != null && revision == this.terminal.revision()) {
         AEKey decoded = AEKey.fromTagGeneric(this.player.registryAccess(), key);
         if (decoded != null) {
            this.terminal.receiveJeiCandidates(revision, decoded, ids);
         }
      }
   }

   public void receiveTree(CompoundTag data) {
      int revision = data.getInt("revision");
      int offset = data.getInt("offset");
      if (data.getBoolean("reset")) {
         this.treeNodes.clear();
         this.treeRevision = revision;
         this.treeTotal = data.getInt("total");
         this.treeLimited = data.getBoolean("limited");
      }

      if (revision == this.treeRevision && offset == this.treeNodes.size()) {
         for (Tag entry : data.getList("nodes", 10)) {
            this.treeNodes.add((CompoundTag)entry);
         }
      }
   }

   private boolean treeReady() {
      if (!this.terminal.keys().isEmpty()) {
         switch (this.terminal.state()) {
            case IDLE:
            case INDEXING:
            case ANALYZING:
            case STALE:
               break;
            default:
               return true;
         }
      }

      return false;
   }

   public AutoPatternMenu(MenuType<AutoPatternMenu> type, int id, Inventory inv, AutoPatternTerminalPart part) {
      super(type, id, inv, part);
      this.terminal = part;
      this.player = inv.player;
      this.routes = part != null && this.player instanceof ServerPlayer server ? new RecipeRouteMenu(part, server) : null;
      ItemStackHandler storage = part == null ? new ItemStackHandler(AutoPatternTerminalPart.OUTPUT_PATTERN_SLOTS + 1) : part.inventory();
      this.addSlot(new SlotItemHandler(storage, 0, 184, 174));

      for (int row = 0; row < OUTPUT_VISIBLE_ROWS; row++) {
         for (int col = 0; col < OUTPUT_COLUMNS; col++) {
            this.addSlot(new OutputSlot(storage, row * OUTPUT_COLUMNS + col, 8 + col * 18, 162 + row * 18));
         }
      }
      this.setOutputScrollOffset(0);

      for (int row = 0; row < 3; row++) {
         for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(inv, col + row * 9 + 9, 216 + col * 18, 162 + row * 18));
         }
      }

      for (int col = 0; col < 9; col++) {
         this.addSlot(new Slot(inv, col, 216 + col * 18, 220));
      }
   }

   public int outputScrollOffset() {
      return this.outputScrollOffset;
   }

   public void setOutputScrollOffset(int offset) {
      int max = AutoPatternTerminalPart.OUTPUT_PATTERN_SLOTS / OUTPUT_COLUMNS - OUTPUT_VISIBLE_ROWS;
      this.outputScrollOffset = Math.max(0, Math.min(offset, max));
      for (int i = 0; i < OUTPUT_VISIBLE_ROWS * OUTPUT_COLUMNS; i++) {
         ((OutputSlot)this.slots.get(i + 1)).setPatternIndex(this.outputScrollOffset * OUTPUT_COLUMNS + i);
      }
   }

   private static final class OutputSlot extends Slot {
      private final ItemStackHandler handler;
      private int patternIndex;

      private OutputSlot(ItemStackHandler handler, int patternIndex, int x, int y) {
         super(new net.minecraft.world.SimpleContainer(1), 0, x, y);
         this.handler = handler;
         this.patternIndex = patternIndex + 1;
      }

      private void setPatternIndex(int index) { this.patternIndex = index + 1; }
      @Override public ItemStack getItem() { return handler.getStackInSlot(patternIndex); }
      @Override public boolean hasItem() { return !getItem().isEmpty(); }
      @Override public void set(ItemStack stack) { handler.setStackInSlot(patternIndex, stack); }
      @Override public ItemStack remove(int amount) { return handler.extractItem(patternIndex, amount, false); }
      @Override public boolean mayPlace(ItemStack stack) { return false; }
      @Override public boolean mayPickup(net.minecraft.world.entity.player.Player player) { return true; }
      @Override public void setChanged() { }
   }

   public boolean stillValid(Player p) {
      if (this.terminal != null && this.terminal.getHost() != null && this.terminal.getHost().getPart(this.terminal.getSide()) == this.terminal) {
         BlockPos pos = this.terminal.getBlockEntity().getBlockPos();
         return p.level().getBlockEntity(pos) == this.terminal.getBlockEntity() && p.distanceToSqr(pos.getCenter()) <= 64.0;
      } else {
         return false;
      }
   }

   public ItemStack quickMoveStack(Player p, int index) {
      if (index >= 0 && index < this.slots.size()) {
         Slot slot = (Slot)this.slots.get(index);
         if (!slot.hasItem()) {
            return ItemStack.EMPTY;
         } else {
            ItemStack stack = slot.getItem();
            ItemStack copy = stack.copy();
            if (index < 55) {
               if (!this.moveItemStackTo(stack, 55, this.slots.size(), true)) {
                  return ItemStack.EMPTY;
               }
            } else if (!this.moveItemStackTo(stack, 0, 1, false)) {
               return ItemStack.EMPTY;
            }

            if (stack.isEmpty()) {
               slot.setByPlayer(ItemStack.EMPTY);
            } else {
               slot.setChanged();
            }

            return copy;
         }
      } else {
         return ItemStack.EMPTY;
      }
   }

   public void action(TerminalPackets.Action a) {
      if (a.action().equals("ingredient_close")) {
         this.ingredientPicker = null;
         this.sync();
      } else if (a.action().equals("picker_close")) {
         this.pickerKey = null;
         this.sync();
      } else if (a.action().equals("tree_close")) {
         this.treeOpen = false;
      } else if (a.action().equals("providers_close")) {
         this.providersOpen = false;
         if (this.routes != null) {
            this.routes.close();
         }
      } else if (a.action().equals("jei_status")) {
         this.terminal.setJeiClient(this.player.getUUID(), Boolean.parseBoolean(a.value()));
      } else if (a.revision() != this.terminal.revision()) {
         this.pickerKey = null;
         this.sync();
      } else {
         if (this.ingredientPicker != null && this.ingredientPicker.revision() != this.terminal.revision()) {
            this.ingredientPicker = null;
         }

         if (this.ingredientPicker != null) {
            String var10 = a.action();
            switch (var10) {
               case "ingredient_slot":
               case "ingredient_choose":
                  this.ingredientAction(a);
                  this.sync();
            }
         } else if (this.providersOpen && this.routes != null && this.routes.action(a)) {
            this.sync();
         } else if (a.action().equals("picker_apply_similar")) {
            boolean applied = false;
            if (this.pickerKey != null && this.pickerRevision == this.terminal.revision()) {
               List<DependencyPlanner.Choice<AEKey, PatternRecipe>> options = this.terminal.recipeOptions(this.pickerKey);
               if (!options.isEmpty()) {
                  this.pickerPage = Math.floorMod(this.pickerPage, options.size());
                  if (a.index() == this.pickerPage) {
                     applied = this.terminal.applySimilarRecipes(this.pickerKey, options.get(this.pickerPage).id(), a.value());
                  }
               }
            }

            if (applied) {
               this.pickerKey = null;
            }

            this.sync();
         } else if (!a.action().equals("picker_page") && !a.action().equals("picker_confirm")) {
            List<AEKey> keys = this.terminal.keys();
            AEKey key = this.selected >= 0 && this.selected < keys.size() ? keys.get(this.selected) : this.terminal.target();
            String var4 = a.action();
            switch (var4) {
               case "tree_open":
                  if (this.treeReady()) {
                     this.treeOpen = true;
                     this.sentTreeRevision = -1;
                  }
                  break;
               case "target":
                  ResourceLocation id = ResourceLocation.tryParse(a.value());
                  if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                     Item item = (Item)BuiltInRegistries.ITEM.get(id);
                     if (item != Items.AIR) {
                        this.terminal.setTarget(AEItemKey.of(item));
                        this.scrollOffset = 0;
                        this.selected = 0;
                     }
                  }
                  break;
               case "cursor":
                  if (!this.getCarried().isEmpty()) {
                     this.terminal.setCursorTarget(this.getCarried());
                     this.scrollOffset = 0;
                     this.selected = 0;
                  }
                  break;
               case "ignore_components":
                  this.terminal.setIgnoreTargetComponents(Boolean.parseBoolean(a.value()));
                  break;
               case "substitute":
                  this.terminal.setSubstitution(false, Boolean.parseBoolean(a.value()));
                  break;
               case "substituteFluids":
                  this.terminal.setSubstitution(true, Boolean.parseBoolean(a.value()));
                  break;
               case "processing":
                  this.terminal.toggleProcessing();
                  break;
               case "autoUpload":
                  this.terminal.setAutoUpload(Boolean.parseBoolean(a.value()));
                  break;
               case "uploadTarget":
                  this.providersOpen = true;
                  this.providerOffset = 0;
                  break;
               case "provider_search":
                  this.providerSearch = a.value().substring(0, Math.min(80, a.value().length())).toLowerCase(Locale.ROOT);
                  this.providerOffset = 0;
                  break;
               case "provider_page":
                  this.providerOffset = Math.max(0, this.providerOffset + Integer.signum(a.index()) * 6);
                  break;
               case "provider_toggle":
                  this.terminal.configureProvider(a.value(), "toggle");
                  break;
               case "provider_single":
                  this.terminal.configureProvider(a.value(), "single");
                  break;
               case "provider_crafting":
                  this.terminal.configureProvider(a.value(), "crafting");
                  break;
               case "provider_continue":
                  this.terminal.setAutoContinueProviders(Boolean.parseBoolean(a.value()));
                  break;
               case "analyze":
                  this.terminal.analyze();
                  break;
               case "start":
                  this.terminal.startOrResume();
                  break;
               case "pause":
                  this.terminal.pause();
                  break;
               case "cancel":
                  this.terminal.cancel();
                  break;
               case "output_scroll":
                  this.setOutputScrollOffset(this.outputScrollOffset + Integer.signum(a.index()));
                  break;
               case "scroll":
                  this.scrollOffset = Math.max(0, this.scrollOffset + Integer.signum(a.index()));
                  break;
               case "scroll_to":
                  List<AutoPatternMenu.Row> rows = this.visibleRows(keys, this.terminal.nodes());
                  this.scrollOffset = Math.max(0, Math.min(a.index(), Math.max(0, rows.size() - 4)));
                  break;
               case "pending_scroll":
                  this.pendingVisibleRows = a.value().equals("compact") ? 3 : 8;
                  this.pendingOffset = Math.max(0, this.pendingOffset + Integer.signum(a.index()));
                  break;
               case "issue":
                  List<AEKey> path = this.terminal.failurePath();
                  int at = path.size() > 1 && path.subList(0, path.size() - 1).contains(path.getLast()) ? path.size() - 2 : path.size() - 1;
                  this.locateMaterial(keys, path.isEmpty() ? null : path.get(at));
                  break;
               case "locate_material":
                  if (a.index() >= 0 && a.index() < keys.size() && this.cachedUnresolved.contains(keys.get(a.index()))) {
                     this.locateMaterial(keys, keys.get(a.index()));
                  }
                  break;
               case "select":
                  if (a.index() >= 0 && a.index() < keys.size()) {
                     this.selected = a.index();
                     DependencyPlanner.Node<AEKey, PatternRecipe> n = this.terminal.nodes().get(keys.get(this.selected));
                     if (n != null && n.choice().kind() == DependencyPlanner.Kind.NEEDS_RECIPE) {
                        this.openPicker(keys.get(this.selected));
                     }
                  }
                  break;
               case "expand":
                  if (a.index() >= 0 && a.index() < keys.size()) {
                     AEKey k = keys.get(a.index());
                     if (!this.collapsed.remove(k)) {
                        this.collapsed.add(k);
                     }

                     this.rowsDirty = true;
                  }
                  break;
               case "recipe":
                  if (key != null) {
                     if (!this.terminal.recipeOptions(key).isEmpty()) {
                        this.openPicker(key);
                     } else {
                        this.terminal.nextRecipe(key);
                     }
                  }
                  break;
               case "external":
                  if (key != null) {
                     this.terminal.toggleExternal(key);
                  }
                  break;
               case "ingredient_open":
                  if (this.pickerKey == null && !this.providersOpen && this.selected == a.index()) {
                     this.openIngredientPicker(key);
                  }
                  break;
               default:
                  return;
            }

            this.sync();
         } else {
            if (this.pickerKey != null && this.pickerRevision == this.terminal.revision()) {
               List<DependencyPlanner.Choice<AEKey, PatternRecipe>> options = this.terminal.recipeOptions(this.pickerKey);
               if (!options.isEmpty()) {
                  this.pickerPage = Math.floorMod(this.pickerPage, options.size());
                  if (a.action().equals("picker_page")) {
                     this.pickerPage = Math.floorMod(this.pickerPage + Integer.signum(a.index()), options.size());
                  } else if (options.get(this.pickerPage).id().equals(a.value())) {
                     this.terminal.chooseRecipe(this.pickerKey, a.value());
                     this.pickerKey = null;
                  }
               }
            }

            this.sync();
         }
      }
   }

   public void bulkExternal(TerminalPackets.BulkExternal action) {
      if (action.revision() == this.terminal.revision() && action.materialIds().size() <= 16384) {
         List<AEKey> keys = this.terminal.keys();
         LinkedHashSet<AEKey> chosen = new LinkedHashSet<>();
         HashSet<AEKey> allowed = new HashSet<>(this.cachedPending);

         for (Integer id : action.materialIds()) {
            if (id == null || id < 0 || id >= keys.size()) {
               return;
            }

            AEKey key = keys.get(id);
            DependencyPlanner.Node<AEKey, PatternRecipe> node = this.terminal.nodes().get(key);
            boolean unresolved = this.cachedUnresolved.contains(key);
            boolean needsRecipe = node != null && node.choice().kind() == DependencyPlanner.Kind.NEEDS_RECIPE;
            if ((unresolved || needsRecipe) && allowed.contains(key)) {
               chosen.add(key);
            }
         }

         if (!chosen.isEmpty()) {
            this.terminal.setExternal(chosen);
         }

         this.sync();
      }
   }

   public void broadcastChanges() {
      super.broadcastChanges();
      if (this.player instanceof ServerPlayer serverPlayer) {
         if (this.routes != null) {
            this.routes.tick();
         }

         if (++this.ticks % 10 == 0) {
            this.sync();
         }

         this.streamTree();
         this.streamIngredients(serverPlayer);
      }
   }

   private void streamTree() {
      if (this.treeOpen && this.player instanceof ServerPlayer sp) {
         if (this.sentTreeRevision != this.terminal.revision()) {
            this.sentTreeRevision = this.terminal.revision();
            HashMap<AEKey, Integer> ids = new HashMap<>();
            List<AEKey> keys = this.terminal.keys();

            for (int i = 0; i < keys.size(); i++) {
               ids.put(keys.get(i), i);
            }

            this.treeIds = ids;
            MindMapLayout.Result<AEKey> layout = MindMapLayout.arrange(this.treeReady() ? this.terminal.target() : null, keyx -> {
               DependencyPlanner.Node<AEKey, PatternRecipe> nodex = this.terminal.nodes().get(keyx);
               return nodex == null ? List.of() : nodex.children();
            });
            this.treeReveal.reset(layout.nodes());
            CompoundTag reset = new CompoundTag();
            reset.putBoolean("reset", true);
            reset.putInt("revision", this.sentTreeRevision);
            reset.putInt("offset", 0);
            reset.putInt("total", layout.nodes().size());
            reset.putBoolean("limited", layout.limited());
            reset.put("nodes", new ListTag());
            PacketDistributor.sendToPlayer(sp, new TerminalPackets.TreeBatch(this.containerId, reset), new CustomPacketPayload[0]);
         }

         if (this.treeReady()) {
            if (this.treeReveal.size() == 0) {
               this.sentTreeRevision = -1;
            } else {
               List<MindMapLayout.DisplayNode<AEKey>> batch = this.treeReveal.tick();
               if (!batch.isEmpty()) {
                  ListTag nodes = new ListTag();

                  for (MindMapLayout.DisplayNode<AEKey> row : batch) {
                     CompoundTag entry = new CompoundTag();
                     AEKey key = row.key();
                     DependencyPlanner.Node<AEKey, PatternRecipe> node = this.terminal.nodes().get(key);
                     entry.putInt("id", row.id());
                     entry.putInt("materialId", this.treeIds.getOrDefault(key, -1));
                     entry.putInt("parentId", row.parentId());
                     entry.putString("stop", row.stop().name());
                     entry.putInt("x", row.x());
                     entry.putInt("y", row.y());
                     entry.putString("name", key.getDisplayName().getString());
                     entry.putString("kind", this.terminal.isGenerated(key) ? "GENERATED" : (node == null ? "PENDING" : node.choice().kind().name()));
                     entry.putIntArray("children", row.children().stream().mapToInt(Integer::intValue).toArray());
                     if (key instanceof AEItemKey item) {
                        entry.put("icon", item.toStack().save(this.player.registryAccess()));
                     }

                     nodes.add(entry);
                  }

                  CompoundTag tag = new CompoundTag();
                  tag.putInt("revision", this.sentTreeRevision);
                  tag.putInt("offset", this.treeReveal.position() - batch.size());
                  tag.put("nodes", nodes);
                  PacketDistributor.sendToPlayer(sp, new TerminalPackets.TreeBatch(this.containerId, tag), new CustomPacketPayload[0]);
               }
            }
         }
      }
   }

   private List<AutoPatternMenu.Row> visibleRows(List<AEKey> keys, Map<AEKey, DependencyPlanner.Node<AEKey, PatternRecipe>> nodes) {
      if (!this.rowsDirty
         && this.rowRevision == this.terminal.revision()
         && this.rowState == this.terminal.state()
         && this.rowNodeCount == nodes.size()
         && this.rowMaterialCount == keys.size()) {
         return this.cachedRows;
      } else {
         this.rowRevision = this.terminal.revision();
         this.rowState = this.terminal.state();
         this.rowNodeCount = nodes.size();
         this.rowMaterialCount = keys.size();
         this.rowsDirty = false;
         ArrayList<AutoPatternMenu.Row> rows = new ArrayList<>();
         this.flatten(this.terminal.target(), 0, new HashSet<>(), rows);
         HashMap<AEKey, Integer> ids = new HashMap<>();

         for (int i = 0; i < keys.size(); i++) {
            ids.put(keys.get(i), i);
         }

         this.cachedIds = ids;
         if (this.rowState == AutoPatternTerminalPart.State.ERROR) {
            HashSet<AEKey> present = new HashSet<>();
            rows.forEach(row -> present.add(row.key()));

            for (AEKey k : keys) {
               if (present.add(k)) {
                  rows.add(new AutoPatternMenu.Row(k, 0));
               }
            }
         }

         this.cachedCounts.clear();
         nodes.values().forEach(n -> this.cachedCounts.merge(n.choice().kind(), Integer.valueOf(1), Integer::sum));
         this.cachedPending = keys.stream().filter(kx -> {
            DependencyPlanner.Node<AEKey, PatternRecipe> n = nodes.get(kx);
            return n != null && n.choice().kind() == DependencyPlanner.Kind.NEEDS_RECIPE;
         }).toList();
         LinkedHashSet<AEKey> unresolved = new LinkedHashSet<>();
         List<AEKey> failurePath = this.terminal.failurePath();
         if (this.rowState == AutoPatternTerminalPart.State.ERROR && !failurePath.isEmpty()) {
            int at = failurePath.size() > 1 && failurePath.subList(0, failurePath.size() - 1).contains(failurePath.getLast())
               ? failurePath.size() - 2
               : failurePath.size() - 1;
            AEKey failed = failurePath.get(at);
            if (ids.containsKey(failed)) {
               unresolved.add(failed);
            }
         }

         this.cachedUnresolved = Set.copyOf(unresolved);
         LinkedHashSet<AEKey> combined = new LinkedHashSet<>(this.cachedPending);
         combined.addAll(unresolved);
         this.cachedPending = List.copyOf(combined);
         return this.cachedRows = List.copyOf(rows);
      }
   }

   private void flatten(AEKey key, int depth, Set<AEKey> seen, List<AutoPatternMenu.Row> rows) {
      if (key != null) {
         ArrayDeque<AutoPatternMenu.Row> stack = new ArrayDeque<>();
         stack.push(new AutoPatternMenu.Row(key, depth));

         while (!stack.isEmpty()) {
            AutoPatternMenu.Row row = stack.pop();
            if (seen.add(row.key())) {
               rows.add(row);
               if (!this.collapsed.contains(row.key())) {
                  DependencyPlanner.Node<AEKey, PatternRecipe> n = this.terminal.nodes().get(row.key());
                  if (n != null) {
                     for (int i = n.children().size() - 1; i >= 0; i--) {
                        stack.push(new AutoPatternMenu.Row(n.children().get(i), row.depth() + 1));
                     }
                  }
               }
            }
         }
      }
   }

   public void sync() {
      if (this.player instanceof ServerPlayer sp) {
         CompoundTag tag = new CompoundTag();
         tag.putInt("revision", this.terminal.revision());
         tag.putString("state", this.terminal.state().name());
         this.syncIngredientPicker(tag);
         if (this.pickerKey != null && this.pickerRevision != this.terminal.revision()) {
            this.pickerKey = null;
         }

         if (this.pickerKey != null) {
            List<DependencyPlanner.Choice<AEKey, PatternRecipe>> options = this.terminal.recipeOptions(this.pickerKey);
            if (!options.isEmpty()) {
               this.pickerPage = Math.floorMod(this.pickerPage, options.size());
               DependencyPlanner.Choice<AEKey, PatternRecipe> choice = options.get(this.pickerPage);
               CompoundTag preview = new CompoundTag();
               preview.putString("name", this.pickerKey.getDisplayName().getString());
               preview.putInt("page", this.pickerPage + 1);
               preview.putInt("count", options.size());
               preview.putString("id", choice.id());
               ProcessingRecipeView processing = ProcessingRecipeView.of(choice.payload(), this.player.registryAccess(), List.of());
               if (processing != null) {
                  preview.put("processing", processing.describe(this.player.registryAccess()));
                  preview.putString("destination", this.routes == null ? "" : this.routes.destination(processing.id()));
               } else if (choice.payload().recipe() != null && choice.payload().adapter() instanceof CraftingRecipeAdapter crafting) {
                  Recipe<?> recipe = choice.payload().recipe().value();
                  List<Ingredient> ingredients = crafting.ingredients(recipe);
                  int width = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : 3;
                  ListTag slots = new ListTag();
                  int ordinal = 0;

                  for (int i = 0; i < ingredients.size(); i++) {
                     if (!ingredients.get(i).isEmpty()) {
                        CompoundTag slot = new CompoundTag();
                        slot.putInt("slot", i / width * 3 + i % width);
                        ListTag variants = new ListTag();

                        for (AEKey candidate : choice.ingredients().get(ordinal++)) {
                           if (candidate instanceof AEItemKey item) {
                              variants.add(item.toStack().save(this.player.registryAccess()));
                           }
                        }

                        slot.put("variants", variants);
                        slots.add(slot);
                     }
                  }

                  preview.put("slots", slots);
                  ItemStack output = recipe.getResultItem(this.player.registryAccess());
                  if (this.pickerKey instanceof AEItemKey item) {
                     ItemStack exact = item.toStack();
                     exact.setCount(Math.max(1, output.getCount()));
                     preview.put("output", exact.save(this.player.registryAccess()));
                  }
               }

               AutoPatternTerminalPart.SimilarRecipeBatch similar = this.terminal.similarRecipeBatch(this.pickerKey, choice.id());
               if (similar != null) {
                  preview.putInt("similarCount", similar.count());
                  preview.putString("similarToken", similar.token());
                  ListTag similarTargets = new ListTag();

                  for (int ix = 0; ix < Math.min(8, similar.targets().size()); ix++) {
                     AutoPatternTerminalPart.SimilarRecipeTarget target = similar.targets().get(ix);
                     CompoundTag row = new CompoundTag();
                     row.putString("name", target.key().getDisplayName().getString());
                     row.putString("nameComponent", Serializer.toJson(target.key().getDisplayName(), this.player.registryAccess()));
                     row.putString("group", this.routes == null ? "" : this.routes.destination(target.recipeId()));
                     similarTargets.add(row);
                  }

                  preview.put("similarTargets", similarTargets);
               }

               tag.put("recipePreview", preview);
            }
         }

         tag.putBoolean("substitute", this.terminal.substitute());
         tag.putBoolean("substituteFluids", this.terminal.substituteFluids());
         tag.putBoolean("ignoreTargetComponents", this.terminal.ignoreTargetComponents());
         tag.putBoolean("treeReady", this.treeReady());
         tag.putBoolean("allowProcessing", this.terminal.allowProcessing());
         tag.putBoolean("autoUpload", this.terminal.autoUpload());
         tag.putInt("uploadTarget", this.terminal.uploadTarget());
         tag.putString("uploadStatus", this.terminal.uploadStatus());
         List<PatternDeployment.Target> uploadTargets = this.terminal.uploadTargets();
         if (this.providersOpen && this.routes != null) {
            this.routes.snapshot(tag, uploadTargets);
         }

         tag.putInt("uploadTargetCount", uploadTargets.size());
         tag.putBoolean("autoContinueProviders", this.terminal.autoContinueProviders());
         List<PatternDeployment.Selection> selections = this.terminal.providerSelections(uploadTargets);
         HashMap<String, Integer> selectionOrders = new HashMap<>();

         for (int ix = 0; ix < selections.size(); ix++) {
            selectionOrders.put(selections.get(ix).id(), ix + 1);
         }

         tag.putInt("selectedProviderCount", selections.size());
         if (this.providersOpen) {
            List<PatternDeployment.TargetGroup> filtered = PatternDeployment.groupTargets(uploadTargets)
               .stream()
               .filter(
                  group -> this.providerSearch.isEmpty()
                        || group.name().toLowerCase(Locale.ROOT).contains(this.providerSearch)
                        || group.members()
                           .stream()
                           .anyMatch(
                              t -> (PatternDeployment.location(t.container())
                                       + " "
                                       + BuiltInRegistries.ITEM.getKey(PatternDeployment.icon(t.container()).getItem()))
                                    .toLowerCase(Locale.ROOT)
                                    .contains(this.providerSearch)
                           )
               )
               .toList();
            this.providerOffset = Math.min(this.providerOffset, Math.max(0, (filtered.size() - 1) / 6 * 6));
            tag.putInt("providerOffset", this.providerOffset);
            tag.putInt("providerCount", filtered.size());
            ListTag providers = new ListTag();

            for (int ix = this.providerOffset; ix < Math.min(filtered.size(), this.providerOffset + 6); ix++) {
               PatternDeployment.TargetGroup group = filtered.get(ix);
               CompoundTag entry = new CompoundTag();
               PatternContainer container = group.representative().container();
               int selectedCount = 0;
               int craftingCount = 0;
               int order = Integer.MAX_VALUE;
               int free = 0;
               int slots = 0;

               for (PatternDeployment.Target target : group.members()) {
                  String memberId = PatternDeployment.identity(target.container());
                  Integer memberOrder = selectionOrders.get(memberId);
                  if (memberOrder != null) {
                     selectedCount++;
                     order = Math.min(order, memberOrder);
                  }

                  if (this.terminal.providerCraftingOnly(memberId)) {
                     craftingCount++;
                  }

                  free += target.freeSlots();
                  slots += target.container().getTerminalPatternInventory().size();
               }

               int count = group.members().size();
               entry.putString("id", group.id());
               entry.putString("name", group.name());
               entry.putInt("count", count);
               entry.putInt("free", free);
               entry.putInt("slots", slots);
               entry.putString("location", PatternDeployment.location(container));
               entry.putBoolean("selected", selectedCount == count);
               entry.putBoolean("selectedMixed", selectedCount > 0 && selectedCount < count);
               entry.putBoolean("craftingOnly", craftingCount == count);
               entry.putBoolean("craftingMixed", craftingCount > 0 && craftingCount < count);
               entry.putInt("order", selectedCount == 0 ? 0 : order);
               entry.put("icon", PatternDeployment.icon(container).save(this.player.registryAccess()));
               providers.add(entry);
            }

            tag.put("providers", providers);
         }

         List<PatternDeployment.Target> selectedTargets = uploadTargets.stream()
            .filter(t -> selectionOrders.containsKey(PatternDeployment.identity(t.container())))
            .toList();
         tag.putInt("selectedOnlineProviders", selectedTargets.size());
         tag.putInt("uploadFreeSlots", selectedTargets.stream().mapToInt(PatternDeployment.Target::freeSlots).sum());
         tag.putString("adapterCapabilities", this.terminal.adapterCapabilities().stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
         tag.putInt("adapterDeclarations", this.terminal.adapterDeclarations().size());
         tag.putString("error", this.terminal.error());
         tag.putBoolean("online", this.terminal.online());
         tag.putInt("done", this.terminal.completed());
         tag.putInt("total", this.terminal.total());
         AnalysisSession.Metrics metrics = this.terminal.analysisMetrics();
         tag.putInt("analysisJeiPending", this.terminal.pendingJeiCount());
         tag.putString("analysisAdapterDiagnostics", this.terminal.adapterDiagnostics());
         if (metrics != null) {
            tag.putString("analysisPhase", metrics.phase());
            tag.putInt("analysisMaterials", metrics.materials());
            tag.putLong("analysisEdges", metrics.edges());
            tag.putInt("analysisComponents", metrics.components());
            tag.putLong("analysisAttempts", metrics.attempts());
            tag.putLong("analysisCacheHits", metrics.cacheHits());
            tag.putLong("analysisReused", metrics.reused());
            tag.putInt("analysisMillis", (int)(metrics.nanos() / 1000000L));
         }

         if (this.terminal.state() == AutoPatternTerminalPart.State.ERROR && !this.terminal.failurePath().isEmpty()) {
            List<AEKey> path = this.terminal.failurePath();
            AEKey failed = path.get(path.size() > 1 && path.subList(0, path.size() - 1).contains(path.getLast()) ? path.size() - 2 : path.size() - 1);
            tag.putString("issueMaterial", failed.getDisplayName().getString());
            tag.putString("issuePath", path.stream().map(k -> k.getDisplayName().getString()).collect(Collectors.joining(" → ")));
            tag.putString("issueRecipe", this.terminal.failureRecipe());
         }

         if (this.terminal.target() != null) {
            tag.putString("target", this.terminal.target().getDisplayName().getString());
         }

         List<AEKey> keys = this.terminal.keys();
         Map<AEKey, DependencyPlanner.Node<AEKey, PatternRecipe>> nodes = this.terminal.nodes();
         List<AutoPatternMenu.Row> rows = this.visibleRows(keys, nodes);
         Map<AEKey, Integer> keyIds = this.cachedIds;
         String failurePathText = this.terminal
            .failurePath()
            .stream()
            .map(material -> material.getDisplayName().getString())
            .collect(Collectors.joining(" → "));
         this.pendingOffset = Math.min(this.pendingOffset, Math.max(0, this.cachedPending.size() - this.pendingVisibleRows));
         tag.putInt("pendingCount", this.cachedPending.size());
         tag.putInt("pendingOffset", this.pendingOffset);
         ListTag pendingRows = new ListTag();
         int[] pendingIds = new int[this.cachedPending.size()];

         for (int ix = 0; ix < this.cachedPending.size(); ix++) {
            pendingIds[ix] = keyIds.getOrDefault(this.cachedPending.get(ix), -1);
         }

         tag.putIntArray("pendingIds", pendingIds);

         for (int ix = this.pendingOffset; ix < Math.min(this.cachedPending.size(), this.pendingOffset + this.pendingVisibleRows); ix++) {
            AEKey key = this.cachedPending.get(ix);
            CompoundTag entry = new CompoundTag();
            entry.putInt("id", keyIds.getOrDefault(key, -1));
            entry.putString("name", key.getDisplayName().getString());
            entry.putBoolean("selected", keyIds.getOrDefault(key, -1) == this.selected);
            entry.putBoolean("unresolved", this.cachedUnresolved.contains(key));
            entry.putBoolean(
               "needsRecipe",
               !this.cachedUnresolved.contains(key) && nodes.get(key) != null && nodes.get(key).choice().kind() == DependencyPlanner.Kind.NEEDS_RECIPE
            );
            if (this.cachedUnresolved.contains(key)) {
               entry.putString("path", failurePathText);
               entry.putString("recipe", this.terminal.failureRecipe());
            }

            if (key instanceof AEItemKey item) {
               entry.put("icon", item.toStack().save(this.player.registryAccess()));
            }

            pendingRows.add(entry);
         }

         tag.put("pendingRows", pendingRows);
         this.scrollOffset = Math.min(this.scrollOffset, Math.max(0, rows.size() - 4));
         tag.putInt("scrollOffset", this.scrollOffset);
         tag.putInt("rowCount", rows.size());
         ListTag list = new ListTag();

         for (int ix = this.scrollOffset; ix < Math.min(rows.size(), this.scrollOffset + 4); ix++) {
            AutoPatternMenu.Row row = rows.get(ix);
            CompoundTag r = new CompoundTag();
            DependencyPlanner.Node<AEKey, PatternRecipe> n = nodes.get(row.key());
            r.putInt("id", keyIds.getOrDefault(row.key(), -1));
            r.putInt("depth", row.depth());
            r.putString("name", row.key().getDisplayName().getString());
            r.putString("kind", this.terminal.isGenerated(row.key()) ? "GENERATED" : (n == null ? "PENDING" : n.choice().kind().name()));
            r.putBoolean("children", n != null && !n.children().isEmpty());
            r.putBoolean("collapsed", this.collapsed.contains(row.key()));
            r.putBoolean("selected", keyIds.getOrDefault(row.key(), -1) == this.selected);
            if (row.key() instanceof AEItemKey item) {
               r.put("icon", item.toStack().save(this.player.registryAccess()));
            }

            list.add(r);
         }

         tag.put("rows", list);

         for (DependencyPlanner.Kind kind : DependencyPlanner.Kind.values()) {
            tag.putInt(kind.name(), this.cachedCounts.getOrDefault(kind, Integer.valueOf(0)));
         }

         if (!keys.isEmpty()) {
            this.selected = Math.min(this.selected, keys.size() - 1);
            AEKey keyx = keys.get(this.selected);
            DependencyPlanner.Node<AEKey, PatternRecipe> n = nodes.get(keyx);
            tag.putInt("selectedId", this.selected);
            tag.putString("selectedKind", this.terminal.isGenerated(keyx) ? "GENERATED" : (n == null ? "PENDING" : n.choice().kind().name()));
            tag.putString("selected", keyx.getDisplayName().getString());
            tag.putLong("stored", this.terminal.stored(keyx));
            tag.putString(
               "recipe",
               n == null ? "" : (n.choice().id().startsWith("NETWORK:") ? "NETWORK" : (n.choice().id().startsWith("BUFFER:") ? "BUFFER" : n.choice().id()))
            );
            tag.putInt("recipes", this.terminal.options(keyx).size());
            if (n != null && n.choice().payload() != null && n.choice().payload().adapted() != null) {
               AdaptedRecipe adapted = n.choice().payload().adapted();
               tag.putString("adapterId", adapted.adapterId());
               tag.putString("recipeId", adapted.recipeId().toString());
               tag.putString("machineType", adapted.requirements().machineType().toString());
               tag.putString("conditions", String.join("; ", adapted.requirements().conditions()));
               tag.putInt("probabilisticOutputs", adapted.probabilisticOutputs().size());
               tag.putBoolean("hasRecipeWarnings", adapted.hasWarnings());
            }

            if (n != null && !n.children().isEmpty()) {
               tag.putInt("ingredients", n.children().size());
               tag.putBoolean("canEditIngredients", this.treeReady() && n.choice().kind() == DependencyPlanner.Kind.GENERATE);
               if (this.treeReady()) {
                  Set<Integer> fluidSlots = this.terminal.fluidInputs(keyx);
                  tag.putBoolean("inputFluid", !fluidSlots.isEmpty());
                  int[] fluidIds = fluidSlots.stream()
                     .map(ix -> keyIds.getOrDefault(n.children().get(ix), -1))
                     .mapToInt(Integer::intValue)
                     .distinct()
                     .toArray();
                  tag.putIntArray("fluidMaterials", fluidIds);
               }
            }
         }

         PacketDistributor.sendToPlayer(sp, new TerminalPackets.Snapshot(this.containerId, tag), new CustomPacketPayload[0]);
      }
   }

   private void locateMaterial(List<AEKey> keys, AEKey key) {
      if (key != null) {
         int id = keys.indexOf(key);
         if (id >= 0) {
            this.selected = id;
            this.collapsed.clear();
            this.rowsDirty = true;
            ArrayList<AutoPatternMenu.Row> visible = new ArrayList<>();
            this.flatten(this.terminal.target(), 0, new HashSet<>(), visible);
            HashSet<AEKey> present = new HashSet<>();
            visible.forEach(row -> present.add(row.key()));

            for (AEKey material : keys) {
               if (present.add(material)) {
                  visible.add(new AutoPatternMenu.Row(material, 0));
               }
            }

            for (int i = 0; i < visible.size(); i++) {
               if (visible.get(i).key().equals(key)) {
                  this.scrollOffset = i;
                  break;
               }
            }
         }
      }
   }

   private static record IngredientGroup(AEKey current, int slotCount, List<AEKey> alternatives) {
   }

   private static record IngredientPicker(AEKey key, String recipeId, int revision, List<AutoPatternMenu.IngredientGroup> inputs, int slot) {
      AutoPatternMenu.IngredientPicker select(int slot) {
         return new AutoPatternMenu.IngredientPicker(this.key, this.recipeId, this.revision, this.inputs, slot);
      }

      List<AEKey> candidates() {
         return this.slot < 0 ? List.of() : this.inputs.get(this.slot).alternatives();
      }
   }

   private static record Row(AEKey key, int depth) {
   }
}
