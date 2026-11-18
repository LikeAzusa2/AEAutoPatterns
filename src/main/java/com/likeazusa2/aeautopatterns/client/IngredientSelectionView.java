package com.likeazusa2.aeautopatterns.client;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import com.likeazusa2.aeautopatterns.menu.AutoPatternMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import static com.likeazusa2.aeautopatterns.client.TerminalStyle.*;

/** Modal ingredient editor shared by the terminal and the recipe tree. */
final class IngredientSelectionView {
    private static final String SNAPSHOT = "ingredientPicker";
    private static final int PAGE_SIZE = AutoPatternMenu.INGREDIENT_PAGE_SIZE;
    private static final int CARD_HEIGHT = 43;
    private final Minecraft minecraft = Minecraft.getInstance();
    private final AutoPatternMenu menu;
    private final RecipeSelectionView.Action action;
    private final List<TerminalButton> buttons = new ArrayList<>();
    private final EditBox search;
    private int inputPage, candidatePage;
    private int listId = -1, indexedCount;
    private final List<Integer> matches = new ArrayList<>();

    IngredientSelectionView(AutoPatternMenu menu, RecipeSelectionView.Action action) {
        this.menu = menu;
        this.action = action;
        search = new EditBox(minecraft.font, 0, 0, 120, 16, tr("search"));
        search.setHint(tr("search"));
        search.setMaxLength(80);
        search.setResponder(value -> {
            candidatePage = 0;
            indexedCount = 0;
            matches.clear();
        });
    }

    boolean open() {
        return menu.view.contains(SNAPSHOT, Tag.TAG_COMPOUND);
    }

    void request() {
        inputPage = 0;
        search.setFocused(false);
        action.send("ingredient_open", menu.view.getInt("selectedId"), "");
    }

    void close() {
        action.send("ingredient_close", 0, "");
        menu.view.remove(SNAPSHOT);
        buttons.clear();
        search.setFocused(false);
    }

    void render(GuiGraphics graphics, int mouseX, int mouseY, float partial, int width, int height) {
        var data = menu.view.getCompound(SNAPSHOT);
        int panelWidth = Math.min(436, width - 12);
        int x = (width - panelWidth) / 2;
        int y = (height - 190) / 2;
        int bodyWidth = panelWidth - 20;
        var inputs = data.getList("inputs", Tag.TAG_COMPOUND);
        var slotCounts = data.getIntArray("slotCounts");
        int incomingListId = data.getInt("listId");
        if (listId != incomingListId) {
            listId = incomingListId;
            candidatePage = 0;
            indexedCount = 0;
            matches.clear();
            search.setValue("");
        }
        var candidates = menu.receivedIngredients(listId);
        int slot = data.getInt("slot");
        search.setEditable(slot >= 0);
        updateMatches(candidates);
        candidatePage = Math.clamp(candidatePage, 0, lastPage(matches.size()));
        int candidateCount = data.getInt("candidateCount");
        inputPage = Math.clamp(inputPage, 0, lastPage(inputs.size()));
        buttons.clear();

        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 600);
        graphics.fill(0, 0, width, height, 0xa0000000);
        bevel(graphics, x, y, panelWidth, 190, PANEL, false);
        label(graphics, tr("title"), x + 10, y + 9, bodyWidth, TEXT);
        var product = readKey(data.getCompound("product"));
        if (product != null) label(graphics, AEKeyRendering.getDisplayName(product), x + 10, y + 22, bodyWidth, MUTED);

        label(graphics, tr("current"), x + 10, y + 40, bodyWidth - 86, TEXT);
        pages(graphics, x + panelWidth - 91, y + 36, inputPage, inputs.size(), newPage -> inputPage = newPage);
        AEKey hovered = null;
        int start = inputPage * PAGE_SIZE;
        for (int i = start; i < inputs.size() && i < start + PAGE_SIZE; i++) {
            int inputGroup = i;
            int slotCount = i < slotCounts.length ? slotCounts[i] : 1;
            var key = readKey(inputs.getCompound(i));
            if (card(graphics, x + 10, y + 53, bodyWidth, i - start, key, slotCount, i == slot, mouseX, mouseY,
                    () -> action.send("ingredient_slot", inputGroup, ""))) hovered = key;
        }

        label(graphics, tr("alternatives"), x + 10, y + 102, bodyWidth - 86, TEXT);
        pages(graphics, x + panelWidth - 91, y + 98, candidatePage, matches.size(), newPage -> candidatePage = newPage);
        search.setX(x + 10);
        search.setY(y + 115);
        search.setWidth(bodyWidth);
        search.render(graphics, mouseX, mouseY, partial);
        if (matches.isEmpty()) {
            bevel(graphics, x + 10, y + 137, bodyWidth, CARD_HEIGHT, SLOT, true);
            String message = slot < 0 ? "select" : candidates.size() < candidateCount ? "loading"
                : candidateCount == 0 ? (slot < slotCounts.length && slotCounts[slot] > 1 ? "no_common" : "none")
                : "no_matches";
            label(graphics, tr(message), x + 17, y + 153, bodyWidth - 14, MUTED);
        }
        int firstCandidate = candidatePage * PAGE_SIZE;
        String candidateList = Integer.toString(listId);
        for (int i = firstCandidate; i < matches.size() && i < firstCandidate + PAGE_SIZE; i++) {
            int candidate = matches.get(i);
            var key = candidates.get(candidate);
            if (card(graphics, x + 10, y + 137, bodyWidth, i - firstCandidate, key, 1, false, mouseX, mouseY,
                    () -> action.send("ingredient_choose", candidate, candidateList))) hovered = key;
        }
        var close = new TerminalButton(x + panelWidth - 29, y + 7, 20, 14, Component.literal("×"), button -> close());
        close.setTooltip(net.minecraft.client.gui.components.Tooltip.create(tr("close")));
        buttons.add(close);
        for (var button : buttons) button.render(graphics, mouseX, mouseY, partial);
        if (hovered != null) graphics.renderTooltip(minecraft.font, AEKeyRendering.getDisplayName(hovered), mouseX, mouseY);
        graphics.pose().popPose();
    }

    private boolean card(GuiGraphics graphics, int x, int y, int rowWidth, int column, AEKey key, int slotCount,
            boolean selected, int mouseX, int mouseY, Runnable onClick) {
        int left = x + column * rowWidth / PAGE_SIZE;
        int right = x + (column + 1) * rowWidth / PAGE_SIZE - 3;
        int width = right - left;
        boolean hovered = mouseX >= left && mouseX < right && mouseY >= y && mouseY < y + CARD_HEIGHT;
        bevel(graphics, left, y, width, CARD_HEIGHT, selected ? 0xffc5dfbd : hovered ? 0xffc8d0e3 : SLOT, selected);
        if (key == null) return false;
        var name = AEKeyRendering.getDisplayName(key);
        var lines = minecraft.font.split(name, width - 6);
        for (int i = 0; i < Math.min(lines.size(), 2); i++) {
            var line = lines.get(i);
            graphics.drawString(minecraft.font, line, left + (width - minecraft.font.width(line)) / 2,
                y + 3 + i * 9, TEXT, false);
        }
        AEKeyRendering.drawInGui(minecraft, graphics, left + (width - 16) / 2, y + 23, key);
        if (slotCount > 1) {
            String count = "×" + slotCount;
            graphics.drawString(minecraft.font, count, right - minecraft.font.width(count) - 4, y + 31, TEXT, false);
        }
        buttons.add(new TerminalButton(left, y, width, CARD_HEIGHT, name, button -> onClick.run()) {
            @Override protected void renderWidget(GuiGraphics g, int mx, int my, float partial) {
                // The entire card is clickable. Its label and icon are drawn together above.
            }
        });
        return hovered;
    }

    private void pages(GuiGraphics graphics, int x, int y, int page, int count, java.util.function.IntConsumer change) {
        int last = lastPage(count);
        if (last == 0) return;
        var previous = new TerminalButton(x, y, 18, 14, Component.literal("←"), button -> change.accept(page - 1));
        var next = new TerminalButton(x + 62, y, 18, 14, Component.literal("→"), button -> change.accept(page + 1));
        previous.active = page > 0;
        next.active = page < last;
        buttons.add(previous);
        buttons.add(next);
        String indicator = (page + 1) + "/" + (last + 1);
        graphics.drawString(minecraft.font, indicator, x + 40 - minecraft.font.width(indicator) / 2, y + 3, MUTED, false);
    }

    private static int lastPage(int count) {
        return Math.max(0, (count - 1) / PAGE_SIZE);
    }

    private AEKey readKey(CompoundTag tag) {
        return AEKey.fromTagGeneric(minecraft.level.registryAccess(), tag);
    }

    private void label(GuiGraphics graphics, Component text, int x, int y, int width, int color) {
        graphics.drawString(minecraft.font, minecraft.font.plainSubstrByWidth(text.getString(), Math.max(0, width)), x, y, color, false);
    }

    private static Component tr(String key) {
        return Component.translatable("gui.aeautopatterns.ingredient." + key);
    }

    private void updateMatches(List<AEKey> candidates) {
        if (indexedCount == candidates.size()) return;
        String query = search.getValue().strip().toLowerCase(Locale.ROOT);
        for (; indexedCount < candidates.size(); indexedCount++) {
            var key = candidates.get(indexedCount);
            if (key != null && (query.isEmpty()
                    || key.getId().toString().toLowerCase(Locale.ROOT).contains(query)
                    || AEKeyRendering.getDisplayName(key).getString().toLowerCase(Locale.ROOT).contains(query))) {
                matches.add(indexedCount);
            }
        }
    }

    void character(char character, int mods) {
        if (search.isFocused()) search.charTyped(character, mods);
    }

    void key(int key, int scan, int mods) {
        if (key == 256) close();
        else if (search.isFocused()) search.keyPressed(key, scan, mods);
    }

    boolean click(double x, double y, int button) {
        if (button == 0) {
            search.setFocused(search.isMouseOver(x, y));
            if (search.isFocused()) {
                search.mouseClicked(x, y, button);
                return true;
            }
        }
        for (var control : buttons) if (control.mouseClicked(x, y, button)) break;
        return true;
    }
}
