package com.nftl.cyae.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.nftl.cyae.ClientConfig;
import com.nftl.cyae.Cyae;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.InputEvent.MouseButton;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

@Mod.EventBusSubscriber(modid = Cyae.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ComboKeybindHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String KEY_NAME_PREFIX = "key.";
    private static final long RECENT_PRESS_WINDOW_MS = 450L;
    private static final Map<String, Consumer<Minecraft>> ACTIONS = createActionTable();
    private static final Set<Integer> PRESSED_INPUTS = new HashSet<>();
    private static final Map<Integer, Long> RECENT_INPUT_PRESSES = new HashMap<>();
    private static final Set<String> ACTIVE_ACTIONS = new HashSet<>();

    private static Map<String, List<KeyCombo>> activeCombos = Map.of();
    private static long observedConfigRevision = Long.MIN_VALUE;

    private ComboKeybindHandler() {
    }

    @SubscribeEvent
    public static void onKeyInput(final InputEvent.Key event) {
        final int keyCode = event.getKey();
        if (keyCode == GLFW.GLFW_KEY_UNKNOWN) {
            return;
        }

        if (event.getAction() == GLFW.GLFW_PRESS) {
            final int inputCode = KeyCombo.keyInputCode(keyCode);
            PRESSED_INPUTS.add(inputCode);
            RECENT_INPUT_PRESSES.put(inputCode, System.currentTimeMillis());
        } else if (event.getAction() == GLFW.GLFW_RELEASE) {
            PRESSED_INPUTS.remove(KeyCombo.keyInputCode(keyCode));
        }

        evaluateFromEvent();
    }

    @SubscribeEvent
    public static void onMouseInput(final MouseButton event) {
        if (event.getAction() == GLFW.GLFW_PRESS) {
            final int inputCode = KeyCombo.mouseInputCode(event.getButton());
            PRESSED_INPUTS.add(inputCode);
            RECENT_INPUT_PRESSES.put(inputCode, System.currentTimeMillis());
        } else if (event.getAction() == GLFW.GLFW_RELEASE) {
            PRESSED_INPUTS.remove(KeyCombo.mouseInputCode(event.getButton()));
        }

        evaluateFromEvent();
    }

    @SubscribeEvent
    public static void onClientTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        refreshCombosIfConfigChanged();

        final Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isWindowActive()) {
            releaseAllActiveKeyMappings(minecraft);
            PRESSED_INPUTS.clear();
            RECENT_INPUT_PRESSES.clear();
            ACTIVE_ACTIONS.clear();
            return;
        }

        evaluateCombos(minecraft);
    }

    private static void refreshCombosIfConfigChanged() {
        final long currentConfigRevision = ClientConfig.getRevision();
        if (currentConfigRevision == observedConfigRevision) {
            return;
        }

        observedConfigRevision = currentConfigRevision;

        final Map<String, List<KeyCombo>> parsedCombos = new LinkedHashMap<>();
        int loadedComboCount = 0;
        for (final ClientConfig.BindingEntry configuredBinding : ClientConfig.getBindings()) {
            final String action = configuredBinding.action();
            final Optional<KeyCombo> combo = KeyCombo.parse(configuredBinding.combo());
            if (combo.isPresent()) {
                parsedCombos.computeIfAbsent(action, ignored -> new ArrayList<>()).add(combo.get());
                loadedComboCount++;
            } else {
                LOGGER.warn(
                        "Ignoring invalid keybind combo '{}' for action '{}'.",
                        configuredBinding.combo(),
                        action
                );
            }
        }

        final Map<String, List<KeyCombo>> immutableCombos = new LinkedHashMap<>();
        for (final Map.Entry<String, List<KeyCombo>> entry : parsedCombos.entrySet()) {
            immutableCombos.put(entry.getKey(), List.copyOf(entry.getValue()));
        }

        activeCombos = Map.copyOf(immutableCombos);
        ACTIVE_ACTIONS.clear();
        LOGGER.info("Loaded {} combo keybind(s) across {} action(s).", loadedComboCount, activeCombos.size());
    }

    private static Map<String, Consumer<Minecraft>> createActionTable() {
        final Map<String, Consumer<Minecraft>> actions = new HashMap<>();
        actions.put("example_action", ComboKeybindHandler::runExampleAction);
        return Map.copyOf(actions);
    }

    private static void evaluateFromEvent() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !minecraft.isWindowActive()) {
            return;
        }

        evaluateCombos(minecraft);
    }

    private static void evaluateCombos(final Minecraft minecraft) {
        pruneStaleRecentPresses();
        final long windowHandle = minecraft.getWindow().getWindow();
        for (final Map.Entry<String, List<KeyCombo>> comboEntry : activeCombos.entrySet()) {
            final String action = comboEntry.getKey();
            final Optional<KeyCombo> activeCombo = findActiveCombo(comboEntry.getValue(), windowHandle);
            final boolean comboActive = activeCombo.isPresent();
            final boolean actionWasActive = ACTIVE_ACTIONS.contains(action);

            final Optional<KeyMapping> targetKeyMapping = resolveTargetKeyMapping(minecraft, action);
            if (targetKeyMapping.isPresent()) {
                applyKeyMappingAction(minecraft, action, targetKeyMapping.get(), activeCombo.orElse(null), comboActive, actionWasActive);
                continue;
            }

            if (comboActive && !actionWasActive) {
                suppressConflictingMappingsForCombo(minecraft, activeCombo.get(), null);
                executeCustomAction(minecraft, action);
                ACTIVE_ACTIONS.add(action);
            } else if (!comboActive) {
                ACTIVE_ACTIONS.remove(action);
            }
        }
    }

    private static Optional<KeyCombo> findActiveCombo(final List<KeyCombo> combos, final long windowHandle) {
        final long now = System.currentTimeMillis();
        for (final KeyCombo combo : combos) {
            if (combo.matches(windowHandle)
                    || combo.matches(PRESSED_INPUTS)
                    || combo.matchesRecentPresses(RECENT_INPUT_PRESSES, now, RECENT_PRESS_WINDOW_MS)) {
                return Optional.of(combo);
            }
        }

        return Optional.empty();
    }

    private static void pruneStaleRecentPresses() {
        final long cutoff = System.currentTimeMillis() - RECENT_PRESS_WINDOW_MS;
        RECENT_INPUT_PRESSES.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    private static Optional<KeyMapping> resolveTargetKeyMapping(final Minecraft minecraft, final String actionName) {
        if (minecraft == null || minecraft.options == null) {
            return Optional.empty();
        }

        final String normalizedAction = normalizeActionName(actionName);
        if (normalizedAction.isEmpty()) {
            return Optional.empty();
        }

        for (final KeyMapping keyMapping : minecraft.options.keyMappings) {
            final String normalizedKeyName = normalizeActionName(keyMapping.getName());
            if (normalizedAction.equals(normalizedKeyName)) {
                return Optional.of(keyMapping);
            }

            if (normalizedKeyName.startsWith(KEY_NAME_PREFIX)
                    && normalizedAction.equals(normalizedKeyName.substring(KEY_NAME_PREFIX.length()))) {
                return Optional.of(keyMapping);
            }
        }

        return Optional.empty();
    }

    private static void applyKeyMappingAction(
            final Minecraft minecraft,
            final String actionName,
            final KeyMapping keyMapping,
            final KeyCombo activeCombo,
            final boolean comboActive,
            final boolean actionWasActive
    ) {
        if (comboActive && !actionWasActive) {
            suppressConflictingMappingsForCombo(minecraft, activeCombo, keyMapping);
            LOGGER.debug("Combo keybind triggered: {} -> {}", actionName, keyMapping.getName());
            MinecraftForge.EVENT_BUS.post(new ComboKeybindTriggeredEvent(actionName));
            triggerKeyMappingClick(minecraft, keyMapping);
            ACTIVE_ACTIONS.add(actionName);
        } else if (!comboActive && actionWasActive) {
            ACTIVE_ACTIONS.remove(actionName);
        }

        if (comboActive) {
            setKeyMappingState(minecraft, keyMapping, true, false);
        } else if (actionWasActive) {
            setKeyMappingState(minecraft, keyMapping, false, false);
        }
    }

    private static void suppressConflictingMappingsForCombo(
            final Minecraft minecraft,
            final KeyCombo combo,
            final KeyMapping excludedMapping
    ) {
        if (minecraft == null || minecraft.options == null || combo == null) {
            return;
        }

        for (final KeyMapping keyMapping : minecraft.options.keyMappings) {
            if (keyMapping == excludedMapping) {
                continue;
            }

            final InputConstants.Key mappedKey = keyMapping.getKey();
            if (mappedKey == InputConstants.UNKNOWN || !combo.includesInput(mappedKey)) {
                continue;
            }

            while (keyMapping.consumeClick()) {
                // Drain key clicks for combo component keys so unrelated mappings do not steal the input.
            }
        }
    }

    private static void releaseAllActiveKeyMappings(final Minecraft minecraft) {
        for (final String actionName : Set.copyOf(ACTIVE_ACTIONS)) {
            resolveTargetKeyMapping(minecraft, actionName)
                    .ifPresent(keyMapping -> setKeyMappingState(minecraft, keyMapping, false, true));
        }
    }

    private static void setKeyMappingState(
            final Minecraft minecraft,
            final KeyMapping keyMapping,
            final boolean pressed,
            final boolean forceRelease
    ) {
        final InputConstants.Key boundKey = keyMapping.getKey();
        if (boundKey != InputConstants.UNKNOWN && !pressed && !forceRelease && isPhysicalInputDown(minecraft, boundKey)) {
            return;
        }

        if (boundKey != InputConstants.UNKNOWN) {
            KeyMapping.set(boundKey, pressed);
        }
        keyMapping.setDown(pressed);
    }

    private static void triggerKeyMappingClick(final Minecraft minecraft, final KeyMapping targetKeyMapping) {
        final InputConstants.Key boundKey = targetKeyMapping.getKey();
        if (boundKey != InputConstants.UNKNOWN) {
            KeyMapping.click(boundKey);
            return;
        }

        KeyMapping.click(InputConstants.UNKNOWN);
        if (minecraft == null || minecraft.options == null) {
            return;
        }

        for (final KeyMapping keyMapping : minecraft.options.keyMappings) {
            if (keyMapping == targetKeyMapping || keyMapping.getKey() != InputConstants.UNKNOWN) {
                continue;
            }

            while (keyMapping.consumeClick()) {
                // Drain clicks so only the targeted unbound mapping is triggered.
            }
        }
    }

    private static boolean isPhysicalInputDown(final Minecraft minecraft, final InputConstants.Key key) {
        final long windowHandle = minecraft.getWindow().getWindow();
        final InputConstants.Type inputType = key.getType();
        if (inputType == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(windowHandle, key.getValue()) == GLFW.GLFW_PRESS;
        }

        if (inputType == InputConstants.Type.KEYSYM || inputType == InputConstants.Type.SCANCODE) {
            return GLFW.glfwGetKey(windowHandle, key.getValue()) == GLFW.GLFW_PRESS;
        }

        return false;
    }

    private static String normalizeActionName(final String actionName) {
        if (actionName == null) {
            return "";
        }

        return actionName.trim().toLowerCase(Locale.ROOT);
    }

    private static void executeCustomAction(final Minecraft minecraft, final String actionName) {
        if (minecraft == null) {
            return;
        }

        LOGGER.debug("Combo keybind triggered: {}", actionName);
        MinecraftForge.EVENT_BUS.post(new ComboKeybindTriggeredEvent(actionName));

        if (executeConfiguredCommand(minecraft, actionName)) {
            return;
        }

        final Consumer<Minecraft> callback = ACTIONS.get(actionName);
        if (callback != null) {
            callback.accept(minecraft);
            return;
        }

        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal("Triggered keybind: " + actionName), false);
        }
    }

    private static boolean executeConfiguredCommand(final Minecraft minecraft, final String actionName) {
        final Optional<String> command = ClientConfig.extractCommandFromAction(actionName);
        if (command.isEmpty()) {
            return false;
        }

        if (minecraft.player == null || minecraft.player.connection == null) {
            return true;
        }

        final String normalizedCommand = command.get().trim();
        if (normalizedCommand.isEmpty()) {
            return true;
        }

        final String outgoingCommand = normalizedCommand.startsWith("/")
                ? normalizedCommand.substring(1)
                : normalizedCommand;
        if (outgoingCommand.isBlank()) {
            return true;
        }

        minecraft.player.connection.sendCommand(outgoingCommand);
        return true;
    }

    private static void runExampleAction(final Minecraft minecraft) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal("CYAE keybind triggered"), false);
        }
    }
}
