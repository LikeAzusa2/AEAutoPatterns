package com.likeazusa2.aeautopatterns.client;

import appeng.client.gui.Icon;
import appeng.client.gui.widgets.ToggleButton;
import appeng.core.localization.ButtonToolTips;
import com.likeazusa2.aeautopatterns.menu.AutoPatternMenu;
import com.likeazusa2.aeautopatterns.part.AutoPatternTerminalPart;
import com.likeazusa2.aeautopatterns.network.TerminalPackets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

public final class AutoPatternScreen extends AbstractContainerScreen<AutoPatternMenu> {
   private static final int ROW_TOP = 34;
   private static final int ROW_HEIGHT = 18;
   private static final int ROW_BOTTOM = 106;
   private EditBox search;
   private List<Item> results = List.of();
   private int searchOffset;
   private boolean draggingRows;
   private boolean draggingSearch;
   private double rowDragGrab;
   private int rowDragOffset;
   private int rowDragRevision;
   private Button start;
   private Button pause;
   private Button recipe;
   private Button external;
   private Button variant;
   private Button treeButton;
   private Button processing;
   private Button autoUpload;
   private Button uploadTarget;
   private DependencyTreeView tree;
   private ProviderSelectionView providers;
   private final RecipeSelectionView picker;
   private final IngredientSelectionView ingredients;
   private ToggleButton substitutions;
   private ToggleButton fluidSubstitutions;
   private int pendingWidth;
   private boolean showPendingInline;
   private Button pendingTab;
   private Button ignoreComponents;
   private Button pendingSelectAll;
   private Button pendingMakeExternal;
   private final Set<Integer> pendingSelection = new HashSet<>();
   private int pendingSelectionRevision = -1;

   void acceptJeiItem(ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         CompoundTag encoded = (CompoundTag)stack.save(Minecraft.getInstance().level.registryAccess());
         PacketDistributor.sendToServer(
            new TerminalPackets.JeiTarget(((AutoPatternMenu)this.menu).containerId, ((AutoPatternMenu)this.menu).view.getInt("revision"), encoded),
            new CustomPacketPayload[0]
         );
      }
   }

   Rect2i jeiTargetArea() {
      return new Rect2i(this.leftPos + 8, this.topPos + 34, 190, 72);
   }

   public AutoPatternScreen(AutoPatternMenu menu, Inventory inv, Component title) {
      super(menu, inv, title);
      this.imageWidth = 386;
      this.imageHeight = 270;
      this.inventoryLabelY = -100;
      this.picker = new RecipeSelectionView(menu, this::send);
      this.ingredients = new IngredientSelectionView(menu, this::send);
   }

   private Component tr(String key) {
      return Component.translatable("gui.aeautopatterns." + key);
   }

   private void send(String action, int index, String value) {
      PacketDistributor.sendToServer(
         new TerminalPackets.Action(((AutoPatternMenu)this.menu).containerId, ((AutoPatternMenu)this.menu).view.getInt("revision"), action, index, value),
         new CustomPacketPayload[0]
      );
   }

   private Button button(int x, int y, int w, String label, Runnable click) {
      return (Button)this.addRenderableWidget(new TerminalStyle.TerminalButton(this.leftPos + x, this.topPos + y, w, 16, this.tr(label), v -> click.run()));
   }

   protected void init() {
      String query = this.search == null ? "" : this.search.getValue();
      super.init();
      this.draggingRows = false;
      this.pendingWidth = this.width - this.imageWidth - 18 >= 72 ? Math.min(112, this.width - this.imageWidth - 18) : 0;
      if (this.pendingWidth > 0) {
         this.leftPos = (this.width - this.imageWidth - this.pendingWidth - 6) / 2 + this.pendingWidth + 6;
      }

      this.send("pending_scroll", 0, this.pendingWidth == 0 ? "compact" : "wide");
      int controlsX = this.pendingWidth > 0 ? -this.pendingWidth - 2 : 8;
      int controlsY = this.pendingWidth > 0 ? 49 : 34;
      int controlsWidth = this.pendingWidth > 0 ? this.pendingWidth - 8 : 190;
      int gap = 3;
      int buttonWidth = (controlsWidth - gap) / 2;
      this.pendingSelectAll = (Button)this.addRenderableWidget(
         new TerminalStyle.TerminalButton(
            this.leftPos + controlsX, this.topPos + controlsY, buttonWidth, 14, this.tr("pending.select_all"), b -> this.toggleAllPending()
         )
      );
      this.pendingMakeExternal = (Button)this.addRenderableWidget(
         new TerminalStyle.TerminalButton(
            this.leftPos + controlsX + buttonWidth + gap,
            this.topPos + controlsY,
            controlsWidth - buttonWidth - gap,
            14,
            this.tr("pending.make_external"),
            b -> this.sendBulkExternal()
         )
      );
      PacketDistributor.sendToServer(
         new TerminalPackets.Action(
            ((AutoPatternMenu)this.menu).containerId,
            ((AutoPatternMenu)this.menu).view.getInt("revision"),
            "jei_status",
            0,
            Boolean.toString(JeiCompat.available())
         ),
         new CustomPacketPayload[0]
      );
      this.search = new EditBox(this.font, this.leftPos + 200, this.topPos + 8, 172, 12, this.tr("search"));
      this.search.setBordered(false);
      this.search.setTextColor(-13092531);
      this.search.setTextColorUneditable(-11315602);
      this.search.setHint(this.tr("search"));
      this.search.setMaxLength(80);
      this.search
         .setResponder(
            value -> {
               this.draggingRows = false;
               this.searchOffset = 0;
               if (value.isBlank()) {
                  this.results = List.of();
               } else {
                  String term = value.toLowerCase(Locale.ROOT);
                  this.results = BuiltInRegistries.ITEM
                     .stream()
                     .filter(
                        i -> i != Items.AIR
                              && (
                                 BuiltInRegistries.ITEM.getKey(i).toString().contains(term)
                                    || i.getDescription().getString().toLowerCase(Locale.ROOT).contains(term)
                              )
                     )
                     .toList();
               }
            }
         );
      this.addRenderableWidget(this.search);
      this.search.setValue(query);
      this.pendingTab = (Button)this.addRenderableWidget(
         new TerminalStyle.TerminalButton(this.leftPos + 8, this.topPos + 20, 126, 12, this.tr("pending.title"), b -> {
            this.showPendingInline = !this.showPendingInline;
            if (this.showPendingInline) {
               this.search.setValue("");
            }
         })
      );
      this.pendingTab.visible = this.pendingWidth == 0;
      this.treeButton = (Button)this.addRenderableWidget(
         new TerminalStyle.TerminalButton(
            this.leftPos + 138,
            this.topPos + 20,
            60,
            12,
            this.tr("tree.open"),
            b -> {
               if (((AutoPatternMenu)this.menu).view.getBoolean("treeReady")) {
                  this.search.setFocused(false);
                  ((AutoPatternMenu)this.menu).treeNodes.clear();
                  ((AutoPatternMenu)this.menu).treeRevision = -1;
                  ((AutoPatternMenu)this.menu).treeTotal = 0;
                  ((AutoPatternMenu)this.menu).treeLimited = false;
                  this.tree = new DependencyTreeView(
                     (AutoPatternMenu)this.menu,
                     (action, index) -> this.send(action, index, ""),
                     this::closeTree,
                     this.ingredients::request,
                     this.width,
                     this.height
                  );
                  this.send("tree_open", 0, "");
               }
            }
         )
      );
      if (this.tree != null) {
         this.tree.resize(this.width, this.height);
      }

      if (this.providers != null) {
         this.providers.resize(this.width, this.height);
      }

      this.ignoreComponents = (Button)this.addRenderableWidget(
         new TerminalStyle.TerminalButton(
            this.leftPos + 264,
            this.topPos + 20,
            78,
            12,
            this.tr("ignore_components.off"),
            b -> this.send("ignore_components", 0, Boolean.toString(!((AutoPatternMenu)this.menu).view.getBoolean("ignoreTargetComponents")))
         )
      );
      this.substitutions = (ToggleButton)this.addRenderableWidget(
         new ToggleButton(Icon.S_SUBSTITUTION_ENABLED, Icon.S_SUBSTITUTION_DISABLED, enabled -> this.send("substitute", 0, Boolean.toString(enabled)))
      );
      this.substitutions.setPosition(this.leftPos + 350, this.topPos + 22);
      this.substitutions.setHalfSize(true);
      this.substitutions.setDisableBackground(true);
      this.substitutions.setWidth(8);
      this.substitutions.setHeight(8);
      this.substitutions.setTooltipOn(List.of(ButtonToolTips.SubstitutionsOn.text(), ButtonToolTips.SubstitutionsDescEnabled.text()));
      this.substitutions.setTooltipOff(List.of(ButtonToolTips.SubstitutionsOff.text(), ButtonToolTips.SubstitutionsDescDisabled.text()));
      this.fluidSubstitutions = (ToggleButton)this.addRenderableWidget(
         new ToggleButton(
            Icon.S_FLUID_SUBSTITUTION_ENABLED, Icon.S_FLUID_SUBSTITUTION_DISABLED, enabled -> this.send("substituteFluids", 0, Boolean.toString(enabled))
         )
      );
      this.fluidSubstitutions.setPosition(this.leftPos + 366, this.topPos + 22);
      this.fluidSubstitutions.setHalfSize(true);
      this.fluidSubstitutions.setDisableBackground(true);
      this.fluidSubstitutions.setWidth(8);
      this.fluidSubstitutions.setHeight(8);
      this.fluidSubstitutions.setTooltipOn(List.of(ButtonToolTips.FluidSubstitutions.text(), ButtonToolTips.FluidSubstitutionsDescEnabled.text()));
      this.fluidSubstitutions.setTooltipOff(List.of(ButtonToolTips.FluidSubstitutions.text(), ButtonToolTips.FluidSubstitutionsDescDisabled.text()));
      this.button(208, 34, 80, "cursor", () -> this.send("cursor", 0, ""));
      this.processing = this.button(292, 34, 84, "processing.off", () -> this.send("processing", 0, ""));
      this.autoUpload = this.button(
         208, 52, 82, "upload.off", () -> this.send("autoUpload", 0, Boolean.toString(!((AutoPatternMenu)this.menu).view.getBoolean("autoUpload")))
      );
      this.uploadTarget = this.button(294, 52, 82, "upload.target", () -> {
         this.search.setFocused(false);
         this.draggingRows = false;
         this.providers = new ProviderSelectionView((AutoPatternMenu)this.menu, this::send, this::closeProviders, this.width, this.height);
         this.send("uploadTarget", 0, "");
      });
      this.recipe = this.button(208, 70, 82, "recipe", () -> this.send("recipe", 0, ""));
      this.external = this.button(294, 70, 82, "external", () -> this.send("external", 0, ""));
      this.variant = this.button(208, 88, 168, "variant", this.ingredients::request);
      this.button(8, 118, 89, "analyze", () -> {
         this.search.setValue("");
         this.send("analyze", 0, "");
      });
      this.start = this.button(101, 118, 89, "start", () -> this.send("start", 0, ""));
      this.pause = this.button(194, 118, 89, "pause", () -> this.send("pause", 0, ""));
      this.button(287, 118, 89, "cancel", () -> this.send("cancel", 0, ""));
   }

   protected void containerTick() {
      super.containerTick();
      String state = ((AutoPatternMenu)this.menu).view.getString("state");
      int revision = ((AutoPatternMenu)this.menu).view.getInt("revision");
      if (this.pendingSelectionRevision != revision) {
         this.pendingSelection.clear();
         this.pendingSelectionRevision = revision;
      }

      this.pendingTab
         .setMessage(
            (Component)(this.showPendingInline
               ? this.tr("dependencies")
               : Component.translatable("gui.aeautopatterns.pending.count", new Object[]{((AutoPatternMenu)this.menu).view.getInt("pendingCount")}))
         );
      int[] pendingIds = ((AutoPatternMenu)this.menu).view.getIntArray("pendingIds");
      boolean allSelected = pendingIds.length > 0 && Arrays.stream(pendingIds).allMatch(this.pendingSelection::contains);
      this.pendingSelectAll.setMessage(this.tr(allSelected ? "pending.clear" : "pending.select_all"));
      this.pendingSelectAll.active = pendingIds.length > 0;
      this.pendingMakeExternal.active = !this.pendingSelection.isEmpty();
      this.treeButton.active = ((AutoPatternMenu)this.menu).view.getBoolean("treeReady");
      this.ignoreComponents
         .setMessage(this.tr(((AutoPatternMenu)this.menu).view.getBoolean("ignoreTargetComponents") ? "ignore_components.on" : "ignore_components.off"));
      this.processing.setMessage(this.tr(((AutoPatternMenu)this.menu).view.getBoolean("allowProcessing") ? "processing.on" : "processing.off"));
      this.autoUpload.setMessage(this.tr(((AutoPatternMenu)this.menu).view.getBoolean("autoUpload") ? "upload.on" : "upload.off"));
      this.uploadTarget.setMessage(this.tr("upload.target"));
      this.uploadTarget.active = true;
      this.start.active = state.equals("READY") || state.equals("PAUSED");
      this.pause.active = Set.of("RUNNING", "NO_BLANKS", "FULL", "OFFLINE", "VALIDATING").contains(state);
      this.recipe.active = ((AutoPatternMenu)this.menu).view.getInt("recipes") > 1;
      this.variant.active = ((AutoPatternMenu)this.menu).view.getBoolean("canEditIngredients");
      this.external.active = ((AutoPatternMenu)this.menu).view.contains("selected");
   }

   private void text(GuiGraphics g, String value, int x, int y, int color, int width) {
      g.drawString(this.font, this.font.plainSubstrByWidth(value, width), this.leftPos + x, this.topPos + y, color, false);
   }

   private void panel(GuiGraphics g, int x, int y, int w, int h, int fill, boolean inset) {
      TerminalStyle.bevel(g, this.leftPos + x, this.topPos + y, w, h, fill, inset);
   }

   private String rowStatus(CompoundTag row) {
      String kind = row.getString("kind");
      if (kind.equals("PENDING") && ((AutoPatternMenu)this.menu).view.getString("state").equals("ERROR")) {
         String material = ((AutoPatternMenu)this.menu).view.getString("issueMaterial");
         return material.isEmpty()
            ? this.tr("kind.ISSUE").getString()
            : Component.translatable("gui.aeautopatterns.issue.at", new Object[]{material}).getString();
      } else {
         return this.tr("kind." + kind).getString();
      }
   }

   private List<Component> issueTooltip() {
      ArrayList<Component> lines = new ArrayList<>();
      if (!((AutoPatternMenu)this.menu).view.getString("issuePath").isEmpty()) {
         lines.add(Component.translatable("gui.aeautopatterns.issue.path", new Object[]{((AutoPatternMenu)this.menu).view.getString("issuePath")}));
         if (!((AutoPatternMenu)this.menu).view.getString("issueRecipe").isEmpty()) {
            lines.add(Component.translatable("gui.aeautopatterns.issue.recipe", new Object[]{((AutoPatternMenu)this.menu).view.getString("issueRecipe")}));
         }
      }

      String error = ((AutoPatternMenu)this.menu).view.getString("error");
      lines.add((Component)(error.startsWith("Cycle:") ? this.tr("issue.cycle") : Component.literal(error)));
      if (!((AutoPatternMenu)this.menu).view.getString("issueMaterial").isEmpty()) {
         lines.add(this.tr("issue.locate"));
      }

      return lines;
   }

   private void renderIssueTooltip(GuiGraphics g, List<Component> lines, int mx, int my) {
      ArrayList<FormattedCharSequence> wrapped = new ArrayList<>();

      for (Component line : lines) {
         wrapped.addAll(this.font.split(line, Math.max(80, Math.min(340, this.width - 20))));
      }

      g.renderTooltip(this.font, wrapped, mx, my);
   }

   protected void renderBg(GuiGraphics g, float partial, int mx, int my) {
      g.fill(this.leftPos - 1, this.topPos - 1, this.leftPos + this.imageWidth + 1, this.topPos + this.imageHeight + 1, -12237221);
      this.panel(g, 0, 0, this.imageWidth, this.imageHeight, -3355434, false);
      this.panel(g, 2, 2, this.imageWidth - 4, this.imageHeight - 4, -3355434, false);
      this.panel(g, 196, 4, 182, 17, -789505, true);
      if (this.pendingWidth > 0) {
         this.text(g, this.tr("dependencies").getString(), 8, 22, -13092531, 124);
      }

      this.text(g, this.tr("route").getString(), 208, 22, -13092531, 52);
      this.panel(g, 6, 32, 194, 76, -5328185, true);
      this.panel(g, 203, 32, 176, 84, -6709837, true);
      if (this.pendingInline()) {
         this.renderPendingRows(g, 8, 51, 190, 3, 18, mx, my);
      } else if (!this.search.getValue().isBlank()) {
         for (int i = 0; i < Math.min(4, this.results.size() - this.searchOffset); i++) {
            int y = 34 + i * 18;
            this.panel(g, 8, y, 190, 18, -5328185, true);
            Item item = this.results.get(this.searchOffset + i);
            g.renderItem(item.getDefaultInstance(), this.leftPos + 12, this.topPos + y + 2);
            this.text(g, item.getDescription().getString(), 34, y + 6, -13092531, 158);
         }
      } else {
         ListTag rows = ((AutoPatternMenu)this.menu).view.getList("rows", 10);

         for (int i = 0; i < rows.size(); i++) {
            CompoundTag row = rows.getCompound(i);
            int y = 34 + i * 18;
            int indent = Math.min(4, row.getInt("depth")) * 7;
            boolean fluidHighlight = ((AutoPatternMenu)this.menu).view.getBoolean("substituteFluids")
               && this.fluidSubstitutions.isHovered()
               && Arrays.stream(((AutoPatternMenu)this.menu).view.getIntArray("fluidMaterials")).anyMatch(id -> id == row.getInt("id"));
            this.panel(g, 8, y, 190, 18, fluidHighlight ? -8732065 : (row.getBoolean("selected") ? -3879713 : -5328185), true);
            this.text(g, row.getBoolean("children") ? (row.getBoolean("collapsed") ? "+" : "−") : "·", 10 + indent, y + 6, -11315602, 9);
            ItemStack icon = ItemStack.parseOptional(this.minecraft.level.registryAccess(), row.getCompound("icon"));
            if (!icon.isEmpty()) {
               g.renderItem(icon, this.leftPos + 20 + indent, this.topPos + y + 2);
            }

            this.text(g, row.getString("name"), 39 + indent, y, -13092531, 153 - indent);
            this.text(g, this.rowStatus(row), 39 + indent, y + 9, this.kindColor(row.getString("kind")), 153 - indent);
         }
      }

      int count = this.pendingInline() ? 0 : (this.search.getValue().isBlank() ? ((AutoPatternMenu)this.menu).view.getInt("rowCount") : this.results.size());
      int offset = this.draggingRows
         ? this.rowDragOffset
         : (this.search.getValue().isBlank() ? ((AutoPatternMenu)this.menu).view.getInt("scrollOffset") : this.searchOffset);
      if (count > 4) {
         int thumb = Math.max(8, 288 / count);
         int y = 34 + (72 - thumb) * offset / (count - 4);
         g.fill(this.leftPos + 196, this.topPos + 34, this.leftPos + 199, this.topPos + 106, -9407348);
         g.fill(this.leftPos + 196, this.topPos + y, this.leftPos + 199, this.topPos + y + thumb, -789505);
      }

      if (((AutoPatternMenu)this.menu).view.getBoolean("inputFluid") && this.fluidSubstitutions.isHovered()) {
         g.fill(this.leftPos + 207, this.topPos + 105, this.leftPos + 377, this.topPos + 114, -8732065);
      }

      if (((AutoPatternMenu)this.menu).view.getInt("ingredients") > 0) {
         this.text(
            g,
            Component.translatable("gui.aeautopatterns.ingredient.count", new Object[]{((AutoPatternMenu)this.menu).view.getInt("ingredients")}).getString(),
            208,
            106,
            -13092531,
            168
         );
      }

      String state = ((AutoPatternMenu)this.menu).view.getString("state");
      String progress = state.equals("ANALYZING")
         ? this.tr("state." + state).getString()
            + " · "
            + this.tr("phase." + ((AutoPatternMenu)this.menu).view.getString("analysisPhase")).getString()
            + " · "
            + ((AutoPatternMenu)this.menu).view.getInt("analysisMaterials")
         : (
            state.equals("VALIDATING")
               ? this.tr("state." + state).getString() + " · " + ((AutoPatternMenu)this.menu).view.getInt("analysisMaterials")
               : (
                  state.equals("INDEXING")
                     ? this.tr("state." + state).getString()
                     : this.tr("state." + state).getString()
                        + "  "
                        + ((AutoPatternMenu)this.menu).view.getInt("done")
                        + "/"
                        + ((AutoPatternMenu)this.menu).view.getInt("total")
               )
         );
      this.text(g, progress, 8, 140, -13092531, 282);
      int total = ((AutoPatternMenu)this.menu).view.getInt("total");
      int done = ((AutoPatternMenu)this.menu).view.getInt("done");
      this.panel(g, 298, 141, 78, 7, -9407348, true);
      if (total > 0) {
         g.fill(this.leftPos + 299, this.topPos + 142, this.leftPos + 299 + Math.min(76, 76 * done / total), this.topPos + 147, -8800084);
      }

      this.text(g, this.tr("outputs").getString(), 8, 152, -13092531, 160);
      this.text(g, this.tr("blanks").getString(), 174, 152, -13092531, 42);
      this.text(g, this.tr("inventory").getString(), 216, 152, -13092531, 80);

      for (Slot slot : ((AutoPatternMenu)this.menu).slots) {
         if (slot.isActive()) this.panel(g, slot.x - 1, slot.y - 1, 18, 18, -5328185, true);
      }
      int outputRows = AutoPatternTerminalPart.OUTPUT_PATTERN_SLOTS / 9;
      int thumbHeight = Math.max(8, 54 * AutoPatternMenu.OUTPUT_VISIBLE_ROWS / outputRows);
      int thumbY = 162 + (54 - thumbHeight) * ((AutoPatternMenu)this.menu).outputScrollOffset()
            / (outputRows - AutoPatternMenu.OUTPUT_VISIBLE_ROWS);
      g.fill(this.leftPos + 173, this.topPos + 162, this.leftPos + 176, this.topPos + 216, -9407348);
      g.fill(this.leftPos + 173, this.topPos + thumbY, this.leftPos + 176, this.topPos + thumbY + thumbHeight, -789505);

      this.text(g, this.tr("required").getString(), 174, 203, -11315602, 42);
      this.text(g, Integer.toString(((AutoPatternMenu)this.menu).view.getInt("GENERATE")), 184, 215, -13092531, 30);
      this.text(
         g,
         this.tr("summary").getString()
            + " "
            + ((AutoPatternMenu)this.menu).view.getInt("GENERATE")
            + " / "
            + ((AutoPatternMenu)this.menu).view.getInt("NETWORK")
            + " / "
            + ((AutoPatternMenu)this.menu).view.getInt("BUFFER")
            + " / "
            + ((AutoPatternMenu)this.menu).view.getInt("EXTERNAL")
            + " / "
            + ((AutoPatternMenu)this.menu).view.getInt("UNSUPPORTED"),
         8,
         240,
         -13092531,
         370
      );
      this.text(
         g,
         this.tr("stock").getString()
            + " "
            + ((AutoPatternMenu)this.menu).view.getLong("stored")
            + "  "
            + this.tr("kind.NEEDS_RECIPE").getString()
            + ": "
            + ((AutoPatternMenu)this.menu).view.getInt("NEEDS_RECIPE"),
         8,
         250,
         -11315602,
         370
      );
      String error = ((AutoPatternMenu)this.menu).view.getString("error");
      if (!error.isEmpty()) {
         this.text(
            g,
            ((AutoPatternMenu)this.menu).view.getString("issueMaterial").isEmpty()
               ? error
               : Component.translatable("gui.aeautopatterns.issue.at", new Object[]{((AutoPatternMenu)this.menu).view.getString("issueMaterial")}).getString()
                  + " · "
                  + this.tr("issue.locate").getString(),
            8,
            260,
            -7524294,
            370
         );
      }

      if (this.pendingWidth > 0) {
         int x = -this.pendingWidth - 6;
         this.panel(g, x, 20, this.pendingWidth, 218, -3355434, false);
         this.text(g, this.tr("pending.title").getString(), x + 6, 27, -13092531, this.pendingWidth - 12);
         this.text(
            g,
            Component.translatable("gui.aeautopatterns.pending.count", new Object[]{((AutoPatternMenu)this.menu).view.getInt("pendingCount")}).getString(),
            x + 6,
            39,
            -11315602,
            this.pendingWidth - 12
         );
         this.renderPendingRows(g, x + 4, 66, this.pendingWidth - 8, 8, 21, mx, my);
      }
   }

   private boolean pendingInline() {
      return this.pendingWidth == 0 && this.showPendingInline && this.search.getValue().isBlank();
   }

   private void renderPendingRows(GuiGraphics g, int x, int y, int w, int visible, int rowHeight, int mx, int my) {
      ListTag rows = ((AutoPatternMenu)this.menu).view.getList("pendingRows", 10);
      this.panel(g, x, y, w, visible * rowHeight, -5328185, true);

      for (int i = 0; i < Math.min(visible, rows.size()); i++) {
         CompoundTag row = rows.getCompound(i);
         int ry = y + i * rowHeight;
         boolean hovered = mx >= this.leftPos + x && mx < this.leftPos + x + w - 4 && my >= this.topPos + ry && my < this.topPos + ry + rowHeight;
         int fill = row.getBoolean("selected") ? -3879713 : (hovered ? -3946536 : -5328185);
         if (row.getBoolean("unresolved") && !row.getBoolean("selected")) {
            fill = hovered ? -2115150 : -2839900;
         }

         this.panel(g, x, ry, w - 4, rowHeight, fill, true);
         int id = row.getInt("id");
         boolean checked = this.pendingSelection.contains(id);
         int boxY = ry + (rowHeight - 10) / 2;
         this.panel(g, x + 1, boxY, 10, 10, checked ? -7691110 : -6709837, true);
         if (checked) {
            g.fill(this.leftPos + x + 3, this.topPos + boxY + 4, this.leftPos + x + 5, this.topPos + boxY + 7, -13092531);
            g.fill(this.leftPos + x + 5, this.topPos + boxY + 2, this.leftPos + x + 9, this.topPos + boxY + 5, -13092531);
         }

         ItemStack icon = ItemStack.parseOptional(this.minecraft.level.registryAccess(), row.getCompound("icon"));
         if (!icon.isEmpty()) {
            g.renderItem(icon, this.leftPos + x + 14, this.topPos + ry + (rowHeight - 16) / 2);
         }

         if (rowHeight < 21) {
            this.text(g, row.getString("name"), x + 32, ry + 5, -13092531, w - 40);
         } else {
            List<FormattedCharSequence> lines = this.font.split(Component.literal(row.getString("name")), w - 40);

            for (int line = 0; line < Math.min(1, lines.size()); line++) {
               g.drawString(this.font, lines.get(line), this.leftPos + x + 32, this.topPos + ry + 2 + line * 9, -13092531, false);
            }

            String status = row.getBoolean("unresolved")
               ? this.tr("pending.unresolved").getString()
               : (row.getBoolean("needsRecipe") ? this.tr("pending.needs_recipe").getString() : "");
            if (!status.isEmpty()) {
               g.drawString(
                  this.font,
                  this.font.plainSubstrByWidth(status, w - 40),
                  this.leftPos + x + 32,
                  this.topPos + ry + 12,
                  row.getBoolean("unresolved") ? -7524294 : -6134766,
                  false
               );
            }
         }
      }

      if (rows.isEmpty()) {
         this.text(g, this.tr("pending.empty").getString(), x + 5, y + 7, -11315602, w - 10);
      }

      int count = ((AutoPatternMenu)this.menu).view.getInt("pendingCount");
      int offset = ((AutoPatternMenu)this.menu).view.getInt("pendingOffset");
      if (count > visible) {
         int height = visible * rowHeight;
         int thumb = Math.max(8, height * visible / count);
         int top = y + (height - thumb) * offset / (count - visible);
         g.fill(this.leftPos + x + w - 3, this.topPos + y, this.leftPos + x + w, this.topPos + y + height, -9407348);
         g.fill(this.leftPos + x + w - 3, this.topPos + top, this.leftPos + x + w, this.topPos + top + thumb, -789505);
      }
   }

   private int pendingRowAt(double mx, double my) {
      int x = this.pendingWidth > 0 ? -this.pendingWidth - 2 : 8;
      int y = this.pendingWidth > 0 ? 66 : 51;
      int w = this.pendingWidth > 0 ? this.pendingWidth - 8 : 190;
      int h = this.pendingWidth > 0 ? 21 : 18;
      int visible = this.pendingWidth > 0 ? 8 : 3;
      if (this.pendingWidth == 0 && !this.pendingInline()) {
         return -1;
      } else {
         return !(mx < (double)(this.leftPos + x))
               && !(mx >= (double)(this.leftPos + x + w))
               && !(my < (double)(this.topPos + y))
               && !(my >= (double)(this.topPos + y + visible * h))
            ? ((int)my - this.topPos - y) / h
            : -1;
      }
   }

   private boolean pendingCheckboxAt(double mx, double my) {
      int x = this.pendingWidth > 0 ? -this.pendingWidth - 1 : 9;
      int y = this.pendingWidth > 0 ? 66 : 51;
      int h = this.pendingWidth > 0 ? 21 : 18;
      int visible = this.pendingWidth > 0 ? 8 : 3;
      return (this.pendingWidth > 0 || this.pendingInline())
         && mx >= (double)(this.leftPos + x)
         && mx < (double)(this.leftPos + x + 12)
         && my >= (double)(this.topPos + y)
         && my < (double)(this.topPos + y + visible * h);
   }

   private void toggleAllPending() {
      int[] ids = ((AutoPatternMenu)this.menu).view.getIntArray("pendingIds");
      boolean all = ids.length > 0 && Arrays.stream(ids).allMatch(this.pendingSelection::contains);
      if (all) {
         for (int id : ids) {
            this.pendingSelection.remove(id);
         }
      } else {
         for (int id : ids) {
            if (id >= 0) {
               this.pendingSelection.add(id);
            }
         }
      }
   }

   private void sendBulkExternal() {
      if (!this.pendingSelection.isEmpty()) {
         PacketDistributor.sendToServer(
            new TerminalPackets.BulkExternal(
               ((AutoPatternMenu)this.menu).containerId, ((AutoPatternMenu)this.menu).view.getInt("revision"), this.pendingSelection.stream().sorted().toList()
            ),
            new CustomPacketPayload[0]
         );
         this.pendingSelection.clear();
      }
   }

   private int kindColor(String kind) {
      return switch (kind) {
         case "NETWORK", "BUFFER", "GENERATED" -> -14394553;
         case "EXTERNAL" -> -9350622;
         case "UNSUPPORTED" -> -7524294;
         case "NEEDS_RECIPE" -> -6134766;
         case "PENDING" -> ((AutoPatternMenu)this.menu).view.getString("state").equals("ERROR") ? -7524294 : -11315602;
         default -> -13283974;
      };
   }

   protected void renderLabels(GuiGraphics g, int mx, int my) {
      g.drawString(this.font, this.title, 8, 10, -13092531, false);
   }

   public void render(GuiGraphics g, int mx, int my, float partial) {
      if (this.ingredients.open()) {
         this.renderTerminal(g, -1, -1, partial);
         this.ingredients.render(g, mx, my, partial, this.width, this.height);
      } else {
         this.renderTerminal(g, mx, my, partial);
      }
   }

   private void renderTerminal(GuiGraphics g, int mx, int my, float partial) {
      if (this.providers != null) {
         this.providers.render(g, mx, my, partial);
      } else {
         boolean showBulk = this.tree == null && (this.pendingWidth > 0 || this.pendingInline());
         this.pendingSelectAll.visible = showBulk;
         this.pendingMakeExternal.visible = showBulk;
         if (this.tree != null) {
            this.tree.render(g, mx, my, partial);
            if (this.picker.open()) {
               this.picker.render(g, mx, my, partial, this.width, this.height);
            }
         } else {
            this.substitutions.setState(((AutoPatternMenu)this.menu).view.getBoolean("substitute"));
            this.fluidSubstitutions.setState(((AutoPatternMenu)this.menu).view.getBoolean("substituteFluids"));
            super.render(g, mx, my, partial);
            this.renderTooltip(g, mx, my);
            int pendingRow = this.pendingRowAt((double)mx, (double)my);
            ListTag pending = ((AutoPatternMenu)this.menu).view.getList("pendingRows", 10);
            if (pendingRow >= 0 && pendingRow < pending.size()) {
               CompoundTag row = pending.getCompound(pendingRow);
               ArrayList<Component> details = new ArrayList<>();
               details.add(Component.literal(row.getString("name")));
               details.add(row.getBoolean("unresolved") ? this.tr("pending.unresolved_help") : this.tr("pending.help"));
               if (row.getBoolean("unresolved") && !row.getString("path").isEmpty()) {
                  details.add(Component.translatable("gui.aeautopatterns.issue.path", new Object[]{row.getString("path")}));
               }

               if (row.getBoolean("unresolved") && !row.getString("recipe").isEmpty()) {
                  details.add(Component.translatable("gui.aeautopatterns.issue.recipe", new Object[]{row.getString("recipe")}));
               }

               g.renderTooltip(this.font, details, Optional.empty(), mx, my);
            }

            if (mx >= this.leftPos + 8 && mx < this.leftPos + 377 && my >= this.topPos + 138 && my < this.topPos + 150) {
               ArrayList<Component> detailsx = new ArrayList<>(
                  List.of(
                     Component.translatable(
                        "gui.aeautopatterns.analysis.graph",
                        new Object[]{
                           ((AutoPatternMenu)this.menu).view.getInt("analysisMaterials"),
                           ((AutoPatternMenu)this.menu).view.getLong("analysisEdges"),
                           ((AutoPatternMenu)this.menu).view.getInt("analysisComponents")
                        }
                     ),
                     Component.translatable(
                        "gui.aeautopatterns.analysis.work",
                        new Object[]{
                           ((AutoPatternMenu)this.menu).view.getLong("analysisAttempts"),
                           ((AutoPatternMenu)this.menu).view.getLong("analysisCacheHits"),
                           ((AutoPatternMenu)this.menu).view.getLong("analysisReused")
                        }
                     ),
                     Component.translatable(
                        "gui.aeautopatterns.analysis.time",
                        new Object[]{
                           ((AutoPatternMenu)this.menu).view.getInt("analysisMillis"), ((AutoPatternMenu)this.menu).view.getInt("analysisJeiPending")
                        }
                     )
                  )
               );
               String diagnostics = ((AutoPatternMenu)this.menu).view.getString("analysisAdapterDiagnostics");
               if (!diagnostics.isEmpty()) {
                  detailsx.add(Component.literal(diagnostics));
               }

               g.renderTooltip(this.font, detailsx, Optional.empty(), mx, my);
            }

            if (this.substitutions.isHovered()) {
               g.renderTooltip(this.font, this.substitutions.getTooltipMessage(), Optional.empty(), mx, my);
            }

            if (this.fluidSubstitutions.isHovered()) {
               g.renderTooltip(this.font, this.fluidSubstitutions.getTooltipMessage(), Optional.empty(), mx, my);
            }

            if (this.processing.isHovered()) {
               g.renderTooltip(this.font, List.of(this.tr("processing.help"), this.tr("processing.fuel")), Optional.empty(), mx, my);
            }

            if (this.ignoreComponents.isHovered()) {
               this.renderIssueTooltip(g, List.of(this.tr("ignore_components.help"), this.tr("ignore_components.trim")), mx, my);
            }

            if (this.autoUpload.isHovered()) {
               g.renderTooltip(this.font, List.of(this.tr("upload.help")), Optional.empty(), mx, my);
            }

            if (this.uploadTarget.isHovered()) {
               g.renderTooltip(
                  this.font,
                  List.of(
                     Component.translatable(
                        "gui.aeautopatterns.providers.summary",
                        new Object[]{
                           ((AutoPatternMenu)this.menu).view.getInt("selectedProviderCount"),
                           ((AutoPatternMenu)this.menu).view.getInt("selectedOnlineProviders")
                        }
                     ),
                     Component.translatable(
                        "gui.aeautopatterns.providers.free_total", new Object[]{((AutoPatternMenu)this.menu).view.getInt("uploadFreeSlots")}
                     ),
                     Component.literal(((AutoPatternMenu)this.menu).view.getString("uploadStatus"))
                  ),
                  Optional.empty(),
                  mx,
                  my
               );
            }

            if (this.search.isHovered() && JeiCompat.available()) {
               g.renderTooltip(this.font, this.tr("search.jei_drop"), mx, my);
            }

            if (mx >= this.leftPos + 8
               && mx < this.leftPos + 378
               && my >= this.topPos + 259
               && my < this.topPos + 270
               && !((AutoPatternMenu)this.menu).view.getString("error").isEmpty()) {
               this.renderIssueTooltip(g, this.issueTooltip(), mx, my);
            }

            if (!this.pendingInline()
               && this.search.getValue().isBlank()
               && mx >= this.leftPos + 8
               && mx < this.leftPos + 198
               && my >= this.topPos + 34
               && my < this.topPos + 106) {
               int rowx = (my - this.topPos - 34) / 18;
               ListTag rows = ((AutoPatternMenu)this.menu).view.getList("rows", 10);
               if (rowx < rows.size()) {
                  CompoundTag r = rows.getCompound(rowx);
                  ArrayList<Component> detailsx = new ArrayList<>();
                  detailsx.add(Component.literal(r.getString("name") + " — " + this.rowStatus(r)));
                  if (((AutoPatternMenu)this.menu).view.getString("state").equals("ERROR")) {
                     detailsx.addAll(this.issueTooltip());
                  }

                  this.renderIssueTooltip(g, detailsx, mx, my);
               }
            }

            if (this.recipe.isHovered() && !((AutoPatternMenu)this.menu).view.getString("recipe").isEmpty()) {
               ArrayList<Component> detailsx = new ArrayList<>();
               detailsx.add(Component.literal(((AutoPatternMenu)this.menu).view.getString("recipe")));
               if (!((AutoPatternMenu)this.menu).view.getString("adapterId").isEmpty()) {
                  detailsx.add(Component.literal("Adapter: " + ((AutoPatternMenu)this.menu).view.getString("adapterId")));
               }

               if (!((AutoPatternMenu)this.menu).view.getString("machineType").isEmpty()) {
                  detailsx.add(Component.literal("Machine: " + ((AutoPatternMenu)this.menu).view.getString("machineType")));
               }

               if (!((AutoPatternMenu)this.menu).view.getString("conditions").isEmpty()) {
                  detailsx.add(Component.literal(((AutoPatternMenu)this.menu).view.getString("conditions")));
               }

               if (((AutoPatternMenu)this.menu).view.getInt("probabilisticOutputs") > 0) {
                  detailsx.add(Component.literal("Probabilistic outputs: " + ((AutoPatternMenu)this.menu).view.getInt("probabilisticOutputs")));
               }

               g.renderTooltip(this.font, detailsx.stream().map(Component::getVisualOrderText).toList(), mx, my);
            }

            if (this.picker.open()) {
               this.picker.render(g, mx, my, partial, this.width, this.height);
            }
         }
      }
   }

   public boolean mouseClicked(double mx, double my, int button) {
      if (this.providers != null) {
         return this.providers.click(mx, my, button);
      } else if (this.ingredients.open()) {
         return this.ingredients.click(mx, my, button);
      } else if (this.picker.open()) {
         return this.picker.click(mx, my, button);
      } else if (this.tree != null) {
         return this.tree.mouseClicked(mx, my, button);
      } else {
         int scrollCount = this.search.getValue().isBlank() ? ((AutoPatternMenu)this.menu).view.getInt("rowCount") : this.results.size();
         if (button == 0
            && !this.pendingInline()
            && scrollCount > 4
            && mx >= (double)(this.leftPos + 194)
            && mx < (double)(this.leftPos + 200)
            && my >= (double)(this.topPos + 34)
            && my < (double)(this.topPos + 106)) {
            int thumb = Math.max(8, 288 / scrollCount);
            int offset = this.search.getValue().isBlank() ? ((AutoPatternMenu)this.menu).view.getInt("scrollOffset") : this.searchOffset;
            int thumbY = this.topPos + 34 + (72 - thumb) * offset / (scrollCount - 4);
            this.rowDragGrab = my >= (double)thumbY && my < (double)(thumbY + thumb) ? my - (double)thumbY : (double)thumb / 2.0;
            this.draggingRows = true;
            this.draggingSearch = !this.search.getValue().isBlank();
            this.rowDragOffset = offset;
            this.rowDragRevision = ((AutoPatternMenu)this.menu).view.getInt("revision");
            this.search.setFocused(false);
            this.dragRowsTo(my);
            return true;
         } else if (this.pendingWidth <= 0 && !this.pendingInline()
            || !this.pendingSelectAll.mouseClicked(mx, my, button) && !this.pendingMakeExternal.mouseClicked(mx, my, button)) {
            int pendingRow = this.pendingRowAt(mx, my);
            if (button == 0 && pendingRow >= 0) {
               ListTag rows = ((AutoPatternMenu)this.menu).view.getList("pendingRows", 10);
               if (pendingRow < rows.size()) {
                  CompoundTag row = rows.getCompound(pendingRow);
                  if (this.pendingCheckboxAt(mx, my)) {
                     int id = row.getInt("id");
                     if (!this.pendingSelection.remove(id)) {
                        this.pendingSelection.add(id);
                     }
                  } else {
                     this.send(row.getBoolean("unresolved") ? "locate_material" : "select", row.getInt("id"), "");
                  }
               }

               return true;
            } else if (button == 0
               && mx >= (double)(this.leftPos + 8)
               && mx < (double)(this.leftPos + 378)
               && my >= (double)(this.topPos + 259)
               && my < (double)(this.topPos + 270)
               && !((AutoPatternMenu)this.menu).view.getString("issueMaterial").isEmpty()) {
               this.send("issue", 0, "");
               return true;
            } else {
               if (button == 0
                  && mx >= (double)(this.leftPos + 8)
                  && mx < (double)(this.leftPos + 198)
                  && my >= (double)(this.topPos + 34)
                  && my < (double)(this.topPos + 106)) {
                  int row = ((int)my - this.topPos - 34) / 18;
                  if (!this.search.getValue().isBlank()) {
                     if (this.searchOffset + row < this.results.size()) {
                        this.send("target", 0, BuiltInRegistries.ITEM.getKey(this.results.get(this.searchOffset + row)).toString());
                        this.search.setValue("");
                     }

                     return true;
                  }

                  ListTag rows = ((AutoPatternMenu)this.menu).view.getList("rows", 10);
                  if (row < rows.size()) {
                     CompoundTag r = rows.getCompound(row);
                     this.send(mx < (double)(this.leftPos + 20 + Math.min(4, r.getInt("depth")) * 7) ? "expand" : "select", r.getInt("id"), "");
                     return true;
                  }
               }

               return super.mouseClicked(mx, my, button);
            }
         } else {
            return true;
         }
      }
   }

   public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
      if (this.providers != null) {
         return this.providers.scroll(vertical);
      } else if (this.ingredients.open() || this.picker.open()) {
         return true;
      } else if (this.tree != null) {
         return this.tree.mouseScrolled(mx, my, vertical);
      } else if (mx >= (double)(this.leftPos + 8) && mx < (double)(this.leftPos + 170)
            && my >= (double)(this.topPos + 162) && my < (double)(this.topPos + 216)) {
         if (vertical != 0.0) {
            int delta = vertical > 0.0 ? -1 : 1;
            ((AutoPatternMenu)this.menu).setOutputScrollOffset(((AutoPatternMenu)this.menu).outputScrollOffset() + delta);
            this.send("output_scroll", delta, "");
         }
         return true;
      } else if (this.pendingRowAt(mx, my) >= 0) {
         if (vertical != 0.0) {
            this.send("pending_scroll", vertical > 0.0 ? -1 : 1, this.pendingWidth == 0 ? "compact" : "wide");
         }

         return true;
      } else if (mx >= (double)(this.leftPos + 8) && mx < (double)(this.leftPos + 198) && my >= (double)(this.topPos + 34) && my < (double)(this.topPos + 106)) {
         if (vertical != 0.0) {
            if (this.search.getValue().isBlank()) {
               this.send("scroll", vertical > 0.0 ? -1 : 1, "");
            } else {
               this.searchOffset = Math.max(0, Math.min(Math.max(0, this.results.size() - 4), this.searchOffset + (vertical > 0.0 ? -1 : 1)));
            }
         }

         return true;
      } else {
         return super.mouseScrolled(mx, my, horizontal, vertical);
      }
   }

   protected boolean hasClickedOutside(double mx, double my, int x, int y, int button) {
      return this.pendingWidth > 0
            && mx >= (double)(this.leftPos - this.pendingWidth - 6)
            && mx < (double)(this.leftPos - 6)
            && my >= (double)(this.topPos + 20)
            && my < (double)(this.topPos + 238)
         ? false
         : super.hasClickedOutside(mx, my, x, y, button);
   }

   private void closeTree() {
      this.tree = null;
      this.send("tree_close", 0, "");
   }

   private void closeProviders() {
      this.providers = null;
      this.send("providers_close", 0, "");
   }

   public void onClose() {
      if (this.ingredients.open()) {
         this.ingredients.close();
      } else if (this.providers != null) {
         this.closeProviders();
      } else if (this.picker.open()) {
         this.picker.close();
      } else if (this.tree != null) {
         this.closeTree();
      } else {
         super.onClose();
      }
   }

   public boolean charTyped(char character, int mods) {
      if (this.providers != null) {
         return this.providers.character(character, mods);
      } else if (this.ingredients.open()) {
         this.ingredients.character(character, mods);
         return true;
      } else if (this.picker.open()) {
         return true;
      } else {
         return this.tree != null ? this.tree.charTyped(character, mods) : super.charTyped(character, mods);
      }
   }

   public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
      if (this.providers != null) {
         return true;
      } else if (this.draggingRows && button == 0) {
         this.dragRowsTo(my);
         return true;
      } else if (!this.ingredients.open() && !this.picker.open()) {
         return this.tree != null ? this.tree.mouseDragged(dx, dy) : super.mouseDragged(mx, my, button, dx, dy);
      } else {
         return true;
      }
   }

   public boolean mouseReleased(double mx, double my, int button) {
      if (this.providers != null) {
         return true;
      } else if (this.draggingRows && button == 0) {
         this.dragRowsTo(my);
         this.draggingRows = false;
         return true;
      } else if (!this.ingredients.open() && !this.picker.open()) {
         return this.tree != null ? this.tree.mouseReleased() : super.mouseReleased(mx, my, button);
      } else {
         return true;
      }
   }

   private void dragRowsTo(double mouseY) {
      if (this.tree == null
         && !this.ingredients.open()
         && !this.picker.open()
         && !this.pendingInline()
         && this.draggingSearch == !this.search.getValue().isBlank()
         && this.rowDragRevision == ((AutoPatternMenu)this.menu).view.getInt("revision")) {
         int count = this.draggingSearch ? this.results.size() : ((AutoPatternMenu)this.menu).view.getInt("rowCount");
         int max = Math.max(0, count - 4);
         if (max == 0) {
            this.draggingRows = false;
         } else {
            int thumb = Math.max(8, 288 / count);
            double fraction = (mouseY - (double)this.topPos - 34.0 - this.rowDragGrab) / (double)(72 - thumb);
            int offset = (int)Math.round(Math.max(0.0, Math.min(1.0, fraction)) * (double)max);
            if (offset != this.rowDragOffset) {
               this.rowDragOffset = offset;
               if (this.draggingSearch) {
                  this.searchOffset = offset;
               } else {
                  this.send("scroll_to", offset, "");
               }
            }
         }
      } else {
         this.draggingRows = false;
      }
   }

   public boolean keyPressed(int key, int scan, int mods) {
      if (this.providers != null) {
         this.providers.key(key, scan, mods);
         return true;
      } else if (this.ingredients.open()) {
         this.ingredients.key(key, scan, mods);
         return true;
      } else if (this.picker.open()) {
         if (key == 256) {
            this.picker.close();
         }

         return true;
      } else if (this.tree != null) {
         if (key == 256) {
            if (!this.tree.dismissSelection()) {
               this.closeTree();
            }

            return true;
         } else {
            return this.tree.keyPressed(key, scan, mods);
         }
      } else {
         return this.search.isFocused() && key != 256
            ? this.search.keyPressed(key, scan, mods) || this.search.canConsumeInput()
            : super.keyPressed(key, scan, mods);
      }
   }
}
