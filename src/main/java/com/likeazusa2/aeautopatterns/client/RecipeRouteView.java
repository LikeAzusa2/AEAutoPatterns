package com.likeazusa2.aeautopatterns.client;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import com.likeazusa2.aeautopatterns.menu.AutoPatternMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import static com.likeazusa2.aeautopatterns.client.TerminalStyle.*;

/** Native route-group editor layered over the provider selection view. */
final class RecipeRouteView {
    private static final int GROUP_ROWS = 7;
    private static final int PAGE_ROWS = 6;
    private static final int ROW_HEIGHT = 18;
    private static final String ROOT = "gui.aeautopatterns.routes.";

    private enum Dialog { NONE, CREATE, RENAME, DELETE }

    private record Scope(String value, String label) {}

    private static final List<Scope> SCOPES = List.of(
        new Scope("plan", "scope.plan"),
        new Scope("all", "scope.all"),
        new Scope("bound", "scope.bound")
    );

    private final Minecraft minecraft = Minecraft.getInstance();
    private final AutoPatternMenu menu;
    private final RecipeSelectionView.Action action;
    private final Runnable close;
    private final List<TerminalButton> buttons = new ArrayList<>();
    private final EditBox search;
    private EditBox dialogInput;
    private Dialog dialog = Dialog.NONE;
    private boolean planView;
    private boolean queryInitialized;
    private int x, y, width, height, panelWidth, panelHeight, leftWidth;
    private double lastMouseX, lastMouseY;
    private List<Component> tooltip;

    RecipeRouteView(AutoPatternMenu menu, RecipeSelectionView.Action action, Runnable close, int width, int height) {
        this.menu = menu;
        this.action = action;
        this.close = close;
        search = new EditBox(minecraft.font, 0, 0, 120, 14, tr("search.recipes"));
        search.setBordered(false);
        search.setTextColor(TEXT);
        search.setTextColorUneditable(MUTED);
        search.setMaxLength(80);
        search.setResponder(value -> action.send("route_search", 0, value));
        resize(width, height);
    }

    void resize(int width, int height) {
        String query = search.getValue();
        this.width = width;
        this.height = height;
        panelWidth = Math.min(360, Math.max(240, width - 8));
        panelHeight = Math.min(252, Math.max(220, height - 8));
        x = (width - panelWidth) / 2;
        y = (height - panelHeight) / 2;
        leftWidth = Math.max(104, Math.min(124, panelWidth / 3));
        int rightX = rightX();
        search.setX(rightX);
        search.setY(y + 47);
        search.setWidth(Math.max(60, rightWidth()));
        search.setValue(query);
        if (dialogInput != null) positionDialogInput();
    }

    private CompoundTag route() {
        return menu.view.getCompound("route");
    }

    private int rightX() {
        return x + leftWidth + 16;
    }

    private int rightWidth() {
        return x + panelWidth - 8 - rightX();
    }

    private int listTop() {
        return y + 47;
    }

    private int rowBottom() {
        return listTop() + GROUP_ROWS * 20;
    }

    void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        CompoundTag route = route();
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        tooltip = null;
        buttons.clear();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 500);
        graphics.fill(0, 0, width, height, 0xb0000000);
        bevel(graphics, x, y, panelWidth, panelHeight, PANEL, false);

        renderHeader(graphics, route);
        renderLeftPane(graphics, route, mouseX, mouseY);
        renderContent(graphics, route, mouseX, mouseY, partial);
        renderStatus(graphics, route);
        renderFooterHints(graphics, route);

        for (var button : buttons) button.render(graphics, mouseX, mouseY, partial);
        if (dialog != Dialog.NONE) renderDialog(graphics, mouseX, mouseY, partial);
        if (tooltip != null && !tooltip.isEmpty()) {
            graphics.renderTooltip(minecraft.font, tooltip, Optional.empty(), mouseX, mouseY);
        }
        graphics.pose().popPose();
    }

    private void renderHeader(GuiGraphics graphics, CompoundTag route) {
        label(graphics, tr("title"), x + 10, y + 9, Math.max(70, leftWidth - 4), TEXT);
        String groupName = route.getString("groupName");
        if (!groupName.isBlank()) {
            label(graphics, Component.literal(groupName), rightX(), y + 9, Math.max(40, rightWidth() - 74), TEXT);
        } else {
            label(graphics, tr("group.none"), rightX(), y + 9, Math.max(40, rightWidth() - 74), MUTED);
        }
        var closeButton = button(x + panelWidth - 78, y + 6, 70, 16, tr("back.providers"), close);
        closeButton.setTooltip(Tooltip.create(tr("back.providers.help")));
    }

    private void renderLeftPane(GuiGraphics graphics, CompoundTag route, int mouseX, int mouseY) {
        int lx = x + 8;
        int half = (leftWidth - 2) / 2;
        var groups = button(lx, y + 28, half, 16, tr(planView ? "groups" : "groups.active"), () -> planView = false);
        var plan = button(lx + half + 2, y + 28, leftWidth - half - 2, 16,
            tr(planView ? "plan.active" : "plan"), () -> planView = true);
        groups.setTooltip(Tooltip.create(tr("groups.help")));
        plan.setTooltip(Tooltip.create(tr("plan.help")));

        bevel(graphics, lx, listTop(), leftWidth, GROUP_ROWS * 20, SLOT, true);
        if (planView) renderPreviewRows(graphics, route, lx, mouseX, mouseY);
        else renderGroupRows(graphics, route, lx, mouseX, mouseY);
    }

    private void renderGroupRows(GuiGraphics graphics, CompoundTag route, int lx, int mouseX, int mouseY) {
        var groups = route.getList("groups", Tag.TAG_COMPOUND);
        String activeId = route.getString("groupId");
        for (int i = 0; i < groups.size() && i < GROUP_ROWS; i++) {
            var group = groups.getCompound(i);
            int ry = listTop() + i * 20;
            boolean active = group.getString("id").equals(activeId);
            boolean hovered = inside(mouseX, mouseY, lx, ry, leftWidth, 20);
            bevel(graphics, lx + 1, ry + 1, leftWidth - 2, 18, active ? 0xffc5dfbd : hovered ? 0xffc8d0e3 : SLOT, true);
            label(graphics, Component.literal(group.getString("name")), lx + 5, ry + 2, leftWidth - 10, TEXT);
            label(graphics, tr("group.counts", group.getInt("recipes"), group.getInt("providers")),
                lx + 5, ry + 11, leftWidth - 10, MUTED);
            if (hovered) {
                tooltip = List.of(Component.literal(group.getString("name")),
                    tr("group.counts.full", group.getInt("recipes"), group.getInt("providers")),
                    tr("select.help"));
            }
            String groupId = group.getString("id");
            if (!groupId.isEmpty()) {
                invisibleButton(lx + 1, ry + 1, leftWidth - 2, 18,
                    () -> action.send("route_select", 0, groupId));
            }
        }
        if (groups.isEmpty()) label(graphics, tr("empty.groups"), lx + 6, listTop() + 8, leftWidth - 12, MUTED);

        int createWidth = 22;
        int actionWidth = Math.max(32, (leftWidth - createWidth - 4) / 2);
        int ay = rowBottom() + 4;
        var create = button(lx, ay, createWidth, 16, tr("create.short"), this::openCreate);
        create.setTooltip(Tooltip.create(tr("create.help")));
        var rename = button(lx + createWidth + 2, ay, actionWidth, 16, tr("rename.short"), this::openRename);
        rename.active = !activeId.isEmpty();
        rename.setTooltip(Tooltip.create(tr("rename.help")));
        var delete = button(lx + createWidth + actionWidth + 4, ay,
            leftWidth - createWidth - actionWidth - 4, 16, tr("delete.short"), this::openDelete);
        delete.active = !activeId.isEmpty();
        delete.setTooltip(Tooltip.create(tr("delete.help")));

        int count = Math.max(groups.size(), route.getInt("groupCount"));
        int offset = Math.max(0, route.getInt("groupOffset"));
        int pageY = ay + 19;
        var previous = button(lx, pageY, 20, 15, Component.literal("←"), () -> action.send("group_page", -1, ""));
        previous.active = offset > 0;
        var next = button(lx + leftWidth - 20, pageY, 20, 15, Component.literal("→"),
            () -> action.send("group_page", 1, ""));
        next.active = count > 0 && offset + Math.max(1, groups.size()) < count;
        String page = count == 0 ? "0 / 0" : (offset + 1) + "-" + (offset + groups.size()) + " / " + count;
        graphics.drawString(minecraft.font, page, lx + (leftWidth - 40 - minecraft.font.width(page)) / 2 + 20,
            pageY + 4, MUTED, false);
    }

    private void renderPreviewRows(GuiGraphics graphics, CompoundTag route, int lx, int mouseX, int mouseY) {
        var preview = route.getList("preview", Tag.TAG_COMPOUND);
        for (int i = 0; i < preview.size() && i < GROUP_ROWS; i++) {
            var entry = preview.getCompound(i);
            int ry = listTop() + i * 20;
            boolean hovered = inside(mouseX, mouseY, lx, ry, leftWidth, 20);
            bevel(graphics, lx + 1, ry + 1, leftWidth - 2, 18, hovered ? 0xffc8d0e3 : SLOT, true);
            label(graphics, tr("preview.name", entry.getString("name"), entry.getInt("count")),
                lx + 5, ry + 2, leftWidth - 10, TEXT);
            String destination = entry.getString("destination");
            String status = statusText(entry.getString("status"), entry.getString("fallback"));
            String bottom = destination.isBlank() ? status : destination + " · " + status;
            label(graphics, Component.literal(bottom), lx + 5, ry + 11, leftWidth - 10, MUTED);
            if (hovered) {
                tooltip = List.of(tr("preview.name", entry.getString("name"), entry.getInt("count")),
                    destination.isBlank() ? tr("preview.any_destination") : Component.literal(destination),
                    status.isBlank() ? tr("status.unknown") : Component.literal(status));
            }
        }
        if (preview.isEmpty()) label(graphics, tr("empty.plan"), lx + 6, listTop() + 8, leftWidth - 12, MUTED);
    }

    private void renderContent(GuiGraphics graphics, CompoundTag route, int mouseX, int mouseY, float partial) {
        int rx = rightX();
        int rw = rightWidth();
        String mode = route.getString("mode");
        boolean providers = mode.equals("providers");
        int tabWidth = (rw - 2) / 2;
        button(rx, y + 28, tabWidth, 16, tr(mode.equals("recipes") ? "recipes.active" : "recipes"),
            () -> action.send("route_tab", 0, "recipes"));
        button(rx + tabWidth + 2, y + 28, rw - tabWidth - 2, 16,
            tr(providers ? "providers.active" : "providers"), () -> action.send("route_tab", 0, "providers"));

        bevel(graphics, rx, y + 47, rw, 14, SLOT, true);
        if (!queryInitialized && route.contains("mode")) {
            search.setValue(route.getString("query"));
            queryInitialized = true;
        }
        search.setHint(tr(providers ? "search.providers" : "search.recipes"));
        search.render(graphics, mouseX, mouseY, partial);

        if (providers) renderProviderControls(graphics, route, rx, rw);
        else renderRecipeControls(graphics, route, rx, rw);

        int rows = providers ? renderProviderRows(graphics, route, rx, rw, mouseX, mouseY)
            : renderRecipeRows(graphics, route, rx, rw, mouseX, mouseY);
        if (rows == 0) {
            label(graphics, tr(providers ? "empty.providers" : "empty.recipes"),
                rx + 6, y + 88, rw - 12, MUTED);
        }
        renderPager(graphics, route, rx, rw, rows);
    }

    private void renderRecipeControls(GuiGraphics graphics, CompoundTag route, int rx, int rw) {
        int machineWidth = Math.max(66, rw * 44 / 100);
        int scopeWidth = Math.max(34, rw * 22 / 100);
        int batchWidth = rw - machineWidth - scopeWidth - 4;
        var machine = button(rx, y + 64, machineWidth, 14, tr("machine.label", machineFilter(route)), this::cycleMachine);
        machine.setTooltip(Tooltip.create(tr("machine.help")));
        var scope = button(rx + machineWidth + 2, y + 64, scopeWidth, 14, scopeLabel(route.getString("scope")), this::cycleScope);
        scope.setTooltip(Tooltip.create(tr("scope.help")));
        var batch = button(rx + machineWidth + scopeWidth + 4, y + 64, batchWidth, 14, tr("batch"), this::batchAdd);
        batch.active = !route.getString("batchToken").isBlank();
        batch.setTooltip(Tooltip.create(tr("batch.help")));
    }

    private void renderProviderControls(GuiGraphics graphics, CompoundTag route, int rx, int rw) {
        label(graphics, tr("provider.hint", route.getInt("count")), rx, y + 68, rw, MUTED);
    }

    private int renderRecipeRows(GuiGraphics graphics, CompoundTag route, int rx, int rw, int mouseX, int mouseY) {
        var rows = route.getList("rows", Tag.TAG_COMPOUND);
        int shown = Math.min(PAGE_ROWS, rows.size());
        for (int i = 0; i < shown; i++) {
            var row = rows.getCompound(i);
            int rowIndex = i;
            int ry = y + 80 + i * ROW_HEIGHT;
            boolean conflict = !row.getString("conflict").isBlank();
            boolean unavailable = row.getBoolean("unavailable");
            boolean hovered = inside(mouseX, mouseY, rx, ry, rw, ROW_HEIGHT);
            int fill = conflict ? 0xffdfc4b4 : row.getBoolean("selected") ? 0xffc5dfbd
                : unavailable ? 0xffc7b8b6 : hovered ? 0xffc8d0e3 : SLOT;
            bevel(graphics, rx, ry, rw, ROW_HEIGHT, fill, true);
            drawCheck(graphics, rx + 4, ry + 4, row.getBoolean("selected"), false, unavailable);
            drawStackIcon(graphics, firstStack(row, "outputs"), rx + 18, ry + 1);

            String machine = machineLabel(route, row);
            String name = row.getString("name");
            if (name.isBlank()) name = stackSummary(row.getList("outputs", Tag.TAG_COMPOUND));
            String io = stackSummary(row.getList("inputs", Tag.TAG_COMPOUND)) + " → "
                + stackSummary(row.getList("outputs", Tag.TAG_COMPOUND));
            int textWidth = rw - 62 - (conflict ? 34 : 0);
            label(graphics, Component.literal(name), rx + 37, ry, textWidth, TEXT);
            label(graphics, Component.literal(machine + " · " + io), rx + 37, ry + 9,
                Math.max(30, rw - 62 - (conflict ? 34 : 0)), unavailable ? 0xff8d303a : TEXT);

            if (conflict) {
                var move = button(rx + rw - 36, ry + 2, 32, 14, tr("move"),
                    () -> moveRecipe(route, rowIndex, row));
                move.setTooltip(Tooltip.create(tr("move.help", row.getString("conflict"))));
            }
            String token = row.getString("token");
            if (!token.isEmpty()) {
                invisibleButton(rx + 1, ry + 1, rw - (conflict ? 38 : 2), ROW_HEIGHT - 2,
                    () -> action.send("route_recipe", rowIndex, token));
            }
            if (hovered) {
                var lines = new ArrayList<Component>();
                lines.add(Component.literal(name));
                lines.add(tr("tooltip.machine", machine));
                lines.add(tr("tooltip.inputs", stackSummary(row.getList("inputs", Tag.TAG_COMPOUND))));
                lines.add(tr("tooltip.outputs", stackSummary(row.getList("outputs", Tag.TAG_COMPOUND))));
                if (conflict) lines.add(tr("conflict", row.getString("conflict")));
                if (unavailable) lines.add(tr("unavailable"));
                if (row.getBoolean("selected")) lines.add(tr("selected"));
                tooltip = lines;
            }
        }
        return shown;
    }

    private int renderProviderRows(GuiGraphics graphics, CompoundTag route, int rx, int rw, int mouseX, int mouseY) {
        var rows = route.getList("rows", Tag.TAG_COMPOUND);
        int shown = Math.min(PAGE_ROWS, rows.size());
        for (int i = 0; i < shown; i++) {
            var row = rows.getCompound(i);
            int rowIndex = i;
            int ry = y + 80 + i * ROW_HEIGHT;
            boolean hovered = inside(mouseX, mouseY, rx, ry, rw, ROW_HEIGHT);
            boolean selected = row.getBoolean("selected");
            boolean mixed = row.getBoolean("selectedMixed");
            int fill = selected || mixed ? 0xffc5dfbd : hovered ? 0xffc8d0e3 : SLOT;
            bevel(graphics, rx, ry, rw, ROW_HEIGHT, fill, true);
            drawCheck(graphics, rx + 4, ry + 4, selected, mixed, false);
            drawStackIcon(graphics, row.getCompound("icon"), rx + 18, ry + 1);
            int count = row.getInt("count");
            String suffix = count > 1 ? " ×" + count : "";
            int textWidth = rw - 48;
            label(graphics, Component.literal(row.getString("name") + suffix), rx + 37, ry, textWidth, TEXT);
            String mode = row.getBoolean("craftingMixed") ? tr("provider.mixed").getString()
                : row.getBoolean("craftingOnly") ? tr("provider.crafting").getString()
                : tr("provider.any").getString();
            if (row.getInt("order") > 0) mode = "#" + row.getInt("order") + " · " + mode;
            String details = tr("provider.space", row.getInt("free"), row.getInt("slots")).getString() + " · " + mode;
            label(graphics, Component.literal(details), rx + 37, ry + 9, textWidth, MUTED);
            String token = row.getString("token");
            if (!token.isEmpty()) {
                invisibleButton(rx + 1, ry + 1, rw - 2, ROW_HEIGHT - 2,
                    () -> action.send("route_provider", rowIndex, token));
            }
            if (hovered) {
                var lines = new ArrayList<Component>();
                lines.add(Component.literal(row.getString("name")));
                lines.add(tr("provider.space", row.getInt("free"), row.getInt("slots")));
                lines.add(mode.startsWith("#") ? Component.literal(mode) : tr(row.getBoolean("craftingMixed") ? "provider.mixed"
                    : row.getBoolean("craftingOnly") ? "provider.crafting" : "provider.any"));
                if (!row.getString("location").isBlank()) lines.add(Component.literal(row.getString("location")));
                lines.add(tr("provider.toggle"));
                tooltip = lines;
            }
        }
        return shown;
    }

    private void renderPager(GuiGraphics graphics, CompoundTag route, int rx, int rw, int shown) {
        int count = Math.max(0, route.getInt("count"));
        int offset = Math.max(0, route.getInt("offset"));
        int pageY = y + 190;
        var previous = button(rx, pageY, 20, 15, Component.literal("←"), () -> action.send("route_page", -1, ""));
        previous.active = offset > 0;
        var next = button(rx + rw - 20, pageY, 20, 15, Component.literal("→"), () -> action.send("route_page", 1, ""));
        next.active = count > 0 && offset + Math.max(1, shown) < count;
        String page = count == 0 ? "0 / 0" : (offset + 1) + "-" + (offset + shown) + " / " + count;
        String total = tr("total", count).getString();
        graphics.drawString(minecraft.font, total, rx + 25, pageY + 4, MUTED, false);
        graphics.drawString(minecraft.font, page, rx + rw - 25 - minecraft.font.width(page),
            pageY + 4, TEXT, false);
    }

    private void renderStatus(GuiGraphics graphics, CompoundTag route) {
        String status = statusText(route.getString("status"), "");
        int color = MUTED;
        if (!status.isBlank()) {
            int tokenColor = switch (route.getString("status")) {
                case "moved", "batch" -> 0xff245b47;
                case "limit", "none" -> 0xff8d303a;
                default -> MUTED;
            };
            color = tokenColor;
            label(graphics, Component.literal(status), rightX(), y + 209, rightWidth(), color);
        }
    }

    private void renderFooterHints(GuiGraphics graphics, CompoundTag route) {
        String mode = route.getString("mode");
        label(graphics, tr("hint." + (mode.equals("providers") ? "providers" : "recipes")),
            rightX(), y + 232, rightWidth(), MUTED);
        label(graphics, tr("hint.groups"), x + 8, y + 232, leftWidth, MUTED);
    }

    private void renderDialog(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        buttons.clear();
        int dialogWidth = Math.min(270, panelWidth - 20);
        int dialogHeight = dialog == Dialog.DELETE ? 92 : 98;
        int dx = x + (panelWidth - dialogWidth) / 2;
        int dy = y + (panelHeight - dialogHeight) / 2;
        graphics.fill(0, 0, width, height, 0x90000000);
        bevel(graphics, dx, dy, dialogWidth, dialogHeight, PANEL, false);
        label(graphics, tr(dialog == Dialog.CREATE ? "dialog.create"
            : dialog == Dialog.RENAME ? "dialog.rename" : "dialog.delete"), dx + 10, dy + 9, dialogWidth - 20, TEXT);

        if (dialog == Dialog.DELETE) {
            label(graphics, tr("dialog.delete.message", route().getString("groupName")),
                dx + 10, dy + 29, dialogWidth - 20, MUTED);
        } else {
            if (dialogInput != null) {
                bevel(graphics, dx + 10, dy + 34, dialogWidth - 20, 16, SLOT, true);
                dialogInput.render(graphics, mouseX, mouseY, partial);
            }
            label(graphics, tr("dialog.name_help"), dx + 10, dy + 54, dialogWidth - 20, MUTED);
        }

        int by = dy + dialogHeight - 25;
        int half = (dialogWidth - 24) / 2;
        var confirm = button(dx + 10, by, half, 17, tr(dialog == Dialog.DELETE ? "delete.confirm"
            : dialog == Dialog.RENAME ? "rename.confirm" : "create.confirm"), this::confirmDialog);
        confirm.active = dialog == Dialog.DELETE || dialogInput != null && !dialogInput.getValue().isBlank();
        button(dx + 14 + half, by, dialogWidth - 24 - half, 17, tr("cancel"), this::cancelDialog);
        for (var button : buttons) button.render(graphics, mouseX, mouseY, partial);
    }

    private TerminalButton button(int bx, int by, int bw, int bh, Component text, Runnable press) {
        var button = new TerminalButton(bx, by, Math.max(1, bw), Math.max(1, bh), text, ignored -> press.run());
        buttons.add(button);
        return button;
    }

    private void invisibleButton(int bx, int by, int bw, int bh, Runnable press) {
        buttons.add(new TerminalButton(bx, by, Math.max(1, bw), Math.max(1, bh), Component.empty(),
            ignored -> press.run()) {
            @Override
            protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
            }
        });
    }

    private void openCreate() {
        openTextDialog(Dialog.CREATE, "");
    }

    private void openRename() {
        String name = route().getString("groupName");
        if (!name.isBlank()) openTextDialog(Dialog.RENAME, name);
    }

    private void openDelete() {
        if (route().getString("groupId").isBlank()) return;
        dialog = Dialog.DELETE;
        dialogInput = null;
        search.setFocused(false);
    }

    private void openTextDialog(Dialog mode, String initialValue) {
        dialog = mode;
        dialogInput = new EditBox(minecraft.font, 0, 0, 100, 14, tr("dialog.name"));
        dialogInput.setBordered(false);
        dialogInput.setTextColor(TEXT);
        dialogInput.setTextColorUneditable(MUTED);
        dialogInput.setMaxLength(80);
        dialogInput.setHint(tr("dialog.name"));
        dialogInput.setValue(initialValue);
        positionDialogInput();
        dialogInput.setFocused(true);
        search.setFocused(false);
    }

    private void positionDialogInput() {
        if (dialogInput == null) return;
        int dialogWidth = Math.min(270, panelWidth - 20);
        int dx = x + (panelWidth - dialogWidth) / 2;
        int dy = y + (panelHeight - (dialog == Dialog.DELETE ? 92 : 98)) / 2;
        dialogInput.setX(dx + 13);
        dialogInput.setY(dy + 36);
        dialogInput.setWidth(dialogWidth - 26);
    }

    private void confirmDialog() {
        if (dialog == Dialog.DELETE) {
            action.send("route_delete", 0, "");
            cancelDialog();
            return;
        }
        if (dialogInput == null) return;
        String name = dialogInput.getValue().strip();
        if (name.isEmpty()) return;
        action.send(dialog == Dialog.RENAME ? "route_rename" : "route_create", 0, name);
        cancelDialog();
    }

    private void cancelDialog() {
        dialog = Dialog.NONE;
        dialogInput = null;
        search.setFocused(false);
    }

    private void cycleMachine() {
        CompoundTag route = route();
        var machines = route.getList("machines", Tag.TAG_COMPOUND);
        String current = route.getString("machine");
        int index = 0;
        for (int i = 0; i < machines.size(); i++) {
            if (machines.getCompound(i).getString("id").equals(current)) {
                index = i + 1;
                break;
            }
        }
        if (index > machines.size()) index = 0;
        String next = index == 0 ? "" : machines.getCompound(index - 1).getString("id");
        action.send("route_machine", 0, next);
    }

    private void cycleScope() {
        String current = route().getString("scope");
        int index = 0;
        for (int i = 0; i < SCOPES.size(); i++) {
            if (SCOPES.get(i).value().equals(current)) {
                index = (i + 1) % SCOPES.size();
                break;
            }
        }
        action.send("route_scope", 0, SCOPES.get(index).value());
    }

    private void batchAdd() {
        String token = route().getString("batchToken");
        if (!token.isEmpty()) action.send("route_batch", 0, token);
    }

    private void moveRecipe(CompoundTag route, int row, CompoundTag data) {
        String token = data.getString("token");
        if (!token.isEmpty()) action.send("route_proceed_move", row, token);
    }

    private Component scopeLabel(String value) {
        for (var scope : SCOPES) if (scope.value().equals(value)) return tr(scope.label());
        return tr("scope.plan");
    }

    private String machineFilter(CompoundTag route) {
        String id = route.getString("machine");
        if (id.isBlank()) return tr("machine.all").getString();
        var machine = machine(route, id);
        return machine == null ? tr("machine.unknown").getString() : machineLabel(machine);
    }

    private String machineLabel(CompoundTag route, CompoundTag row) {
        String id = row.getString("machineKey");
        var machine = machine(route, id);
        if (machine != null) return machineLabel(machine);
        String fallback = row.getString("machineFallback");
        return fallback.isBlank() ? tr("machine.unknown").getString() : fallback;
    }

    private String machineLabel(CompoundTag machine) {
        String labelKey = machine.getString("labelKey");
        String fallback = machine.getString("fallback");
        if (!labelKey.isBlank()) {
            String translated = Component.translatable(labelKey).getString();
            if (!translated.equals(labelKey)) return translated;
        }
        return fallback.isBlank() ? tr("machine.unknown").getString() : fallback;
    }

    private CompoundTag machine(CompoundTag route, String id) {
        if (id.isBlank()) return null;
        for (Tag tag : route.getList("machines", Tag.TAG_COMPOUND)) {
            var machine = (CompoundTag) tag;
            if (machine.getString("id").equals(id)) return machine;
        }
        return null;
    }

    private String statusText(String token, String fallback) {
        if (token == null || token.isBlank()) return fallback == null ? "" : fallback;
        String key = token.startsWith("gui.") ? token : ROOT + "status." + token;
        String translated = Component.translatable(key).getString();
        if (!translated.equals(key)) return translated;
        if (fallback != null && !fallback.isBlank()) return fallback;
        return token.replace('_', ' ');
    }

    private String stackSummary(ListTag stacks) {
        if (stacks.isEmpty()) return tr("none").getString();
        var parts = new ArrayList<String>();
        for (int i = 0; i < stacks.size() && i < 4; i++) parts.add(stackLabel(stacks.getCompound(i)));
        if (stacks.size() > parts.size()) parts.add("+" + (stacks.size() - parts.size()));
        return String.join(", ", parts);
    }

    private String stackLabel(CompoundTag stack) {
        String name = stackName(stack).getString();
        String formatted = stack.getString("formattedAmount");
        if (!formatted.isBlank()) return name + " ×" + formatted;
        long amount = stack.getLong("amount");
        return amount == 1 ? name : name + " ×" + amount;
    }

    private Component stackName(CompoundTag stack) {
        String json = stack.getString("nameComponent");
        if (!json.isBlank()) {
            try {
                var name = Component.Serializer.fromJson(json, minecraft.level.registryAccess());
                if (name != null) return name;
            } catch (RuntimeException ignored) {
                // Fall back to the server-provided plain name.
            }
        }
        String fallback = stack.getString("name");
        return fallback.isBlank() ? tr("unknown_item") : Component.literal(fallback);
    }

    private CompoundTag firstStack(CompoundTag row, String key) {
        var stacks = row.getList(key, Tag.TAG_COMPOUND);
        return stacks.isEmpty() ? new CompoundTag() : stacks.getCompound(0);
    }

    private void drawStackIcon(GuiGraphics graphics, CompoundTag stackData, int ix, int iy) {
        if (stackData == null || stackData.isEmpty()) return;
        var stack = ItemStack.parseOptional(minecraft.level.registryAccess(), stackData.getCompound("stack"));
        if (!stack.isEmpty()) {
            graphics.renderItem(stack, ix, iy);
            return;
        }
        if (stackData.contains("key", Tag.TAG_COMPOUND)) {
            AEKey key = AEKey.fromTagGeneric(minecraft.level.registryAccess(), stackData.getCompound("key"));
            if (key != null) AEKeyRendering.drawInGui(minecraft, graphics, ix, iy, key);
        }
    }

    private void drawCheck(GuiGraphics graphics, int cx, int cy, boolean selected, boolean mixed, boolean unavailable) {
        bevel(graphics, cx, cy, 10, 10, unavailable ? 0xffd6c4c1 : selected || mixed ? 0xff9ab69f : WELL, true);
        if (mixed) {
            graphics.fill(cx + 2, cy + 4, cx + 8, cy + 6, 0xff245b47);
        } else if (selected) {
            graphics.fill(cx + 2, cy + 4, cx + 4, cy + 7, 0xff245b47);
            graphics.fill(cx + 4, cy + 6, cx + 6, cy + 8, 0xff245b47);
            graphics.fill(cx + 6, cy + 2, cx + 8, cy + 6, 0xff245b47);
        }
    }

    private static boolean inside(double mx, double my, int x, int y, int width, int height) {
        return mx >= x && mx < x + width && my >= y && my < y + height;
    }

    private void label(GuiGraphics graphics, Component text, int lx, int ly, int maxWidth, int color) {
        graphics.drawString(minecraft.font,
            minecraft.font.plainSubstrByWidth(text.getString(), Math.max(0, maxWidth)), lx, ly, color, false);
    }

    boolean click(double mx, double my, int button) {
        for (var control : buttons) {
            if (control.mouseClicked(mx, my, button)) return true;
        }
        if (dialog != Dialog.NONE) return true;
        if (inside(mx, my, search.getX(), search.getY(), search.getWidth(), search.getHeight())) {
            search.setFocused(true);
            search.mouseClicked(mx, my, button);
            return true;
        }
        search.setFocused(false);
        return true;
    }

    boolean scroll(double mx, double my, double amount) {
        if (dialog != Dialog.NONE || amount == 0) return true;
        if (inside(mx, my, x + 8, listTop(), leftWidth, GROUP_ROWS * 20)) {
            if (!planView) action.send("group_page", amount > 0 ? -1 : 1, "");
        } else {
            action.send("route_page", amount > 0 ? -1 : 1, "");
        }
        return true;
    }

    boolean scroll(double amount) {
        return scroll(lastMouseX, lastMouseY, amount);
    }

    boolean key(int key, int scan, int mods) {
        if (dialog != Dialog.NONE) {
            if (key == 256) cancelDialog();
            else if (key == 257 || key == 335) confirmDialog();
            else if (dialogInput != null) dialogInput.keyPressed(key, scan, mods);
            return true;
        }
        if (key == 256) {
            close.run();
            return true;
        }
        return search.keyPressed(key, scan, mods) || search.canConsumeInput();
    }

    boolean character(char character, int mods) {
        if (dialog != Dialog.NONE) {
            if (dialogInput != null) dialogInput.charTyped(character, mods);
            return true;
        }
        return search.charTyped(character, mods);
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable(ROOT + key, args);
    }
}
