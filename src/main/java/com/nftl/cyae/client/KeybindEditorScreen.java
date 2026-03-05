package com.nftl.cyae.client;

import com.nftl.cyae.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class KeybindEditorScreen extends Screen {
    private static final int ROW_HEIGHT = 24;
    private static final int LIST_MARGIN = 16;
    private static final Map<Integer, String> KEY_CODE_TOKENS = buildKeyCodeTokens();
    private static final Map<Integer, String> MOUSE_BUTTON_TOKENS = buildMouseButtonTokens();

    private final Screen parent;
    private final List<BindingDraft> draftRows = new ArrayList<>();

    private BindingList bindingList;
    private Component statusMessage = Component.empty();
    private int statusColor = 0xA0A0A0;
    private Integer capturingRowIndex;
    private final LinkedHashSet<String> capturedComboTokens = new LinkedHashSet<>();
    private final Set<Integer> currentlyPressedCaptureInputs = new HashSet<>();

    public KeybindEditorScreen(final Screen parent) {
        super(Component.translatable("screen.cyae.keybind_editor"));
        this.parent = parent;
        this.loadDraftRowsFromConfig();
    }

    @Override
    protected void init() {
        final int listTop = 32;
        final int buttonY = this.height - 28;
        final int listBottom = buttonY - 8;

        this.bindingList = this.addRenderableWidget(
                new BindingList(this.minecraft, this, this.width - (LIST_MARGIN * 2), this.height, listTop, listBottom, ROW_HEIGHT)
        );
        this.bindingList.setLeftPos(LIST_MARGIN);
        this.bindingList.loadBindings(this.draftRows);

        this.addRenderableWidget(
                Button.builder(Component.translatable("screen.cyae.add_row"), button -> this.bindingList.addEmptyRow())
                        .pos(LIST_MARGIN, buttonY)
                        .size(90, 20)
                        .build()
        );
        this.addRenderableWidget(
                Button.builder(Component.translatable("screen.cyae.save"), button -> this.saveAndClose())
                        .pos(this.width / 2 - 84, buttonY)
                        .size(80, 20)
                        .build()
        );
        this.addRenderableWidget(
                Button.builder(Component.translatable("screen.cyae.cancel"), button -> this.onClose())
                        .pos(this.width / 2 + 4, buttonY)
                        .size(80, 20)
                        .build()
        );

        this.setStatus(Component.translatable("screen.cyae.status.edit"), 0xA0A0A0);
        this.setInitialFocus(this.bindingList);
    }

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY, final float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        guiGraphics.drawString(this.font, this.statusMessage, LIST_MARGIN, this.height - 42, this.statusColor, false);
    }

    @Override
    public void onClose() {
        this.cancelComboCapture(false);
        this.minecraft.setScreen(this.parent);
    }

    void openActionPickerForRow(final int rowIndex) {
        if (this.minecraft == null || this.bindingList == null) {
            return;
        }

        this.cancelComboCapture(false);
        this.captureDraftRows();
        if (rowIndex < 0 || rowIndex >= this.draftRows.size()) {
            return;
        }

        final String currentAction = this.draftRows.get(rowIndex).action;
        this.minecraft.setScreen(new ActionPickerScreen(this, rowIndex, currentAction));
    }

    void applyPickedAction(final int rowIndex, final String actionName) {
        if (rowIndex < 0 || rowIndex >= this.draftRows.size()) {
            return;
        }

        this.draftRows.get(rowIndex).action = ClientConfig.canonicalActionName(actionName);
    }

    private void saveAndClose() {
        this.cancelComboCapture(false);
        this.captureDraftRows();
        final List<ClientConfig.BindingEntry> updatedBindings = new ArrayList<>();

        for (final BindingDraft row : this.draftRows) {
            final String rawAction = row.getAction();
            final String rawCombo = row.getCombo();
            final String action = ClientConfig.canonicalActionName(rawAction);
            final String combo = ClientConfig.canonicalComboExpression(rawCombo);

            if (action.isEmpty() && combo.isEmpty()) {
                continue;
            }

            if (!ClientConfig.isValidActionName(action)) {
                this.setStatus(Component.literal("Invalid action name: " + rawAction), 0xFF5555);
                return;
            }

            if (KeyCombo.parse(combo).isEmpty()) {
                this.setStatus(Component.literal("Invalid combo: " + rawCombo), 0xFF5555);
                return;
            }

            updatedBindings.add(new ClientConfig.BindingEntry(action, combo));
        }

        if (!ClientConfig.replaceBindings(updatedBindings)) {
            this.setStatus(Component.literal("Failed to save keybinds."), 0xFF5555);
            return;
        }

        this.minecraft.setScreen(this.parent);
    }

    private void captureDraftRows() {
        if (this.bindingList == null) {
            return;
        }

        this.draftRows.clear();
        for (final BindingRow row : this.bindingList.getEntriesSnapshot()) {
            this.draftRows.add(new BindingDraft(row.getAction(), row.getCombo()));
        }

        if (this.draftRows.isEmpty()) {
            this.draftRows.add(new BindingDraft("", ""));
        }
    }

    private void loadDraftRowsFromConfig() {
        if (!this.draftRows.isEmpty()) {
            return;
        }

        for (final ClientConfig.BindingEntry entry : ClientConfig.getBindings()) {
            this.draftRows.add(new BindingDraft(entry.action(), entry.combo()));
        }

        if (this.draftRows.isEmpty()) {
            this.draftRows.add(new BindingDraft("", ""));
        }
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (!this.isCapturingCombo()) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.cancelComboCapture(true);
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            this.finishComboCapture();
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_UNKNOWN) {
            return true;
        }

        this.currentlyPressedCaptureInputs.add(KeyCombo.keyInputCode(keyCode));
        this.captureToken(tokenForKeyCode(keyCode));
        return true;
    }

    @Override
    public boolean keyReleased(final int keyCode, final int scanCode, final int modifiers) {
        if (!this.isCapturingCombo()) {
            return super.keyReleased(keyCode, scanCode, modifiers);
        }

        if (keyCode != GLFW.GLFW_KEY_UNKNOWN) {
            this.currentlyPressedCaptureInputs.remove(KeyCombo.keyInputCode(keyCode));
        }
        this.finishCaptureIfReleased();
        return true;
    }

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        final boolean handledByWidgets = super.mouseClicked(mouseX, mouseY, button);
        if (!this.isCapturingCombo()) {
            return handledByWidgets;
        }

        if (handledByWidgets) {
            return true;
        }

        this.currentlyPressedCaptureInputs.add(KeyCombo.mouseInputCode(button));
        this.captureToken(tokenForMouseButton(button));
        return true;
    }

    @Override
    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
        final boolean handledByWidgets = super.mouseReleased(mouseX, mouseY, button);
        if (!this.isCapturingCombo()) {
            return handledByWidgets;
        }

        this.currentlyPressedCaptureInputs.remove(KeyCombo.mouseInputCode(button));
        this.finishCaptureIfReleased();
        return true;
    }

    void toggleComboCaptureForRow(final int rowIndex) {
        if (this.capturingRowIndex != null && this.capturingRowIndex == rowIndex) {
            this.finishComboCapture();
            return;
        }

        this.captureDraftRows();
        if (rowIndex < 0 || rowIndex >= this.draftRows.size()) {
            return;
        }

        this.capturingRowIndex = rowIndex;
        this.capturedComboTokens.clear();
        this.currentlyPressedCaptureInputs.clear();
        this.setStatus(Component.translatable("screen.cyae.status.capturing"), 0xFFD700);
    }

    boolean isCapturingRow(final int rowIndex) {
        return this.capturingRowIndex != null && this.capturingRowIndex == rowIndex;
    }

    void cancelComboCapture(final boolean showStatus) {
        if (!this.isCapturingCombo()) {
            return;
        }

        this.capturingRowIndex = null;
        this.capturedComboTokens.clear();
        this.currentlyPressedCaptureInputs.clear();
        if (showStatus) {
            this.setStatus(Component.translatable("screen.cyae.status.capture_cancelled"), 0xFFAA00);
        }
    }

    private boolean isCapturingCombo() {
        return this.capturingRowIndex != null;
    }

    private void captureToken(final String token) {
        if (token == null || token.isBlank()) {
            return;
        }

        this.capturedComboTokens.add(token);
    }

    private void finishCaptureIfReleased() {
        if (this.currentlyPressedCaptureInputs.isEmpty() && !this.capturedComboTokens.isEmpty()) {
            this.finishComboCapture();
        }
    }

    private void finishComboCapture() {
        if (this.capturingRowIndex == null) {
            return;
        }

        final int rowIndex = this.capturingRowIndex;
        if (this.capturedComboTokens.isEmpty()) {
            this.cancelComboCapture(true);
            return;
        }

        final String combo = String.join("+", this.capturedComboTokens);
        if (this.bindingList != null) {
            this.bindingList.setComboForRow(rowIndex, combo);
        }
        if (rowIndex >= 0 && rowIndex < this.draftRows.size()) {
            this.draftRows.get(rowIndex).combo = combo;
        }

        this.capturingRowIndex = null;
        this.capturedComboTokens.clear();
        this.currentlyPressedCaptureInputs.clear();
        this.setStatus(Component.translatable("screen.cyae.status.capture_saved", combo), 0x55FF55);
    }

    private void setStatus(final Component message, final int color) {
        this.statusMessage = message;
        this.statusColor = color;
    }

    private static String tokenForKeyCode(final int keyCode) {
        final String mapped = KEY_CODE_TOKENS.get(keyCode);
        return mapped == null ? "" : mapped;
    }

    private static String tokenForMouseButton(final int button) {
        final String mapped = MOUSE_BUTTON_TOKENS.get(button);
        return mapped == null ? "" : mapped;
    }

    private static Map<Integer, String> buildKeyCodeTokens() {
        final Map<Integer, String> mapping = new HashMap<>();
        for (final Field field : GLFW.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !field.getName().startsWith("GLFW_KEY_")) {
                continue;
            }

            try {
                final int code = field.getInt(null);
                if (code == GLFW.GLFW_KEY_UNKNOWN) {
                    continue;
                }

                final String token = field.getName().substring("GLFW_KEY_".length());
                mapping.put(code, normalizeCapturedKeyToken(token));
            } catch (IllegalAccessException ignored) {
                // GLFW key constants are public.
            }
        }

        return Map.copyOf(mapping);
    }

    private static Map<Integer, String> buildMouseButtonTokens() {
        final Map<Integer, String> mapping = new HashMap<>();
        mapping.put(GLFW.GLFW_MOUSE_BUTTON_LEFT, "MOUSE1");
        mapping.put(GLFW.GLFW_MOUSE_BUTTON_RIGHT, "MOUSE2");
        mapping.put(GLFW.GLFW_MOUSE_BUTTON_MIDDLE, "MOUSE3");
        mapping.put(GLFW.GLFW_MOUSE_BUTTON_4, "MOUSE4");
        mapping.put(GLFW.GLFW_MOUSE_BUTTON_5, "MOUSE5");
        mapping.put(GLFW.GLFW_MOUSE_BUTTON_6, "MOUSE6");
        mapping.put(GLFW.GLFW_MOUSE_BUTTON_7, "MOUSE7");
        mapping.put(GLFW.GLFW_MOUSE_BUTTON_8, "MOUSE8");
        return Map.copyOf(mapping);
    }

    private static String normalizeCapturedKeyToken(final String token) {
        return switch (token) {
            case "LEFT_CONTROL", "RIGHT_CONTROL" -> "CTRL";
            case "LEFT_SHIFT", "RIGHT_SHIFT" -> "SHIFT";
            case "LEFT_ALT", "RIGHT_ALT" -> "ALT";
            case "LEFT_SUPER", "RIGHT_SUPER" -> "SUPER";
            default -> token;
        };
    }

    private static final class BindingDraft {
        private String action;
        private String combo;

        private BindingDraft(final String action, final String combo) {
            this.action = action == null ? "" : action;
            this.combo = combo == null ? "" : combo;
        }

        private String getAction() {
            return this.action.trim();
        }

        private String getCombo() {
            return this.combo.trim().toUpperCase(Locale.ROOT);
        }
    }

    private static final class BindingList extends ContainerObjectSelectionList<BindingRow> {
        private final Minecraft minecraft;
        private final KeybindEditorScreen ownerScreen;

        private BindingList(
                final Minecraft minecraft,
                final KeybindEditorScreen ownerScreen,
                final int width,
                final int height,
                final int top,
                final int bottom,
                final int itemHeight
        ) {
            super(minecraft, width, height, top, bottom, itemHeight);
            this.minecraft = minecraft;
            this.ownerScreen = ownerScreen;
            this.setRenderSelection(false);
            this.setRenderTopAndBottom(false);
        }

        @Override
        public int getRowWidth() {
            return this.width - 20;
        }

        @Override
        protected int getScrollbarPosition() {
            return this.getRight() - 6;
        }

        private void loadBindings(final List<BindingDraft> bindings) {
            this.clearEntries();
            for (final BindingDraft draft : bindings) {
                this.addEntry(new BindingRow(this.minecraft, this.ownerScreen, this, draft.action, draft.combo));
            }

            if (this.children().isEmpty()) {
                this.addEmptyRow();
            }
        }

        private void addEmptyRow() {
            this.ownerScreen.cancelComboCapture(false);
            this.addEntry(new BindingRow(this.minecraft, this.ownerScreen, this, "", ""));
        }

        private void removeRow(final BindingRow row) {
            this.ownerScreen.cancelComboCapture(false);
            this.removeEntry(row);
            if (this.children().isEmpty()) {
                this.addEmptyRow();
            }
        }

        private void setComboForRow(final int rowIndex, final String combo) {
            if (rowIndex < 0 || rowIndex >= this.children().size()) {
                return;
            }

            this.children().get(rowIndex).setCombo(combo);
        }

        private int indexOfRow(final BindingRow row) {
            return this.children().indexOf(row);
        }

        private List<BindingRow> getEntriesSnapshot() {
            return new ArrayList<>(this.children());
        }
    }

    private static final class BindingRow extends ContainerObjectSelectionList.Entry<BindingRow> {
        private static final int ACTION_WIDTH = 140;
        private static final int PICK_WIDTH = 68;
        private static final int ASSIGN_WIDTH = 64;
        private static final int REMOVE_WIDTH = 24;
        private static final int WIDGET_HEIGHT = 18;

        private final KeybindEditorScreen ownerScreen;
        private final BindingList ownerList;
        private final EditBox actionBox;
        private final EditBox comboBox;
        private final Button selectActionButton;
        private final Button assignComboButton;
        private final Button removeButton;
        private final List<GuiEventListener> children;
        private final List<NarratableEntry> narratables;

        private BindingRow(
                final Minecraft minecraft,
                final KeybindEditorScreen ownerScreen,
                final BindingList owner,
                final String action,
                final String combo
        ) {
            this.ownerScreen = ownerScreen;
            this.ownerList = owner;
            this.actionBox = new EditBox(minecraft.font, 0, 0, ACTION_WIDTH, WIDGET_HEIGHT, Component.translatable("screen.cyae.action"));
            this.actionBox.setValue(action);
            this.actionBox.setMaxLength(256);

            this.selectActionButton = Button.builder(
                            Component.translatable("screen.cyae.select_action"),
                            button -> ownerScreen.openActionPickerForRow(owner.indexOfRow(this))
                    )
                    .pos(0, 0)
                    .size(PICK_WIDTH, WIDGET_HEIGHT)
                    .build();

            this.comboBox = new EditBox(minecraft.font, 0, 0, 120, WIDGET_HEIGHT, Component.translatable("screen.cyae.combo"));
            this.comboBox.setValue(combo);
            this.comboBox.setMaxLength(128);

            this.assignComboButton = Button.builder(
                            Component.translatable("screen.cyae.assign_combo"),
                            button -> this.ownerScreen.toggleComboCaptureForRow(this.ownerList.indexOfRow(this))
                    )
                    .pos(0, 0)
                    .size(ASSIGN_WIDTH, WIDGET_HEIGHT)
                    .build();

            this.removeButton = Button.builder(Component.literal("X"), button -> owner.removeRow(this))
                    .pos(0, 0)
                    .size(REMOVE_WIDTH, WIDGET_HEIGHT)
                    .build();

            this.children = List.of(this.actionBox, this.selectActionButton, this.comboBox, this.assignComboButton, this.removeButton);
            this.narratables = List.of(this.actionBox, this.selectActionButton, this.comboBox, this.assignComboButton, this.removeButton);
        }

        @Override
        public void render(
                final GuiGraphics guiGraphics,
                final int index,
                final int top,
                final int left,
                final int width,
                final int height,
                final int mouseX,
                final int mouseY,
                final boolean hovered,
                final float partialTick
        ) {
            final int y = top + ((height - WIDGET_HEIGHT) / 2);
            final int actionX = left + 4;
            final int pickX = actionX + ACTION_WIDTH + 6;
            final int removeX = left + width - REMOVE_WIDTH - 4;
            final int assignX = removeX - ASSIGN_WIDTH - 6;
            final int comboX = pickX + PICK_WIDTH + 6;
            final int comboWidth = Math.max(60, removeX - comboX - 6);
            final int adjustedComboWidth = Math.max(60, assignX - comboX - 6);
            final boolean capturingThisRow = this.ownerScreen.isCapturingRow(this.ownerList.indexOfRow(this));

            this.actionBox.setX(actionX);
            this.actionBox.setY(y);

            this.selectActionButton.setX(pickX);
            this.selectActionButton.setY(y);

            this.comboBox.setX(comboX);
            this.comboBox.setY(y);
            this.comboBox.setWidth(Math.min(comboWidth, adjustedComboWidth));

            this.assignComboButton.setX(assignX);
            this.assignComboButton.setY(y);
            this.assignComboButton.setMessage(Component.translatable(capturingThisRow ? "screen.cyae.capturing" : "screen.cyae.assign_combo"));

            this.removeButton.setX(removeX);
            this.removeButton.setY(y);

            this.actionBox.render(guiGraphics, mouseX, mouseY, partialTick);
            this.selectActionButton.render(guiGraphics, mouseX, mouseY, partialTick);
            this.comboBox.render(guiGraphics, mouseX, mouseY, partialTick);
            this.assignComboButton.render(guiGraphics, mouseX, mouseY, partialTick);
            this.removeButton.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return this.children;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return this.narratables;
        }

        private String getAction() {
            return this.actionBox.getValue().trim();
        }

        private String getCombo() {
            return this.comboBox.getValue().trim().toUpperCase(Locale.ROOT);
        }

        private void setCombo(final String combo) {
            this.comboBox.setValue(combo);
        }
    }
}
