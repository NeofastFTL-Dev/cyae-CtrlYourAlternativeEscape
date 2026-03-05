package com.nftl.cyae.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class ActionPickerScreen extends Screen {
    private static final int LIST_MARGIN = 16;
    private static final int ROW_HEIGHT = 22;

    private final KeybindEditorScreen parent;
    private final int rowIndex;
    private final String initialAction;

    private EditBox searchBox;
    private ActionList actionList;
    private List<ActionOption> allOptions = List.of();

    public ActionPickerScreen(final KeybindEditorScreen parent, final int rowIndex, final String initialAction) {
        super(Component.translatable("screen.cyae.action_picker"));
        this.parent = parent;
        this.rowIndex = rowIndex;
        this.initialAction = initialAction == null ? "" : initialAction.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    protected void init() {
        final int buttonY = this.height - 28;
        final int listTop = 54;
        final int listBottom = buttonY - 8;

        this.searchBox = this.addRenderableWidget(
                new EditBox(
                        this.font,
                        LIST_MARGIN,
                        30,
                        this.width - (LIST_MARGIN * 2),
                        18,
                        Component.translatable("screen.cyae.action_picker.search")
                )
        );
        this.searchBox.setResponder(this::refreshVisibleOptions);
        this.searchBox.setMaxLength(80);

        this.actionList = this.addRenderableWidget(
                new ActionList(this.width - (LIST_MARGIN * 2), this.height, listTop, listBottom, ROW_HEIGHT)
        );
        this.actionList.setLeftPos(LIST_MARGIN);

        this.allOptions = this.collectActionOptions();
        this.refreshVisibleOptions(this.searchBox.getValue());
        this.actionList.selectByAction(this.initialAction);

        this.addRenderableWidget(
                Button.builder(Component.translatable("screen.cyae.cancel"), button -> this.onClose())
                        .pos(this.width / 2 - 40, buttonY)
                        .size(80, 20)
                        .build()
        );

        this.setInitialFocus(this.searchBox);
    }

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY, final float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        guiGraphics.drawString(
                this.font,
                Component.translatable("screen.cyae.action_picker.hint"),
                LIST_MARGIN,
                18,
                0xA0A0A0,
                false
        );

        if (!this.actionList.hasEntries()) {
            guiGraphics.drawCenteredString(
                    this.font,
                    Component.translatable("screen.cyae.action_picker.empty"),
                    this.width / 2,
                    this.height / 2,
                    0xFF5555
            );
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    private List<ActionOption> collectActionOptions() {
        if (this.minecraft == null || this.minecraft.options == null) {
            return List.of();
        }

        final KeyMapping[] mappings = this.minecraft.options.keyMappings.clone();
        final Comparator<KeyMapping> byCategory = Comparator.comparing(
                mapping -> Component.translatable(mapping.getCategory()).getString(),
                String.CASE_INSENSITIVE_ORDER
        );
        final Comparator<KeyMapping> byName = Comparator.comparing(
                mapping -> Component.translatable(mapping.getName()).getString(),
                String.CASE_INSENSITIVE_ORDER
        );
        java.util.Arrays.sort(mappings, byCategory.thenComparing(byName));

        final List<ActionOption> options = new ArrayList<>(mappings.length);
        for (final KeyMapping mapping : mappings) {
            options.add(new ActionOption(
                    mapping.getName(),
                    Component.translatable(mapping.getName()).getString(),
                    Component.translatable(mapping.getCategory()).getString(),
                    mapping.getTranslatedKeyMessage().getString()
            ));
        }
        return List.copyOf(options);
    }

    private void refreshVisibleOptions(final String rawFilter) {
        final String filter = normalizeFilter(rawFilter);
        final List<ActionOption> filtered = new ArrayList<>();
        for (final ActionOption option : this.allOptions) {
            if (option.matches(filter)) {
                filtered.add(option);
            }
        }

        this.actionList.loadOptions(filtered);
    }

    private void selectAction(final String actionName) {
        this.parent.applyPickedAction(this.rowIndex, actionName);
        this.minecraft.setScreen(this.parent);
    }

    private static String normalizeFilter(final String rawFilter) {
        if (rawFilter == null) {
            return "";
        }

        return rawFilter.trim().toLowerCase(Locale.ROOT);
    }

    private static final class ActionOption {
        private final String actionName;
        private final String displayName;
        private final String categoryName;
        private final String assignedKey;
        private final String searchBlob;

        private ActionOption(
                final String actionName,
                final String displayName,
                final String categoryName,
                final String assignedKey
        ) {
            this.actionName = actionName == null ? "" : actionName;
            this.displayName = displayName == null ? "" : displayName;
            this.categoryName = categoryName == null ? "" : categoryName;
            this.assignedKey = assignedKey == null ? "" : assignedKey;
            this.searchBlob = (this.actionName + "|" + this.displayName + "|" + this.categoryName + "|" + this.assignedKey)
                    .toLowerCase(Locale.ROOT);
        }

        private boolean matches(final String normalizedFilter) {
            return normalizedFilter.isEmpty() || this.searchBlob.contains(normalizedFilter);
        }

        private Component buttonLabel() {
            return Component.literal(this.displayName + " (" + this.assignedKey + ") [" + this.actionName + "]");
        }
    }

    private final class ActionList extends ContainerObjectSelectionList<ActionEntry> {
        private ActionList(final int width, final int height, final int top, final int bottom, final int itemHeight) {
            super(ActionPickerScreen.this.minecraft, width, height, top, bottom, itemHeight);
            this.setRenderTopAndBottom(false);
            this.setRenderSelection(false);
        }

        @Override
        public int getRowWidth() {
            return this.width - 12;
        }

        @Override
        protected int getScrollbarPosition() {
            return this.getRight() - 6;
        }

        private void loadOptions(final List<ActionOption> options) {
            this.clearEntries();
            for (final ActionOption option : options) {
                this.addEntry(new ActionEntry(option));
            }
        }

        private boolean hasEntries() {
            return !this.children().isEmpty();
        }

        private void selectByAction(final String actionName) {
            if (actionName == null || actionName.isBlank()) {
                return;
            }

            final String normalizedAction = actionName.trim().toLowerCase(Locale.ROOT);
            for (final ActionEntry entry : this.children()) {
                if (entry.option.actionName.equalsIgnoreCase(normalizedAction)) {
                    this.centerScrollOn(entry);
                    break;
                }
            }
        }
    }

    private final class ActionEntry extends ContainerObjectSelectionList.Entry<ActionEntry> {
        private final ActionOption option;
        private final Button selectButton;
        private final List<GuiEventListener> children;
        private final List<NarratableEntry> narratables;

        private ActionEntry(final ActionOption option) {
            this.option = option;
            this.selectButton = Button.builder(option.buttonLabel(), button -> selectAction(option.actionName))
                    .pos(0, 0)
                    .size(120, 18)
                    .build();
            this.children = List.of(this.selectButton);
            this.narratables = List.of(this.selectButton);
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
            this.selectButton.setX(left + 2);
            this.selectButton.setY(top + 2);
            this.selectButton.setWidth(Math.max(120, width - 4));
            this.selectButton.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return this.children;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return this.narratables;
        }
    }
}
