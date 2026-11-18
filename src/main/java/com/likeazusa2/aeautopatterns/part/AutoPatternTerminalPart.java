package com.likeazusa2.aeautopatterns.part;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IManagedGridNode;
import appeng.api.parts.IPartItem;
import appeng.api.parts.IPartModel;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.core.definitions.AEItems;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.items.parts.PartModels;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import appeng.parts.PartModel;
import appeng.parts.reporting.AbstractTerminalPart;
import com.likeazusa2.aeautopatterns.AEAutoPatterns;
import com.likeazusa2.aeautopatterns.AnalysisConfig;
import com.likeazusa2.aeautopatterns.ModContent;
import com.likeazusa2.aeautopatterns.integration.AdaptedRecipe;
import com.likeazusa2.aeautopatterns.integration.AdapterCapability;
import com.likeazusa2.aeautopatterns.integration.AdapterRegistry;
import com.likeazusa2.aeautopatterns.integration.AnalysisGridService;
import com.likeazusa2.aeautopatterns.integration.CompositeRecipeAdapter;
import com.likeazusa2.aeautopatterns.integration.CraftingRecipeAdapter;
import com.likeazusa2.aeautopatterns.integration.DatapackAdapterSpec;
import com.likeazusa2.aeautopatterns.integration.PatternRecipe;
import com.likeazusa2.aeautopatterns.integration.RecipeAdapter;
import com.likeazusa2.aeautopatterns.integration.RecipeKind;
import com.likeazusa2.aeautopatterns.integration.RecipeSnapshot;
import com.likeazusa2.aeautopatterns.logic.AnalysisScheduler;
import com.likeazusa2.aeautopatterns.logic.AnalysisSession;
import com.likeazusa2.aeautopatterns.logic.DependencyPlanner;
import com.likeazusa2.aeautopatterns.logic.PatternDeployment;
import com.likeazusa2.aeautopatterns.logic.PatternLookup;
import com.likeazusa2.aeautopatterns.logic.RecipeRouteGroups;
import com.likeazusa2.aeautopatterns.menu.AutoPatternMenu;
import com.likeazusa2.aeautopatterns.network.TerminalPackets;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.NonNullList;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent.Post;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.LoggerFactory;

public final class AutoPatternTerminalPart extends AbstractTerminalPart {
   public static final int OUTPUT_PATTERN_SLOTS = 54;
   /** User-facing processing pattern generation is temporarily disabled. */
   public static final boolean PROCESSING_GENERATION_AVAILABLE = false;
   private static final Set<AutoPatternTerminalPart> ACTIVE = Collections.newSetFromMap(new IdentityHashMap<>());
   @PartModels
   public static final ResourceLocation MODEL_OFF = ResourceLocation.fromNamespaceAndPath("aeautopatterns", "part/auto_pattern_terminal_off");
   @PartModels
   public static final ResourceLocation MODEL_ON = ResourceLocation.fromNamespaceAndPath("aeautopatterns", "part/auto_pattern_terminal_on");
   @PartModels
   public static final ResourceLocation MODEL_BACKGROUND = ResourceLocation.fromNamespaceAndPath("aeautopatterns", "part/auto_pattern_terminal_background");
   @PartModels
   public static final ResourceLocation MODEL_BACKGROUND_ON = ResourceLocation.fromNamespaceAndPath(
      "aeautopatterns", "part/auto_pattern_terminal_background_on"
   );
   public static final IPartModel MODELS_OFF = new PartModel(new ResourceLocation[]{MODEL_BASE, MODEL_BACKGROUND, MODEL_OFF, MODEL_STATUS_OFF});
   public static final IPartModel MODELS_ON = new PartModel(new ResourceLocation[]{MODEL_BASE, MODEL_BACKGROUND_ON, MODEL_ON, MODEL_STATUS_ON});
   public static final IPartModel MODELS_HAS_CHANNEL = new PartModel(
      new ResourceLocation[]{MODEL_BASE, MODEL_BACKGROUND_ON, MODEL_ON, MODEL_STATUS_HAS_CHANNEL}
   );
   private Level level;
   private boolean allowProcessing;
   private boolean ignoreTargetComponents;
   private AEKey cursorTarget;
   private boolean autoUpload;
   private int uploadTarget;
   private boolean providerSelectionConfigured;
   private boolean autoContinueProviders;
   private final LinkedHashSet<String> selectedProviders = new LinkedHashSet<>();
   private final Set<String> craftingOnlyProviders = new HashSet<>();
   private final RecipeRouteGroups routeGroups = new RecipeRouteGroups();
   private String uploadStatus = "";
   private boolean substitute;
   private boolean substituteFluids = true;
   private final Map<AEKey, Set<Integer>> fluidPreview = new HashMap<>();
   private final ItemStackHandler inventory = new ItemStackHandler(OUTPUT_PATTERN_SLOTS + 1) {
      protected void onContentsChanged(int slot) {
         if (AutoPatternTerminalPart.this.lookup != null) {
            AutoPatternTerminalPart.this.lookup.invalidate();
         }

         if (slot > 0) {
            AutoPatternTerminalPart.this.bufferVersion++;
         }

         AutoPatternTerminalPart.this.markForSave();
      }

      public boolean isItemValid(int slot, ItemStack stack) {
         return slot == 0 && AEItems.BLANK_PATTERN.is(stack);
      }
   };
   private AEKey target;
   private final Set<AEKey> external = new HashSet<>();
   private final Map<AEKey, String> recipeChoices = new HashMap<>();
   private final Map<AEKey, Map<Integer, AEKey>> ingredientChoices = new HashMap<>();
   private final Map<AEKey, Long> pendingJei = new HashMap<>();
   private final LinkedHashSet<AEKey> queuedJei = new LinkedHashSet<>();
   private final Map<Integer, AutoPatternTerminalPart.JeiFlight> jeiFlights = new LinkedHashMap<>();
   private int nextJeiBatch;
   private boolean jeiTimedOut;
   private final Map<AEKey, List<String>> jeiRecipeIds = new HashMap<>();
   private final Set<UUID> jeiClients = new HashSet<>();
   private final LinkedHashMap<AEKey, List<DependencyPlanner.Choice<AEKey, PatternRecipe>>> discovered = new LinkedHashMap<>();
   private RecipeAdapter adapter;
   private RecipeAdapter.Settings adapterSettings;
   private int adapterReloadEpoch = -1;
   private List<DatapackAdapterSpec> adapterDeclarations = List.of();
   private PatternLookup lookup;
   private AnalysisSession<AEKey, PatternRecipe> planner;
   private List<DependencyPlanner.Node<AEKey, PatternRecipe>> queue = List.of();
   private final Set<AEKey> generated = new HashSet<>();
   private AutoPatternTerminalPart.State state = AutoPatternTerminalPart.State.IDLE;
   private String error = "";
   private int cursor;
   private int revision;
   private int completed;
   private long topology;
   private long bufferVersion;
   private long analysisBufferVersion;
   private static final AnalysisScheduler SCHEDULER = new AnalysisScheduler();
   private Iterator<DependencyPlanner.Node<AEKey, PatternRecipe>> validation = Collections.emptyIterator();
   private Iterator<DependencyPlanner.Node<AEKey, PatternRecipe>> backgroundValidation = Collections.emptyIterator();
   private Iterator<DependencyPlanner.Node<AEKey, PatternRecipe>> capacityValidation = Collections.emptyIterator();
   private final List<ItemStack> simulatedOutput = new ArrayList<>();
   private int simulatedFree;
   private long validationBufferVersion;
   private AutoPatternTerminalPart.State afterValidation = AutoPatternTerminalPart.State.READY;
   private long analysisStarted;
   private long indexNanos;
   private final Map<AEKey, String> orderingKeys = new HashMap<>();
   private final Map<AEKey, Integer> materialRanks = new HashMap<>();
   private List<AEKey> keySnapshot = List.of();
   private int keyRevision = -1;
   private IGrid analyzedGrid;
   private int reloadEpoch;
   private long serverTick;
   private long planEpoch;
   private long approvedPlanEpoch = -1L;
   private int approvedAdapterEpoch = -1;

   public AutoPatternTerminalPart(IPartItem<?> partItem) {
      super(partItem);
   }

   public ItemStackHandler inventory() {
      return this.inventory;
   }

   public IManagedGridNode node() {
      return this.getMainNode();
   }

   private void markForSave() {
      if (this.getHost() != null) {
         this.getHost().markForSave();
      }
   }

   private void setChanged() {
      this.markForSave();
   }

   private static void serverTick(Post event) {
      synchronized (ACTIVE) {
         List<AutoPatternTerminalPart> parts = ACTIVE.stream().filter(p -> p.level != null && p.level.getServer() == event.getServer()).toList();

         for (AutoPatternTerminalPart part : parts) {
            part.serverTick++;
         }

         SCHEDULER.tick(
            parts,
            (long)((Double)AnalysisConfig.GLOBAL_MS.get() * 1000000.0),
            (long)((Double)AnalysisConfig.SESSION_MS.get() * 1000000.0),
            System::nanoTime,
            AutoPatternTerminalPart::tick
         );
      }
   }

   public void addToWorld() {
      super.addToWorld();
      this.level = this.getLevel();
      if (this.level != null && !this.level.isClientSide) {
         synchronized (ACTIVE) {
            ACTIVE.add(this);
         }
      }
   }

   public void removeFromWorld() {
      synchronized (ACTIVE) {
         ACTIVE.remove(this);
      }

      super.removeFromWorld();
   }

   protected void onMainNodeStateChanged(appeng.api.networking.IGridNodeListener.State reason) {
      super.onMainNodeStateChanged(reason);
      if (this.analyzedGrid != null && this.node().getGrid() != this.analyzedGrid) {
         this.invalidatePlan();
      }
   }

   public void addAdditionalDrops(List<ItemStack> drops, boolean wrenched) {
      super.addAdditionalDrops(drops, wrenched);

      for (int i = 0; i < this.inventory.getSlots(); i++) {
         ItemStack stack = this.inventory.getStackInSlot(i);
         if (!stack.isEmpty()) {
            drops.add(stack.copy());
         }
      }
   }

   public void clearContent() {
      super.clearContent();

      for (int i = 0; i < this.inventory.getSlots(); i++) {
         this.inventory.setStackInSlot(i, ItemStack.EMPTY);
      }
   }

   public void readFromNBT(CompoundTag tag, Provider registries) {
      super.readFromNBT(tag, registries);
      this.node().loadFromNBT(tag);
      this.inventory.deserializeNBT(registries, tag.getCompound("inventory"));
      this.allowProcessing = tag.getBoolean("allowProcessing");
      this.ignoreTargetComponents = tag.getBoolean("ignoreTargetComponents");
      this.cursorTarget = tag.contains("cursorTarget") ? AEKey.fromTagGeneric(registries, tag.getCompound("cursorTarget")) : null;
      this.substitute = tag.getBoolean("substitute");
      this.substituteFluids = !tag.contains("substituteFluids") || tag.getBoolean("substituteFluids");
      this.fluidPreview.clear();
      this.target = tag.contains("target") ? AEKey.fromTagGeneric(registries, tag.getCompound("target")) : null;
      this.external.clear();
      this.recipeChoices.clear();
      this.ingredientChoices.clear();

      for (Tag entry : tag.getList("rules", 10)) {
         CompoundTag r = (CompoundTag)entry;
         AEKey key = AEKey.fromTagGeneric(registries, r.getCompound("key"));
         if (key != null) {
            if (r.getBoolean("external")) {
               this.external.add(key);
            }

            if (r.contains("recipe")) {
               this.recipeChoices.put(key, r.getString("recipe"));
            }

            HashMap<Integer, AEKey> inputs = new HashMap<>();

            for (Tag in : r.getList("inputs", 10)) {
               CompoundTag input = (CompoundTag)in;
               AEKey value = AEKey.fromTagGeneric(registries, input.getCompound("key"));
               if (value != null) {
                  inputs.put(input.getInt("index"), value);
               }
            }

            this.ingredientChoices.put(key, inputs);
         }
      }

      this.completed = tag.getInt("completed");
      this.cursor = tag.getInt("cursor");
      this.state = this.target == null ? AutoPatternTerminalPart.State.IDLE : AutoPatternTerminalPart.State.STALE;
      this.autoUpload = tag.getBoolean("autoUpload");
      this.uploadTarget = Math.max(0, tag.getInt("uploadTarget"));
      this.uploadStatus = "";
      this.providerSelectionConfigured = tag.getBoolean("providerSelectionConfigured");
      this.autoContinueProviders = tag.getBoolean("autoContinueProviders");
      this.selectedProviders.clear();
      this.craftingOnlyProviders.clear();

      for (Tag entryx : tag.getList("providerRules", 10)) {
         CompoundTag rule = (CompoundTag)entryx;
         String id = rule.getString("id");
         if (rule.getBoolean("selected")) {
            this.selectedProviders.add(id);
         }

         if (rule.getBoolean("craftingOnly")) {
            this.craftingOnlyProviders.add(id);
         }
      }

      this.routeGroups.readNbt(tag.getCompound("recipeRouteGroups"));
   }

   public void writeToNBT(CompoundTag tag, Provider registries) {
      super.writeToNBT(tag, registries);
      this.node().saveToNBT(tag);
      tag.put("inventory", this.inventory.serializeNBT(registries));
      tag.putBoolean("allowProcessing", this.allowProcessing);
      tag.putBoolean("autoUpload", this.autoUpload);
      tag.putBoolean("ignoreTargetComponents", this.ignoreTargetComponents);
      if (this.cursorTarget != null) {
         tag.put("cursorTarget", this.cursorTarget.toTagGeneric(registries));
      }

      tag.putInt("uploadTarget", this.uploadTarget);
      tag.putBoolean("substitute", this.substitute);
      tag.putBoolean("substituteFluids", this.substituteFluids);
      tag.putBoolean("providerSelectionConfigured", this.providerSelectionConfigured);
      tag.putBoolean("autoContinueProviders", this.autoContinueProviders);
      ListTag providerRules = new ListTag();
      LinkedHashSet<String> providerIds = new LinkedHashSet<>(this.selectedProviders);
      providerIds.addAll(this.craftingOnlyProviders);

      for (String id : providerIds) {
         CompoundTag rule = new CompoundTag();
         rule.putString("id", id);
         rule.putBoolean("selected", this.selectedProviders.contains(id));
         rule.putBoolean("craftingOnly", this.craftingOnlyProviders.contains(id));
         providerRules.add(rule);
      }

      tag.put("providerRules", providerRules);
      tag.put("recipeRouteGroups", this.routeGroups.writeNbt());
      if (this.target != null) {
         tag.put("target", this.target.toTagGeneric(registries));
      }

      ListTag rules = new ListTag();
      HashSet<AEKey> keys = new HashSet<>(this.external);
      keys.addAll(this.recipeChoices.keySet());
      keys.addAll(this.ingredientChoices.keySet());

      for (AEKey key : keys) {
         CompoundTag r = new CompoundTag();
         r.put("key", key.toTagGeneric(registries));
         r.putBoolean("external", this.external.contains(key));
         if (this.recipeChoices.containsKey(key)) {
            r.putString("recipe", this.recipeChoices.get(key));
         }

         ListTag inputs = new ListTag();
         this.ingredientChoices.getOrDefault(key, Map.of()).forEach((i, k) -> {
            CompoundTag in = new CompoundTag();
            in.putInt("index", i);
            in.put("key", k.toTagGeneric(registries));
            inputs.add(in);
         });
         r.put("inputs", inputs);
         rules.add(r);
      }

      tag.put("rules", rules);
      tag.putInt("completed", this.completed);
      tag.putInt("cursor", this.cursor);
      tag.putString("state", this.state.name());
   }

   public boolean onUseWithoutItem(Player player, Vec3 pos) {
      if (!super.onUseWithoutItem(player, pos) && !this.isClientSide()) {
         MenuOpener.open(ModContent.TERMINAL_MENU.get(), player, MenuLocators.forPart(this));
      }

      return true;
   }

   public MenuType<?> getMenuType(Player player) {
      return ModContent.TERMINAL_MENU.get();
   }

   public IPartModel getStaticModels() {
      return this.selectModel(MODELS_OFF, MODELS_ON, MODELS_HAS_CHANNEL);
   }

   public int revision() {
      return this.revision;
   }

   public List<AEKey> keys() {
      if (this.keyRevision != this.revision || this.keySnapshot.size() != this.discovered.size()) {
         this.keySnapshot = List.copyOf(this.discovered.keySet());
         this.keyRevision = this.revision;
      }

      return this.keySnapshot;
   }

   public AEKey target() {
      return this.target;
   }

   public Map<AEKey, DependencyPlanner.Node<AEKey, PatternRecipe>> nodes() {
      return this.planner == null ? Map.of() : this.planner.nodes();
   }

   public List<DependencyPlanner.Choice<AEKey, PatternRecipe>> options(AEKey key) {
      return this.discovered.getOrDefault(key, List.of());
   }

   public List<DependencyPlanner.Choice<AEKey, PatternRecipe>> recipeOptions(AEKey key) {
      return this.rankRecipeOptions(this.supportedRecipeOptions(this.discovered.getOrDefault(key, List.of())));
   }

   public List<DependencyPlanner.Choice<AEKey, PatternRecipe>> nativeOptions(AEKey key) {
      return this.recipeOptions(key)
         .stream()
         .filter(choice -> this.processingRecipeId(choice.payload()) == null && choice.payload().adapter() instanceof CraftingRecipeAdapter)
         .toList();
   }

   public AutoPatternTerminalPart.State state() {
      return this.state;
   }

   public boolean isGenerated(AEKey key) {
      return this.generated.contains(key);
   }

   public String error() {
      return this.error;
   }

   public List<AEKey> failurePath() {
      return this.planner == null ? List.of() : this.planner.failurePath();
   }

   public String failureRecipe() {
      return this.planner == null ? "" : this.planner.failureRecipe();
   }

   public int completed() {
      return this.completed;
   }

   public int total() {
      return this.queue.size();
   }

   public boolean online() {
      return this.node().isActive();
   }

   public long stored(AEKey key) {
      IGrid g = this.node().getGrid();
      return g == null ? 0L : g.getStorageService().getCachedInventory().get(key);
   }

   public boolean substitute() {
      return this.substitute;
   }

   public boolean substituteFluids() {
      return this.substituteFluids;
   }

   public void setSubstitution(boolean fluids, boolean enabled) {
      if ((fluids ? this.substituteFluids : this.substitute) != enabled) {
         if (fluids) {
            this.substituteFluids = enabled;
         } else {
            this.substitute = enabled;
         }

         this.setChanged();
         if (this.target != null) {
            this.analyze();
         } else {
            this.revision++;
         }
      }
   }

   public Set<Integer> fluidInputs(AEKey key) {
      return !this.substituteFluids ? Set.of() : this.fluidPreview.computeIfAbsent(key, k -> {
         DependencyPlanner.Node<AEKey, PatternRecipe> n = this.nodes().get(k);
         if (n == null) {
            return Set.of();
         } else {
            PatternRecipe payload = n.choice().payload();
            if (payload != null && payload.adapter() instanceof CraftingRecipeAdapter && payload.recipe().value() instanceof CraftingRecipe) {
               ItemStack encoded = payload.adapter().encode(payload, n.children(), k, this.level);
               IPatternDetails decoded = PatternDetailsHelper.decodePattern(encoded, this.level);
               HashSet<Integer> slots = new HashSet<>();
               if (decoded instanceof AECraftingPattern crafting) {
                  NonNullList<Ingredient> ingredients = payload.recipe().value().getIngredients();
                  int width = payload.recipe().value() instanceof ShapedRecipe shaped ? shaped.getWidth() : 3;
                  int ordinal = 0;

                  for (int i = 0; i < ingredients.size(); i++) {
                     if (!((Ingredient)ingredients.get(i)).isEmpty()) {
                        if (crafting.getValidFluid(i / width * 3 + i % width) != null) {
                           slots.add(ordinal);
                        }

                        ordinal++;
                     }
                  }
               }

               return Set.copyOf(slots);
            } else {
               return Set.of();
            }
         }
      });
   }

   public boolean allowProcessing() {
      return this.allowProcessing;
   }

   public boolean autoUpload() {
      return this.autoUpload;
   }

   public int uploadTarget() {
      return this.uploadTarget;
   }

   public String uploadStatus() {
      return this.uploadStatus;
   }

   public List<PatternDeployment.Target> uploadTargets() {
      return PatternDeployment.targets(this.node().getGrid());
   }

   public List<PatternDeployment.Selection> providerSelections() {
      return this.providerSelections(this.providerSelectionConfigured ? List.of() : this.uploadTargets());
   }

   public List<PatternDeployment.Selection> providerSelections(List<PatternDeployment.Target> targets) {
      return !this.providerSelectionConfigured && this.uploadTarget >= 0 && this.uploadTarget < targets.size()
         ? List.of(new PatternDeployment.Selection(PatternDeployment.identity(targets.get(this.uploadTarget).container()), false))
         : this.selectedProviders.stream().map(id -> new PatternDeployment.Selection(id, this.craftingOnlyProviders.contains(id))).toList();
   }

   public boolean providerCraftingOnly(String id) {
      return this.craftingOnlyProviders.contains(id);
   }

   public boolean autoContinueProviders() {
      return this.autoContinueProviders;
   }

   public void setAutoContinueProviders(boolean enabled) {
      this.autoContinueProviders = enabled;
      this.setChanged();
   }

   public void configureProvider(String id, String operation) {
      List<PatternDeployment.Target> targets = this.uploadTargets();
      Optional<PatternDeployment.TargetGroup> group = PatternDeployment.groupTargets(targets).stream().filter(g -> g.id().equals(id)).findFirst();
      if (!group.isEmpty()) {
         List<String> ids = group.get().members().stream().map(t -> PatternDeployment.identity(t.container())).toList();
         if (!this.providerSelectionConfigured) {
            this.providerSelections(targets).forEach(s -> this.selectedProviders.add(s.id()));
            this.providerSelectionConfigured = true;
         }

         switch (operation) {
            case "toggle":
               if (this.selectedProviders.containsAll(ids)) {
                  this.selectedProviders.removeAll(ids);
               } else {
                  this.selectedProviders.addAll(ids);
               }
               break;
            case "single":
               this.selectedProviders.retainAll(ids);
               this.selectedProviders.addAll(ids);
               break;
            case "crafting":
               if (this.craftingOnlyProviders.containsAll(ids)) {
                  this.craftingOnlyProviders.removeAll(ids);
               } else {
                  this.craftingOnlyProviders.addAll(ids);
               }
               break;
            default:
               return;
         }

         this.uploadStatus = "";
         this.setChanged();
      }
   }

   public List<RecipeRouteGroups.GroupView> recipeRouteGroups() {
      return this.routeGroups.groups();
   }

   public String createRecipeRouteGroup(String name) {
      String id = this.routeGroups.create(name);
      if (id != null) {
         this.routeGroupChanged();
      }

      return id;
   }

   public boolean renameRecipeRouteGroup(String groupId, String name) {
      if (!this.routeGroups.rename(groupId, name)) {
         return false;
      } else {
         this.routeGroupChanged();
         return true;
      }
   }

   public boolean deleteRecipeRouteGroup(String groupId) {
      if (!this.routeGroups.delete(groupId)) {
         return false;
      } else {
         this.routeGroupChanged();
         return true;
      }
   }

   public boolean addRecipesToRouteGroup(String groupId, Collection<ResourceLocation> recipeIds) {
      if (!this.routeGroups.addRecipes(groupId, recipeIds)) {
         return false;
      } else {
         this.routeGroupChanged();
         return true;
      }
   }

   public boolean removeRecipesFromRouteGroup(String groupId, Collection<ResourceLocation> recipeIds) {
      if (!this.routeGroups.removeRecipes(groupId, recipeIds)) {
         return false;
      } else {
         this.routeGroupChanged();
         return true;
      }
   }

   public boolean moveRecipesToRouteGroup(String groupId, Collection<ResourceLocation> recipeIds) {
      if (!this.routeGroups.moveRecipes(groupId, recipeIds)) {
         return false;
      } else {
         this.routeGroupChanged();
         return true;
      }
   }

   public boolean toggleRouteGroupProvider(String groupId, String providerId) {
      List<String> members = this.expandProviderSelection(providerId);
      if (!members.isEmpty() && this.routeGroups.toggleProviders(groupId, members)) {
         this.routeGroupChanged();
         return true;
      } else {
         return false;
      }
   }

   public List<PatternDeployment.Target> eligibleRouteGroupProviders(String groupId) {
      return this.routeGroups.group(groupId).map(this::eligibleProviders).orElse(List.of());
   }

   public RecipeRouteGroups.RouteStatus routeStatus() {
      boolean configured = this.routeGroups.routingConfigured();
      if (!configured) {
         return new RecipeRouteGroups.RouteStatus(
            false, this.autoUpload, null, "", "", RecipeRouteGroups.RouteState.LEGACY, "Legacy ungrouped routing", List.of()
         );
      } else {
         DependencyPlanner.Node<AEKey, PatternRecipe> node = this.currentRoutingNode();
         PatternRecipe payload = node == null ? null : node.choice().payload();
         ResourceLocation recipeId = this.processingRecipeId(payload);
         if (!this.autoUpload) {
            return new RecipeRouteGroups.RouteStatus(
               true, false, recipeId, "", "", RecipeRouteGroups.RouteState.BUFFERED, "Auto upload is disabled; patterns go to the terminal buffer", List.of()
            );
         } else if (recipeId == null) {
            return new RecipeRouteGroups.RouteStatus(
               true, true, null, "", "", RecipeRouteGroups.RouteState.NOT_PROCESSING, "Current plan item is not a processing recipe", List.of()
            );
         } else {
            Optional<RecipeRouteGroups.GroupView> group = this.routeGroups.groupForRecipe(recipeId);
            if (group.isEmpty()) {
               return new RecipeRouteGroups.RouteStatus(
                  true, true, recipeId, "", "", RecipeRouteGroups.RouteState.UNASSIGNED, "Processing recipe has no route group", List.of()
               );
            } else {
               RecipeRouteGroups.GroupView view = group.get();
               List<PatternDeployment.Target> eligible = this.eligibleProviders(view);
               RecipeRouteGroups.RouteState state = eligible.isEmpty() ? RecipeRouteGroups.RouteState.NO_PROVIDERS : RecipeRouteGroups.RouteState.READY;
               String message = eligible.isEmpty()
                  ? "Route group has no active eligible provider"
                  : "Route group has " + eligible.size() + " active provider(s)";
               return new RecipeRouteGroups.RouteStatus(
                  true,
                  true,
                  recipeId,
                  view.groupId(),
                  view.name(),
                  state,
                  message,
                  eligible.stream().map(target -> PatternDeployment.identity(target.container())).toList()
               );
            }
         }
      }
   }

   public List<PatternRecipe> processingCatalog() {
      if (this.adapter != null && this.adapterReloadEpoch == AEAutoPatterns.reloadEpoch) {
         List<PatternRecipe> catalog = this.adapter.processingRecipes();
         return catalog == null ? List.of() : List.copyOf(catalog);
      } else {
         return List.of();
      }
   }

   public List<PatternRecipe> plannedProcessingRecipes() {
      if (!this.queue.isEmpty() && this.cursor >= 0 && this.cursor < this.queue.size()) {
         ArrayList<PatternRecipe> result = new ArrayList<>();

         for (DependencyPlanner.Node<AEKey, PatternRecipe> entry : this.queue.subList(this.cursor, this.queue.size())) {
            if (entry.choice().kind() == DependencyPlanner.Kind.GENERATE) {
               PatternRecipe payload = entry.choice().payload();
               ResourceLocation recipeId = this.processingRecipeId(payload);
               if (recipeId != null) {
                  result.add(payload);
               }
            }
         }

         return List.copyOf(result);
      } else {
         return List.of();
      }
   }

   private void routeGroupChanged() {
      this.uploadStatus = "";
      this.revision++;
      this.setChanged();
   }

   private List<String> expandProviderSelection(String requestedId) {
      if (requestedId == null) {
         return List.of();
      } else {
         List<PatternDeployment.Target> targets = this.uploadTargets();

         for (PatternDeployment.Target target : targets) {
            if (PatternDeployment.identity(target.container()).equals(requestedId)) {
               return List.of(requestedId);
            }
         }

         for (PatternDeployment.TargetGroup group : PatternDeployment.groupTargets(targets)) {
            if (group.id().equals(requestedId)) {
               return group.members().stream().map(targetx -> PatternDeployment.identity(targetx.container())).toList();
            }
         }

         return List.of();
      }
   }

   private List<PatternDeployment.Target> eligibleProviders(RecipeRouteGroups.GroupView group) {
      HashMap<String, PatternDeployment.Target> byId = new HashMap<>();

      for (PatternDeployment.Target target : this.uploadTargets()) {
         byId.put(PatternDeployment.identity(target.container()), target);
      }

      ArrayList<PatternDeployment.Target> result = new ArrayList<>();

      for (String id : group.providerIds()) {
         PatternDeployment.Target target = byId.get(id);
         if (target != null && !this.providerCraftingOnly(id)) {
            result.add(target);
         }
      }

      return List.copyOf(result);
   }

   private DependencyPlanner.Node<AEKey, PatternRecipe> currentRoutingNode() {
      if (this.cursor >= 0 && this.cursor < this.queue.size()) {
         return this.queue.get(this.cursor);
      } else {
         return this.target != null && this.planner != null ? this.planner.nodes().get(this.target) : null;
      }
   }

   private ResourceLocation processingRecipeId(PatternRecipe payload) {
      if (payload != null && payload.adapter() != null) {
         ResourceLocation recipeId = this.payloadRecipeId(payload);
         if (recipeId == null) {
            return null;
         } else {
            AdaptedRecipe adapted = payload.adapted();
            boolean processing = adapted != null
               ? adapted.kind() == RecipeKind.PROCESSING || adapted.kind() == RecipeKind.MACHINE
               : payload.recipe() != null && payload.recipe().value() instanceof AbstractCookingRecipe;
            return processing ? recipeId : null;
         }
      } else {
         return null;
      }
   }

   private ResourceLocation payloadRecipeId(PatternRecipe payload) {
      if (payload == null) {
         return null;
      } else if (payload.adapted() != null) {
         return payload.adapted().recipeId();
      } else {
         return payload.recipe() == null ? null : payload.recipe().id();
      }
   }

   private boolean recipeOptionMatches(DependencyPlanner.Choice<AEKey, PatternRecipe> choice, String id) {
      if (choice.id().equals(id)) {
         return true;
      } else {
         ResourceLocation recipeId = this.payloadRecipeId(choice.payload());
         return recipeId != null && recipeId.toString().equals(id);
      }
   }

   private List<DependencyPlanner.Choice<AEKey, PatternRecipe>> supportedRecipeOptions(List<DependencyPlanner.Choice<AEKey, PatternRecipe>> options) {
      LinkedHashMap<String, DependencyPlanner.Choice<AEKey, PatternRecipe>> unique = new LinkedHashMap<>();

      for (DependencyPlanner.Choice<AEKey, PatternRecipe> choice : options) {
         if (choice.kind() == DependencyPlanner.Kind.GENERATE && choice.payload() != null && choice.payload().adapter() != null) {
            Set<AdapterCapability> capabilities = choice.payload().adapter().capabilities();
            if (capabilities.contains(AdapterCapability.VALIDATION)
               && (this.processingRecipeId(choice.payload()) == null || capabilities.contains(AdapterCapability.AE_PROCESSING_ENCODING))) {
               unique.putIfAbsent(choice.id(), choice);
            }
         }
      }

      return List.copyOf(unique.values());
   }

   private List<DependencyPlanner.Choice<AEKey, PatternRecipe>> rankRecipeOptions(List<DependencyPlanner.Choice<AEKey, PatternRecipe>> options) {
      if (options.size() >= 2 && this.routeGroups.routingConfigured() && this.autoUpload) {
         HashSet<String> activeProviders = new HashSet<>();

         for (PatternDeployment.Target target : this.uploadTargets()) {
            String id = PatternDeployment.identity(target.container());
            if (!this.providerCraftingOnly(id)) {
               activeProviders.add(id);
            }
         }

         ArrayList<DependencyPlanner.Choice<AEKey, PatternRecipe>> ranked = new ArrayList<>(options);
         ranked.sort(Comparator.comparingInt(choice -> this.routeOptionRank((DependencyPlanner.Choice<AEKey, PatternRecipe>)choice, activeProviders)));
         return List.copyOf(ranked);
      } else {
         return options;
      }
   }

   private int routeOptionRank(DependencyPlanner.Choice<AEKey, PatternRecipe> choice, Set<String> activeProviders) {
      ResourceLocation recipeId = this.processingRecipeId(choice.payload());
      if (recipeId == null) {
         return 0;
      } else {
         Optional<RecipeRouteGroups.GroupView> group = this.routeGroups.groupForRecipe(recipeId);
         if (group.isEmpty()) {
            return 2;
         } else {
            return group.get().providerIds().stream().anyMatch(activeProviders::contains) ? 0 : 1;
         }
      }
   }

   public void setAutoUpload(boolean enabled) {
      this.autoUpload = enabled;
      this.uploadStatus = "";
      this.setChanged();
   }

   public void cycleUploadTarget() {
      List<PatternDeployment.Target> list = this.uploadTargets();
      if (list.isEmpty()) {
         this.uploadTarget = 0;
         this.uploadStatus = "No visible pattern providers";
      } else {
         this.uploadTarget = (this.uploadTarget + 1) % list.size();
      }

      this.setChanged();
   }

   public Set<AdapterCapability> adapterCapabilities() {
      return this.adapter == null ? Set.of() : this.adapter.capabilities();
   }

   public List<DatapackAdapterSpec> adapterDeclarations() {
      return this.adapterDeclarations;
   }

   public void toggleProcessing() {
      this.allowProcessing = !this.allowProcessing;
      if (!this.allowProcessing) {
         for (Entry<AEKey, String> entry : new HashMap<>(this.recipeChoices).entrySet()) {
            ResourceLocation id = ResourceLocation.tryParse(entry.getValue());
            if (id != null && this.level.getRecipeManager().byKey(id).map(r -> r.value() instanceof AbstractCookingRecipe).orElse(false)) {
               this.recipeChoices.remove(entry.getKey());
               this.ingredientChoices.remove(entry.getKey());
            }
         }
      }

      this.setChanged();
      if (this.target != null) {
         this.analyze();
      } else {
         this.revision++;
      }
   }

   public void setTarget(AEKey key) {
      this.cursorTarget = null;
      this.target = key;
      this.external.clear();
      this.recipeChoices.clear();
      this.ingredientChoices.clear();
      this.cancel();
   }

   public boolean ignoreTargetComponents() {
      return this.ignoreTargetComponents;
   }

   public void setCursorTarget(ItemStack stack) {
      if (!stack.isEmpty()) {
         AEItemKey original = AEItemKey.of(stack);
         this.setTarget(this.ignoreTargetComponents ? AEItemKey.of(stack.getItem().getDefaultInstance()) : original);
         this.cursorTarget = original;
         this.setChanged();
      }
   }

   public void setIgnoreTargetComponents(boolean enabled) {
      if (this.ignoreTargetComponents != enabled) {
         this.ignoreTargetComponents = enabled;
         if (this.cursorTarget instanceof AEItemKey item) {
            AEKey original = this.cursorTarget;
            AEKey next = (AEKey)(enabled ? AEItemKey.of(item.getItem().getDefaultInstance()) : original);
            if (!Objects.equals(this.target, next)) {
               this.setTarget(next);
               this.cursorTarget = original;
               this.analyze();
            } else {
               this.revision++;
            }
         } else {
            this.revision++;
         }

         this.setChanged();
      }
   }

   public void invalidatePlan() {
      this.approvedPlanEpoch = -1L;
      this.approvedAdapterEpoch = -1;
      if (this.state != AutoPatternTerminalPart.State.IDLE && this.state != AutoPatternTerminalPart.State.COMPLETE) {
         this.state = AutoPatternTerminalPart.State.STALE;
         this.error = "";
         this.revision++;
         this.setChanged();
      }
   }

   public void cancel() {
      this.planEpoch++;
      this.approvedPlanEpoch = -1L;
      this.approvedAdapterEpoch = -1;
      this.fluidPreview.clear();
      this.pendingJei.clear();
      this.queuedJei.clear();
      this.jeiFlights.clear();
      this.jeiRecipeIds.clear();
      this.validation = Collections.emptyIterator();
      this.capacityValidation = Collections.emptyIterator();
      this.backgroundValidation = Collections.emptyIterator();
      this.simulatedOutput.clear();
      this.planner = null;
      this.queue = List.of();
      this.discovered.clear();
      this.cursor = 0;
      this.completed = 0;
      this.generated.clear();
      this.state = AutoPatternTerminalPart.State.IDLE;
      this.error = "";
      this.revision++;
      this.setChanged();
   }

   public void analyze() {
      this.analyze(null);
   }

   private void analyze(AEKey changed) {
      if (this.target != null) {
         this.planEpoch++;
         this.approvedPlanEpoch = -1L;
         this.approvedAdapterEpoch = -1;
         this.fluidPreview.clear();
         this.pendingJei.clear();
         this.queuedJei.clear();
         this.jeiFlights.clear();
         this.jeiTimedOut = false;
         AnalysisSession<AEKey, PatternRecipe> previous = this.planner;
         boolean incremental = changed != null && previous != null && previous.done() && this.unchanged() && this.analysisBufferVersion == this.bufferVersion;
         this.planner = null;
         this.queue = List.of();
         this.cursor = 0;
         this.completed = 0;
         this.generated.clear();
         this.error = "";
         this.validation = Collections.emptyIterator();
         this.backgroundValidation = Collections.emptyIterator();
         if (!incremental) {
            this.discovered.clear();
         }

         this.materialRanks.clear();
         this.orderingKeys.clear();
         this.analyzedGrid = this.node().getGrid();
         this.topology = this.topologyVersion();
         this.reloadEpoch = AEAutoPatterns.reloadEpoch;
         RecipeAdapter.Settings requestedSettings = new RecipeAdapter.Settings(this.allowProcessing, this.substitute, this.substituteFluids);
         if (this.adapter == null || !requestedSettings.equals(this.adapterSettings) || this.adapterReloadEpoch != this.reloadEpoch) {
            this.adapter = null;
            this.adapterSettings = requestedSettings;
            this.adapterReloadEpoch = this.reloadEpoch;
            this.jeiRecipeIds.clear();
            incremental = false;
            this.discovered.clear();
         }

         this.lookup = new PatternLookup(this.analyzedGrid, this.level, this.inventory);
         this.analysisBufferVersion = this.bufferVersion;
         this.analysisStarted = System.nanoTime();
         this.indexNanos = 0L;
         if (incremental) {
            this.planner = previous.revise(changed, this::choices).awaitOrdering(this::jeiReady).orderWith(this::orderJei);
            this.state = AutoPatternTerminalPart.State.ANALYZING;
         } else {
            this.state = AutoPatternTerminalPart.State.INDEXING;
         }

         this.revision++;
         this.setChanged();
      }
   }

   public void setJeiClient(UUID player, boolean available) {
      if (available) {
         this.jeiClients.add(player);
      } else {
         this.jeiClients.remove(player);
      }
   }

   public void receiveJeiCandidates(int expectedRevision, AEKey key, List<String> ids) {
      if (expectedRevision == this.revision && this.pendingJei.containsKey(key)) {
         this.pendingJei.remove(key);
         this.jeiRecipeIds.put(key, ids.stream().filter(id -> id.length() <= 256).distinct().limit(256L).toList());
      }
   }

   public void receiveJeiBatch(int expectedRevision, int batch, CompoundTag data, UUID player) {
      AutoPatternTerminalPart.JeiFlight flight = this.jeiFlights.get(batch);
      if (expectedRevision == this.revision && flight != null && flight.player().equals(player) && this.serverTick - flight.sent() < 40L) {
         int characters = 0;

         for (Tag raw : data.getList("entries", 10)) {
            CompoundTag entry = (CompoundTag)raw;
            AEKey key = AEKey.fromTagGeneric(this.level.registryAccess(), entry.getCompound("key"));
            if (key != null && flight.keys().contains(key)) {
               ArrayList<String> ids = new ArrayList<>();
               ListTag list = entry.getList("ids", 8);

               for (int i = 0; i < Math.min(256, list.size()); i++) {
                  String id = list.getString(i);
                  characters += id.length();
                  if (id.length() > 256 || characters > 8192) {
                     break;
                  }

                  ids.add(id);
               }

               this.receiveJeiCandidates(expectedRevision, key, ids);
            }
         }

         for (AEKey key : flight.keys()) {
            if (this.pendingJei.remove(key) != null) {
               this.jeiRecipeIds.putIfAbsent(key, List.of());
            }
         }

         this.jeiFlights.remove(batch);
      }
   }

   private boolean jeiReady() {
      return this.pendingJei.isEmpty() && this.queuedJei.isEmpty() && this.jeiFlights.isEmpty();
   }

   private List<DependencyPlanner.Choice<AEKey, PatternRecipe>> orderJei(AEKey key, List<DependencyPlanner.Choice<AEKey, PatternRecipe>> choices) {
      List<String> ids = this.jeiRecipeIds.getOrDefault(key, List.of());
      if (ids.isEmpty()) {
         return choices;
      } else {
         HashMap<String, Integer> ranks = new HashMap<>();

         for (int i = 0; i < ids.size(); i++) {
            ranks.putIfAbsent(ids.get(i), i);
         }

         Comparator<DependencyPlanner.Choice<AEKey, PatternRecipe>> comparator = Comparator.comparingInt(c -> ranks.getOrDefault(c.id(), Integer.MAX_VALUE));
         ArrayList<DependencyPlanner.Choice<AEKey, PatternRecipe>> all = new ArrayList<>(this.discovered.getOrDefault(key, List.of()));
         all.sort(comparator);
         this.discovered.put(key, List.copyOf(all));
         ArrayList<DependencyPlanner.Choice<AEKey, PatternRecipe>> result = new ArrayList<>(choices);
         result.sort(comparator);
         return List.copyOf(result);
      }
   }

   private ServerPlayer jeiPlayer() {
      if (this.level instanceof ServerLevel server) {
         for (UUID id : this.jeiClients) {
            Player player = server.getPlayerByUUID(id);
            if (player instanceof ServerPlayer sp && player.containerMenu instanceof AutoPatternMenu menu && menu.terminal() == this) {
               return sp;
            }
         }
      }

      return null;
   }

   private void flushJei() {
      ServerPlayer player = this.jeiPlayer();
      if (player == null || this.jeiFlights.values().stream().anyMatch(f -> this.serverTick - f.sent() >= 40L)) {
         this.jeiTimedOut = true;
      }

      if (this.jeiTimedOut) {
         for (AEKey key : this.pendingJei.keySet()) {
            this.jeiRecipeIds.putIfAbsent(key, List.of());
         }

         this.pendingJei.clear();
         this.queuedJei.clear();
         this.jeiFlights.clear();
      } else {
         while (this.jeiFlights.size() < 2 && !this.queuedJei.isEmpty()) {
            ArrayList<AEKey> keys = new ArrayList<>();
            ListTag entries = new ListTag();
            Iterator<AEKey> it = this.queuedJei.iterator();

            while (it.hasNext() && keys.size() < 32) {
               AEKey key = it.next();
               it.remove();
               keys.add(key);
               this.pendingJei.put(key, this.serverTick);
               CompoundTag entry = new CompoundTag();
               entry.put("key", key.toTagGeneric(this.level.registryAccess()));
               entries.add(entry);
            }

            int batch = this.nextJeiBatch++;
            CompoundTag data = new CompoundTag();
            data.put("entries", entries);
            this.jeiFlights.put(batch, new AutoPatternTerminalPart.JeiFlight(player.getUUID(), List.copyOf(keys), this.serverTick));
            PacketDistributor.sendToPlayer(
               player, new TerminalPackets.JeiBatch(player.containerMenu.containerId, this.revision, batch, data, false), new CustomPacketPayload[0]
            );
         }
      }
   }

   public void startOrResume() {
      if (this.state == AutoPatternTerminalPart.State.READY || this.state == AutoPatternTerminalPart.State.PAUSED) {
         this.error = "";
         this.beginValidation(AutoPatternTerminalPart.State.RUNNING);
         this.setChanged();
      }
   }

   public void pause() {
      if (this.state == AutoPatternTerminalPart.State.RUNNING
         || this.state == AutoPatternTerminalPart.State.NO_BLANKS
         || this.state == AutoPatternTerminalPart.State.FULL
         || this.state == AutoPatternTerminalPart.State.OFFLINE
         || this.state == AutoPatternTerminalPart.State.VALIDATING) {
         this.state = AutoPatternTerminalPart.State.PAUSED;
         this.setChanged();
      }
   }

   public void toggleExternal(AEKey key) {
      if (!this.external.remove(key)) {
         this.external.add(key);
      }

      this.analyze(key);
   }

   public void setExternal(Collection<AEKey> keys) {
      boolean changed = false;

      for (AEKey key : keys) {
         changed |= this.external.add(key);
      }

      if (changed) {
         this.analyze();
      }
   }

   public void nextRecipe(AEKey key) {
      List<DependencyPlanner.Choice<AEKey, PatternRecipe>> list = this.recipeOptions(key);
      if (!list.isEmpty()) {
         DependencyPlanner.Node<AEKey, PatternRecipe> current = this.nodes().get(key);
         String id = current != null ? current.choice().id() : this.recipeChoices.getOrDefault(key, list.getFirst().id());
         int at = -1;

         for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) {
               at = i;
            }
         }

         this.external.remove(key);
         this.recipeChoices.put(key, list.get((at + 1) % list.size()).id());
         this.ingredientChoices.remove(key);
         this.analyze(key);
      }
   }

   public void chooseRecipe(AEKey key, String id) {
      Optional<DependencyPlanner.Choice<AEKey, PatternRecipe>> selected = this.recipeOptions(key)
         .stream()
         .filter(choice -> this.recipeOptionMatches((DependencyPlanner.Choice<AEKey, PatternRecipe>)choice, id))
         .findFirst();
      if (!selected.isEmpty()) {
         this.external.remove(key);
         this.recipeChoices.put(key, selected.get().id());
         this.ingredientChoices.remove(key);
         this.analyze(key);
      }
   }

   public AutoPatternTerminalPart.SimilarRecipeBatch similarRecipeBatch(AEKey source, String sourceChoiceId) {
      if (source != null
         && sourceChoiceId != null
         && this.state == AutoPatternTerminalPart.State.WAITING_SELECTION
         && this.planner != null
         && this.planner.done()
         && this.lookup != null
         && this.unchanged()) {
         Map<AEKey, DependencyPlanner.Node<AEKey, PatternRecipe>> allNodes = this.nodes();
         DependencyPlanner.Node<AEKey, PatternRecipe> sourceNode = allNodes.get(source);
         if (sourceNode != null
            && sourceNode.choice().kind() == DependencyPlanner.Kind.NEEDS_RECIPE
            && !this.external.contains(source)
            && !this.recipeChoices.containsKey(source)
            && !this.lookup.exists(source)) {
            DependencyPlanner.Choice<AEKey, PatternRecipe> sourceChoice = this.supportedRecipeOptions(this.discovered.getOrDefault(source, List.of()))
               .stream()
               .filter(choice -> choice.id().equals(sourceChoiceId))
               .findFirst()
               .orElse(null);
            if (sourceChoice == null) {
               return null;
            } else {
               ResourceLocation family = this.similarMachineFamily(sourceChoice.payload());
               if (family == null) {
                  return null;
               } else if (!this.similarChoiceLive(source, sourceChoice)) {
                  return null;
               } else {
                  ArrayList<AutoPatternTerminalPart.SimilarRecipeTarget> targets = new ArrayList<>();
                  targets.add(this.similarTarget(source, sourceChoice));
                  ArrayList<Entry<AEKey, DependencyPlanner.Node<AEKey, PatternRecipe>>> others = new ArrayList<>(allNodes.entrySet());
                  others.sort(Comparator.comparing(entryx -> ((AEKey)entryx.getKey()).toTagGeneric(this.level.registryAccess()).toString()));

                  for (Entry<AEKey, DependencyPlanner.Node<AEKey, PatternRecipe>> entry : others) {
                     AEKey key = entry.getKey();
                     if (!key.equals(source)
                        && entry.getValue().choice().kind() == DependencyPlanner.Kind.NEEDS_RECIPE
                        && !this.external.contains(key)
                        && !this.recipeChoices.containsKey(key)
                        && !this.lookup.exists(key)) {
                        List<DependencyPlanner.Choice<AEKey, PatternRecipe>> choices = this.liveSimilarChoices(key, family);
                        if (choices.size() == 1) {
                           targets.add(this.similarTarget(key, choices.getFirst()));
                        }
                     }
                  }

                  if (targets.size() < 2) {
                     return null;
                  } else {
                     List<AutoPatternTerminalPart.SimilarRecipeTarget> immutable = List.copyOf(targets);
                     return new AutoPatternTerminalPart.SimilarRecipeBatch(source, sourceChoiceId, immutable, this.similarToken(immutable));
                  }
               }
            }
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   public boolean applySimilarRecipes(AEKey source, String sourceChoiceId, String expectedToken) {
      if (expectedToken != null && !expectedToken.isBlank()) {
         AutoPatternTerminalPart.SimilarRecipeBatch preview = this.similarRecipeBatch(source, sourceChoiceId);
         if (preview != null && preview.token().equals(expectedToken)) {
            for (AutoPatternTerminalPart.SimilarRecipeTarget target : preview.targets()) {
               this.external.remove(target.key());
               this.recipeChoices.put(target.key(), target.choiceId());
               this.ingredientChoices.remove(target.key());
            }

            this.analyze();
            return true;
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private AutoPatternTerminalPart.SimilarRecipeTarget similarTarget(AEKey key, DependencyPlanner.Choice<AEKey, PatternRecipe> choice) {
      ResourceLocation recipeId = this.payloadRecipeId(choice.payload());
      if (recipeId == null) {
         throw new IllegalStateException("Missing processing recipe ID");
      } else {
         return new AutoPatternTerminalPart.SimilarRecipeTarget(key, choice.id(), recipeId);
      }
   }

   private List<DependencyPlanner.Choice<AEKey, PatternRecipe>> liveSimilarChoices(AEKey key, ResourceLocation family) {
      ArrayList<DependencyPlanner.Choice<AEKey, PatternRecipe>> result = new ArrayList<>();

      for (DependencyPlanner.Choice<AEKey, PatternRecipe> choice : this.supportedRecipeOptions(this.discovered.getOrDefault(key, List.of()))) {
         if (family.equals(this.similarMachineFamily(choice.payload())) && this.similarChoiceLive(key, choice)) {
            result.add(choice);
         }
      }

      return List.copyOf(result);
   }

   private ResourceLocation similarMachineFamily(PatternRecipe payload) {
      if (this.processingRecipeId(payload) == null) {
         return null;
      } else {
         AdaptedRecipe adapted = payload.adapted();
         if (adapted != null) {
            return adapted.kind() != RecipeKind.PROCESSING && adapted.kind() != RecipeKind.MACHINE ? null : adapted.requirements().machineType();
         } else {
            return payload.recipe() != null && payload.recipe().value() instanceof AbstractCookingRecipe cooking
               ? BuiltInRegistries.RECIPE_TYPE.getKey(cooking.getType())
               : null;
         }
      }
   }

   private boolean similarChoiceLive(AEKey key, DependencyPlanner.Choice<AEKey, PatternRecipe> choice) {
      PatternRecipe payload = choice.payload();
      if (payload != null && payload.adapter() != null) {
         RecipeHolder<?> holder = this.similarRecipeHolder(payload);
         if (holder == null || this.level.getRecipeManager().byKey(holder.id()).orElse(null) != holder) {
            return false;
         } else if (payload.recipe() != null && payload.recipe() != holder) {
            return false;
         } else {
            AdaptedRecipe adapted = payload.adapted();
            if (adapted != null) {
               if (!adapted.recipeId().equals(holder.id())) {
                  return false;
               } else {
                  Object source = adapted.source();
                  if (source instanceof RecipeSnapshot snapshot) {
                     try {
                        snapshot.verify(this.level, adapted);
                     } catch (RuntimeException var10) {
                        return false;
                     }
                  } else {
                     if (!(source instanceof RecipeHolder<?> sourceHolder)) {
                        return false;
                     }

                     if (sourceHolder != holder) {
                        return false;
                     }
                  }

                  return adapted.outputs().stream().anyMatch(output -> output.key().equals(key));
               }
            } else if (payload.recipe() != null && payload.recipe().value() instanceof AbstractCookingRecipe cooking) {
               ItemStack output = cooking.getResultItem(this.level.registryAccess());
               return !output.isEmpty() && key.equals(AEItemKey.of(output));
            } else {
               return false;
            }
         }
      } else {
         return false;
      }
   }

   private RecipeHolder<?> similarRecipeHolder(PatternRecipe payload) {
      if (payload.recipe() != null) {
         return payload.recipe();
      } else if (payload.adapted() == null) {
         return null;
      } else {
         Object source = payload.adapted().source();
         if (source instanceof RecipeSnapshot snapshot) {
            return snapshot.holder();
         } else {
            return source instanceof RecipeHolder ? (RecipeHolder)source : null;
         }
      }
   }

   private String similarToken(List<AutoPatternTerminalPart.SimilarRecipeTarget> targets) {
      try {
         MessageDigest digest = MessageDigest.getInstance("SHA-256");

         for (AutoPatternTerminalPart.SimilarRecipeTarget target : targets) {
            digest.update(target.key().toTagGeneric(this.level.registryAccess()).toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte)0);
            digest.update(target.choiceId().getBytes(StandardCharsets.UTF_8));
            digest.update((byte)0);
         }

         return HexFormat.of().formatHex(digest.digest());
      } catch (NoSuchAlgorithmException var5) {
         throw new IllegalStateException("SHA-256 unavailable", var5);
      }
   }

   public List<List<AEKey>> ingredientOptions(AEKey key) {
      DependencyPlanner.Node<AEKey, PatternRecipe> node = this.nodes().get(key);
      return node != null && node.choice().kind() == DependencyPlanner.Kind.GENERATE
         ? this.options(key).stream().filter(choice -> choice.id().equals(node.choice().id())).findFirst().orElse(node.choice()).ingredients()
         : List.of();
   }

   public boolean replaceIngredient(AEKey key, String recipeId, AEKey current, AEKey replacement) {
      DependencyPlanner.Node<AEKey, PatternRecipe> node = this.nodes().get(key);
      if (node != null && node.choice().id().equals(recipeId) && !current.equals(replacement)) {
         List<List<AEKey>> groups = this.ingredientOptions(key);
         if (groups.size() != node.children().size()) {
            return false;
         } else {
            ArrayList<Integer> slots = new ArrayList<>();

            for (int slot = 0; slot < groups.size(); slot++) {
               if (node.children().get(slot).equals(current)) {
                  if (!groups.get(slot).contains(replacement)) {
                     return false;
                  }

                  slots.add(slot);
               }
            }

            if (slots.isEmpty()) {
               return false;
            } else {
               Map<Integer, AEKey> selected = this.ingredientChoices.computeIfAbsent(key, ignored -> new HashMap<>());

               for (int slotx : slots) {
                  selected.put(slotx, replacement);
               }

               this.recipeChoices.put(key, recipeId);
               this.analyze(key);
               return true;
            }
         }
      } else {
         return false;
      }
   }

   private List<DependencyPlanner.Choice<AEKey, PatternRecipe>> choices(AEKey key) {
      if (this.discovered.size() >= (Integer)AnalysisConfig.NODES.get() && !this.discovered.containsKey(key)) {
         throw new IllegalStateException("Candidate material limit: " + AnalysisConfig.NODES.get());
      } else {
         List<DependencyPlanner.Choice<AEKey, PatternRecipe>> existing = this.lookup.find(key);
         List<DependencyPlanner.Choice<AEKey, PatternRecipe>> base = existing.isEmpty() ? this.adapter.find(key) : existing;
         if (existing.isEmpty() && !base.isEmpty() && key instanceof AEItemKey && !this.jeiRecipeIds.containsKey(key) && !this.pendingJei.containsKey(key)) {
            if (!this.jeiTimedOut && this.jeiPlayer() != null) {
               this.pendingJei.put(key, -1L);
               this.queuedJei.add(key);
            } else {
               this.jeiRecipeIds.put(key, List.of());
            }
         }

         if (base.isEmpty()) {
            base = List.of(
               new DependencyPlanner.Choice<>(
                  "external",
                  key instanceof AEItemKey && this.adapter.unsupported(key) ? DependencyPlanner.Kind.UNSUPPORTED : DependencyPlanner.Kind.EXTERNAL,
                  List.of(),
                  null
               )
            );
         }

         ArrayList<DependencyPlanner.Choice<AEKey, PatternRecipe>> sorted = new ArrayList<>();

         for (DependencyPlanner.Choice<AEKey, PatternRecipe> c : base) {
            ArrayList<List<AEKey>> groups = new ArrayList<>();

            for (List<AEKey> group : c.ingredients()) {
               ArrayList<AEKey> variants = new ArrayList<>(group);
               variants.sort(
                  Comparator.<AEKey>comparingInt(
                        k -> this.materialRanks.computeIfAbsent(k, v -> this.lookup.existsCached(v) ? 0 : (this.adapter.hasRecipe(v) ? 1 : 2))
                     )
                     .thenComparing(k -> this.orderingKeys.computeIfAbsent(k, v -> v.toTagGeneric(this.level.registryAccess()).toString()))
               );
               groups.add(List.copyOf(variants));
            }

            sorted.add(new DependencyPlanner.Choice<>(c.id(), c.kind(), List.copyOf(groups), c.payload()));
         }

         List<String> preferred = this.jeiRecipeIds.get(key);
         if (preferred != null && !preferred.isEmpty()) {
            HashMap<String, Integer> ranks = new HashMap<>();

            for (int i = 0; i < preferred.size(); i++) {
               ranks.put(preferred.get(i), i);
            }

            sorted.sort(Comparator.comparingInt(cx -> ranks.getOrDefault(cx.id(), Integer.MAX_VALUE)));
            if (sorted.stream().noneMatch(cx -> ranks.containsKey(cx.id()))) {
               sorted.sort(Comparator.comparing(DependencyPlanner.Choice::id));
            }
         }

         sorted = new ArrayList<>(this.rankRecipeOptions(sorted));
         this.discovered.put(key, List.copyOf(sorted));
         if (this.external.contains(key)) {
            return List.of(new DependencyPlanner.Choice<>("manual_external", DependencyPlanner.Kind.EXTERNAL, List.of(), null));
         } else if (!existing.isEmpty()) {
            return sorted;
         } else {
            String chosen = this.recipeChoices.get(key);
            if (chosen != null && !sorted.stream().map(DependencyPlanner.Choice::id).toList().contains(chosen)) {
               this.recipeChoices.remove(key);
               this.ingredientChoices.remove(key);
               chosen = null;
            }

            if (chosen == null && this.supportedRecipeOptions(sorted).size() > 1) {
               return List.of(new DependencyPlanner.Choice<>("choose_recipe", DependencyPlanner.Kind.NEEDS_RECIPE, List.of(), null));
            } else {
               if (chosen != null) {
                  String selectedRecipe = chosen;
                  sorted.removeIf(cx -> !cx.id().equals(selectedRecipe));
               }

               Map<Integer, AEKey> forced = this.ingredientChoices.getOrDefault(key, Map.of());
               if (forced.isEmpty()) {
                  return sorted;
               } else {
                  ArrayList<DependencyPlanner.Choice<AEKey, PatternRecipe>> adjusted = new ArrayList<>();

                  for (DependencyPlanner.Choice<AEKey, PatternRecipe> c : sorted) {
                     ArrayList<List<AEKey>> groups = new ArrayList<>();

                     for (int i = 0; i < c.ingredients().size(); i++) {
                        AEKey override = forced.get(i);
                        List<AEKey> group = c.ingredients().get(i);
                        groups.add(override == null ? group : (group.contains(override) ? List.of(override) : List.of()));
                     }

                     adjusted.add(new DependencyPlanner.Choice<>(c.id(), c.kind(), groups, c.payload()));
                  }

                  return adjusted;
               }
            }
         }
      }
   }

   private long topologyVersion() {
      IGrid grid = this.node().getGrid();
      return grid == null ? -1L : ((AnalysisGridService)grid.getService(AnalysisGridService.class)).version();
   }

   private boolean unchanged() {
      return this.reloadEpoch == AEAutoPatterns.reloadEpoch && this.node().getGrid() == this.analyzedGrid && this.topology == this.topologyVersion();
   }

   private boolean nodeUnchanged(DependencyPlanner.Node<AEKey, PatternRecipe> n) {
      PatternRecipe payload = n.choice().payload();
      if (payload != null && payload.recipe() != null) {
         if (this.level.getRecipeManager().byKey(payload.recipe().id()).orElse(null) != payload.recipe()) {
            return false;
         }

         if (payload.adapted() != null && !payload.recipe().id().equals(payload.adapted().recipeId())) {
            return false;
         }
      }

      return payload != null
            && payload.adapted() != null
            && payload.adapted().source() instanceof RecipeHolder<?> holder
            && this.level.getRecipeManager().byKey(holder.id()).orElse(null) != holder
         ? false
         : n.choice().kind() != DependencyPlanner.Kind.NETWORK && n.choice().kind() != DependencyPlanner.Kind.BUFFER
            || this.lookup.find(n.key()).stream().anyMatch(c -> c.id().equals(n.choice().id()));
   }

   private boolean planApproved() {
      return this.approvedPlanEpoch == this.planEpoch
         && this.approvedAdapterEpoch == this.adapterReloadEpoch
         && this.adapterReloadEpoch == AEAutoPatterns.reloadEpoch
         && this.adapter != null;
   }

   private boolean adapterApprovedForEncoding(PatternRecipe payload) {
      if (payload != null && payload.adapter() != null) {
         Set<AdapterCapability> capabilities = payload.adapter().capabilities();
         return !capabilities.contains(AdapterCapability.VALIDATION)
            ? false
            : this.processingRecipeId(payload) == null || capabilities.contains(AdapterCapability.AE_PROCESSING_ENCODING);
      } else {
         return false;
      }
   }

   private void beginValidation(AutoPatternTerminalPart.State next) {
      this.validation = this.nodes().values().iterator();
      this.afterValidation = next;
      this.state = AutoPatternTerminalPart.State.VALIDATING;
      this.validationBufferVersion = this.bufferVersion;
      this.simulatedOutput.clear();
      this.simulatedFree = OUTPUT_PATTERN_SLOTS;
      this.capacityValidation = Collections.emptyIterator();
      if (next == AutoPatternTerminalPart.State.RUNNING && !this.autoUpload) {
         for (int i = 1; i < this.inventory.getSlots(); i++) {
            if (!this.inventory.getStackInSlot(i).isEmpty()) {
               this.simulatedOutput.add(this.inventory.getStackInSlot(i).copy());
               this.simulatedFree--;
            }
         }

         this.capacityValidation = this.queue.subList(this.cursor, this.queue.size()).iterator();
      }
   }

   public AnalysisSession.Metrics analysisMetrics() {
      return this.planner == null ? null : this.planner.metrics();
   }

   public long indexNanos() {
      return this.indexNanos;
   }

   public int pendingJeiCount() {
      return this.pendingJei.size();
   }

   public String adapterDiagnostics() {
      if (this.adapter instanceof CompositeRecipeAdapter composite) {
         Optional<Map.Entry<String, CompositeRecipeAdapter.Timing>> var4 = composite.timings()
            .entrySet()
            .stream()
            .filter(e -> e.getValue().maxNanos() > 4000000L)
            .max(Comparator.comparingLong(e -> ((CompositeRecipeAdapter.Timing)e.getValue()).maxNanos()));
         ArrayList<String> lines = new ArrayList<>();
         var4.ifPresent(
            e -> lines.add(
                  (String)e.getKey()
                     + ": max "
                     + String.format(Locale.ROOT, "%.2fms", (double)((CompositeRecipeAdapter.Timing)e.getValue()).maxNanos() / 1000000.0)
               )
         );
         composite.failures().entrySet().stream().limit(4L).forEach(e -> lines.add(e.getKey() + ": " + e.getValue()));
         return String.join("; ", lines);
      } else {
         return "";
      }
   }

   public void tick() {
      this.serverTick++;
      this.tick(System.nanoTime() + 2000000L);
   }

   private void tick(long deadline) {
      if (this.state != AutoPatternTerminalPart.State.IDLE
         && this.state != AutoPatternTerminalPart.State.COMPLETE
         && this.state != AutoPatternTerminalPart.State.ERROR
         && this.state != AutoPatternTerminalPart.State.STALE) {
         try {
            if (!this.online()) {
               if (this.state == AutoPatternTerminalPart.State.RUNNING
                  || this.state == AutoPatternTerminalPart.State.NO_BLANKS
                  || this.state == AutoPatternTerminalPart.State.FULL) {
                  this.state = AutoPatternTerminalPart.State.OFFLINE;
               }

               return;
            }

            if (!this.unchanged()) {
               this.invalidatePlan();
               return;
            }

            if ((this.state == AutoPatternTerminalPart.State.INDEXING || this.state == AutoPatternTerminalPart.State.ANALYZING)
               && this.analysisBufferVersion != this.bufferVersion) {
               this.invalidatePlan();
               return;
            }

            if (this.state == AutoPatternTerminalPart.State.INDEXING) {
               long started = System.nanoTime();
               if (this.adapter == null) {
                  this.adapter = AdapterRegistry.create(this.level, this.adapterSettings);
                  this.adapterDeclarations = AdapterRegistry.declarations(this.level);
                  long elapsed = System.nanoTime() - started;
                  if (elapsed > 4000000L) {
                     LoggerFactory.getLogger("aeautopatterns.analysis.adapters")
                        .warn("Adapter initialization: {}ms (soft tick budget)", (double)elapsed / 1000000.0);
                  }

                  this.indexNanos += elapsed;
                  return;
               }

               do {
                  if (this.adapter.indexStep(1)) {
                     this.planner = new AnalysisSession<>(this.target, this::choices, this.recipeChoices::containsKey, AnalysisConfig.limits())
                        .awaitOrdering(this::jeiReady)
                        .orderWith(this::orderJei);
                     this.state = AutoPatternTerminalPart.State.ANALYZING;
                     break;
                  }
               } while (System.nanoTime() < deadline);

               this.indexNanos = this.indexNanos + (System.nanoTime() - started);
               return;
            }

            if (this.state == AutoPatternTerminalPart.State.ANALYZING) {
               this.planner.step(deadline);
               this.flushJei();
               if (this.planner.done()) {
                  this.discovered.keySet().retainAll(this.planner.materialKeys());
                  if (!this.planner.error().isEmpty()) {
                     this.error = this.planner.error();
                     this.state = AutoPatternTerminalPart.State.ERROR;
                  } else if (this.planner.nodes().values().stream().anyMatch(n -> n.choice().kind() == DependencyPlanner.Kind.UNSUPPORTED)) {
                     this.error = "Unsupported recipe. Select an external input or another recipe.";
                     this.state = AutoPatternTerminalPart.State.ERROR;
                  } else if (this.planner.nodes().values().stream().anyMatch(n -> n.choice().kind() == DependencyPlanner.Kind.NEEDS_RECIPE)) {
                     this.queue = List.of();
                     this.state = AutoPatternTerminalPart.State.WAITING_SELECTION;
                  } else {
                     this.queue = this.planner.queue();
                     this.beginValidation(AutoPatternTerminalPart.State.READY);
                  }

                  LoggerFactory.getLogger("aeautopatterns.analysis")
                     .info(
                        "Analysis {}: {}, index={}ms elapsed={}ms",
                        new Object[]{
                           this.target,
                           this.planner.metrics(),
                           (double)this.indexNanos / 1000000.0,
                           (double)(System.nanoTime() - this.analysisStarted) / 1000000.0
                        }
                     );
                  this.revision++;
                  this.setChanged();
               }

               return;
            }

            if (this.state == AutoPatternTerminalPart.State.VALIDATING) {
               if (this.validationBufferVersion != this.bufferVersion) {
                  this.beginValidation(this.afterValidation);
                  return;
               }

               for (int i = 0; i < 64 && this.validation.hasNext() && System.nanoTime() < deadline; i++) {
                  if (!this.nodeUnchanged(this.validation.next())) {
                     this.invalidatePlan();
                     return;
                  }
               }

               if (!this.validation.hasNext()) {
                  for (int ix = 0; ix < 64 && this.capacityValidation.hasNext() && System.nanoTime() < deadline; ix++) {
                     if (!this.reserveOutput(this.capacityValidation.next())) {
                        this.state = AutoPatternTerminalPart.State.FULL;
                        this.error = "Output buffer is too small for the remaining patterns. Remove encoded patterns and resume.";
                        this.setChanged();
                        return;
                     }
                  }

                  if (!this.capacityValidation.hasNext()) {
                     this.state = this.afterValidation;
                     this.approvedPlanEpoch = this.planEpoch;
                     this.approvedAdapterEpoch = this.adapterReloadEpoch;
                     this.setChanged();
                  }
               }

               return;
            }

            if (!this.backgroundValidation.hasNext()) {
               this.backgroundValidation = this.nodes().values().iterator();
            }

            for (int ixx = 0; ixx < 64 && this.backgroundValidation.hasNext() && System.nanoTime() < deadline; ixx++) {
               if (!this.nodeUnchanged(this.backgroundValidation.next())) {
                  this.invalidatePlan();
                  return;
               }
            }

            if (System.nanoTime() >= deadline) {
               return;
            }

            if (this.state == AutoPatternTerminalPart.State.RUNNING
               || this.state == AutoPatternTerminalPart.State.NO_BLANKS
               || this.state == AutoPatternTerminalPart.State.FULL
               || this.state == AutoPatternTerminalPart.State.OFFLINE) {
               this.encodeNext();
            }
         } catch (RuntimeException var7) {
            this.state = AutoPatternTerminalPart.State.ERROR;
            this.error = Objects.toString(var7.getMessage(), var7.getClass().getSimpleName());
            this.revision++;
            this.setChanged();
         }
      }
   }

   private void encodeNext() {
      if (this.cursor >= this.queue.size()) {
         this.state = AutoPatternTerminalPart.State.COMPLETE;
         this.setChanged();
      } else {
         DependencyPlanner.Node<AEKey, PatternRecipe> n = this.queue.get(this.cursor);
         if (!this.nodeUnchanged(n)) {
            this.invalidatePlan();
         } else if (this.lookup.exists(n.key())) {
            this.cursor++;
            this.completed++;
            this.setChanged();
         } else if (!this.planApproved()) {
            this.invalidatePlan();
         } else if (this.inventory.getStackInSlot(0).isEmpty()) {
            this.state = AutoPatternTerminalPart.State.NO_BLANKS;
         } else {
            PatternRecipe payload = n.choice().payload();
            if (!this.adapterApprovedForEncoding(payload)) {
               this.state = AutoPatternTerminalPart.State.ERROR;
               this.error = "Recipe generation was not approved by its adapter";
               this.revision++;
               this.setChanged();
            } else {
               ItemStack encoded = payload.adapter().encode(payload, n.children(), n.key(), this.level);
               if (this.autoUpload) {
                  ResourceLocation recipeId = this.processingRecipeId(payload);
                  if (this.routeGroups.routingConfigured() && recipeId != null) {
                     this.encodeRoutedProcessing(n, recipeId, encoded);
                  } else {
                     PatternDeployment.Result result = PatternDeployment.deploy(
                        this.node().getGrid(), this.providerSelections(), this.autoContinueProviders, encoded, this.level
                     );
                     if (result == PatternDeployment.Result.NO_TARGET && recipeId != null && !this.hasUnavailableSelectedProvider()) {
                        this.keepProcessingPatternLocally(
                           n, encoded, "No visible pattern provider; pattern kept in output buffer"
                        );
                     } else if (result != PatternDeployment.Result.INSERTED && result != PatternDeployment.Result.DUPLICATE) {
                        this.uploadStatus = switch (result) {
                           case NO_TARGET -> "No visible pattern provider";
                           case FULL -> "Selected pattern provider is full";
                           case OFFLINE -> "Pattern provider network is offline";
                           default -> "Upload waiting";
                        };
                        this.state = result == PatternDeployment.Result.FULL ? AutoPatternTerminalPart.State.FULL : AutoPatternTerminalPart.State.OFFLINE;
                        this.error = this.uploadStatus;
                        this.setChanged();
                     } else {
                        if (result == PatternDeployment.Result.INSERTED) {
                           this.inventory.extractItem(0, 1, false);
                        }

                        this.generated.add(n.key());
                        this.cursor++;
                        this.completed++;
                        this.state = AutoPatternTerminalPart.State.RUNNING;
                        this.uploadStatus = result == PatternDeployment.Result.DUPLICATE ? "Pattern already installed" : "Uploaded to provider";
                        this.error = "";
                        this.setChanged();
                     }
                  }
               } else if (!this.addEncodedPatternToBuffer(encoded)) {
                  this.state = AutoPatternTerminalPart.State.FULL;
                  this.error = "Output buffer is full. Remove encoded patterns to continue.";
               } else {
                  this.generated.add(n.key());
                  this.cursor++;
                  this.completed++;
                  this.state = AutoPatternTerminalPart.State.RUNNING;
                  this.error = "";
                  this.setChanged();
               }
            }
         }
      }
   }

   private void encodeRoutedProcessing(DependencyPlanner.Node<AEKey, PatternRecipe> node, ResourceLocation recipeId, ItemStack encoded) {
      Optional<RecipeRouteGroups.GroupView> group = this.routeGroups.groupForRecipe(recipeId);
      if (group.isEmpty()) {
         this.keepProcessingPatternLocally(
            node, encoded, "Processing recipe has no route group; pattern kept in output buffer"
         );
         return;
      }

      RecipeRouteGroups.GroupView view = group.get();
      List<PatternDeployment.Target> eligible = this.eligibleProviders(view);
      if (eligible.isEmpty()) {
         if (view.providerIds().isEmpty() || this.hasActiveRouteProvider(view)) {
            this.keepProcessingPatternLocally(
               node, encoded,
               "Route group " + view.name() + " has no eligible provider; pattern kept in output buffer"
            );
         } else {
            this.routedPause("No active eligible provider in route group " + view.name());
         }
         return;
      }

      List<String> ids = eligible.stream().map(target -> PatternDeployment.identity(target.container())).toList();
      PatternDeployment.RouteDeployment deployment = PatternDeployment.deployOrdered(
         this.node().getGrid(), ids, this.autoContinueProviders, encoded, this.level
      );
      if (deployment.result() != PatternDeployment.Result.INSERTED && deployment.result() != PatternDeployment.Result.DUPLICATE) {
         if (deployment.result() == PatternDeployment.Result.FULL) {
            this.state = AutoPatternTerminalPart.State.FULL;
            this.error = deployment.message();
            this.uploadStatus = deployment.message();
            this.setChanged();
         } else {
            this.routedPause(deployment.message().isBlank() ? "No active eligible route destination" : deployment.message());
         }
      } else {
         if (deployment.result() == PatternDeployment.Result.INSERTED) {
            this.inventory.extractItem(0, 1, false);
         }

         this.generated.add(node.key());
         this.cursor++;
         this.completed++;
         this.state = AutoPatternTerminalPart.State.RUNNING;
         this.uploadStatus = deployment.result() == PatternDeployment.Result.DUPLICATE
            ? "Pattern already installed in route group"
            : "Uploaded to route group " + view.name();
         this.error = "";
         this.setChanged();
      }
   }

   private boolean hasUnavailableSelectedProvider() {
      if (!this.providerSelectionConfigured || this.selectedProviders.isEmpty()) {
         return false;
      }

      Set<String> active = new HashSet<>();
      for (PatternDeployment.Target target : this.uploadTargets()) {
         active.add(PatternDeployment.identity(target.container()));
      }

      for (String id : this.selectedProviders) {
         if (!active.contains(id)) {
            return true;
         }
      }

      return false;
   }

   private boolean hasActiveRouteProvider(RecipeRouteGroups.GroupView group) {
      for (PatternDeployment.Target target : this.uploadTargets()) {
         if (group.providerIds().contains(PatternDeployment.identity(target.container()))) {
            return true;
         }
      }

      return false;
   }

   private boolean addEncodedPatternToBuffer(ItemStack encoded) {
      int slot = -1;

      for (int i = 1; i <= OUTPUT_PATTERN_SLOTS; i++) {
         ItemStack stack = this.inventory.getStackInSlot(i);
         if (stack.isEmpty() || ItemStack.isSameItemSameComponents(stack, encoded) && stack.getCount() < stack.getMaxStackSize()) {
            slot = i;
            break;
         }
      }

      if (slot < 0) {
         return false;
      }

      ItemStack output = this.inventory.getStackInSlot(slot).copy();
      if (output.isEmpty()) {
         output = encoded.copy();
      } else {
         output.grow(1);
      }

      this.inventory.extractItem(0, 1, false);
      this.inventory.setStackInSlot(slot, output);
      return true;
   }

   private void keepProcessingPatternLocally(
      DependencyPlanner.Node<AEKey, PatternRecipe> node, ItemStack encoded, String status
   ) {
      if (!this.addEncodedPatternToBuffer(encoded)) {
         this.state = AutoPatternTerminalPart.State.FULL;
         this.error = "Output buffer is full. Remove encoded patterns to continue.";
         this.uploadStatus = this.error;
         this.setChanged();
         return;
      }

      this.generated.add(node.key());
      this.cursor++;
      this.completed++;
      this.state = AutoPatternTerminalPart.State.RUNNING;
      this.uploadStatus = status;
      this.error = "";
      this.setChanged();
   }

   private void routedPause(String message) {
      this.state = AutoPatternTerminalPart.State.PAUSED;
      this.error = message;
      this.uploadStatus = message;
      this.revision++;
      this.setChanged();
   }

   private boolean reserveOutput(DependencyPlanner.Node<AEKey, PatternRecipe> entry) {
      if (this.lookup.exists(entry.key())) {
         return true;
      } else {
         PatternRecipe payload = entry.choice().payload();
         ItemStack encoded = payload.adapter().encode(payload, entry.children(), entry.key(), this.level);

         for (ItemStack stack : this.simulatedOutput) {
            if (ItemStack.isSameItemSameComponents(stack, encoded) && stack.getCount() < stack.getMaxStackSize()) {
               stack.grow(1);
               return true;
            }
         }

         if (this.simulatedFree == 0) {
            return false;
         } else {
            this.simulatedFree--;
            this.simulatedOutput.add(encoded.copy());
            return true;
         }
      }
   }

   static {
      NeoForge.EVENT_BUS.addListener(AutoPatternTerminalPart::serverTick);
   }

   private static record JeiFlight(UUID player, List<AEKey> keys, long sent) {
   }

   public static record SimilarRecipeBatch(AEKey source, String sourceChoiceId, List<AutoPatternTerminalPart.SimilarRecipeTarget> targets, String token) {
      public SimilarRecipeBatch(AEKey source, String sourceChoiceId, List<AutoPatternTerminalPart.SimilarRecipeTarget> targets, String token) {
         Objects.requireNonNull(source, "source");
         Objects.requireNonNull(sourceChoiceId, "sourceChoiceId");
         targets = List.copyOf(targets);
         Objects.requireNonNull(token, "token");
         this.source = source;
         this.sourceChoiceId = sourceChoiceId;
         this.targets = targets;
         this.token = token;
      }

      public int count() {
         return this.targets.size();
      }
   }

   public static record SimilarRecipeTarget(AEKey key, String choiceId, ResourceLocation recipeId) {
      public SimilarRecipeTarget(AEKey key, String choiceId, ResourceLocation recipeId) {
         Objects.requireNonNull(key, "key");
         Objects.requireNonNull(choiceId, "choiceId");
         Objects.requireNonNull(recipeId, "recipeId");
         this.key = key;
         this.choiceId = choiceId;
         this.recipeId = recipeId;
      }
   }

   public static enum State {
      IDLE,
      INDEXING,
      ANALYZING,
      VALIDATING,
      WAITING_SELECTION,
      READY,
      RUNNING,
      PAUSED,
      NO_BLANKS,
      FULL,
      OFFLINE,
      STALE,
      ERROR,
      COMPLETE;
   }
}
