package com.nftl.cyae;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Mod.EventBusSubscriber(modid = Cyae.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ClientConfig {
    private static final Pattern ACTION_NAME_PATTERN = Pattern.compile("[a-z0-9_.-]+");
    private static final String LEGACY_COMMAND_PREFIX = "cmd:";
    private static final String LEGACY_COMMAND_ALIAS_PREFIX = "command:";
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> KEYBINDS = BUILDER
            .comment(
                    "Keybind mapping entries in format action=KEY+KEY+KEY.",
                    "You can add multiple entries for the same action.",
                    "Action can be a custom id or a control key name (jump, sneak, key.jump, key.sneak).",
                    "Examples:",
                    "jump=CTRL+SHIFT+K",
                    "jump=Z+A+T",
                    "key.sneak=CTRL+R",
                    "/spawn=CTRL+P",
                    "/gamemode creative=CTRL+SHIFT+G",
                    "flight_toggle=MOUSE_RIGHT+Q"
            )
            .defineListAllowEmpty("keybinds", List.of("jump=CTRL+SHIFT+K"), ClientConfig::validateKeybindEntry);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    private static volatile List<BindingEntry> bindings = List.of();
    private static volatile long revision = 0L;

    public record BindingEntry(String action, String combo) {
    }

    private static boolean validateKeybindEntry(final Object obj) {
        if (!(obj instanceof final String entry)) {
            return false;
        }

        final int splitIndex = entry.indexOf('=');
        return splitIndex > 0 && splitIndex < entry.length() - 1;
    }

    public static List<BindingEntry> getBindings() {
        return bindings;
    }

    public static long getRevision() {
        return revision;
    }

    public static synchronized boolean setBinding(final String actionName, final String comboExpression) {
        final String action = canonicalActionName(actionName);
        final String combo = canonicalComboExpression(comboExpression);
        if (!isValidActionName(action) || combo.isEmpty()) {
            return false;
        }

        final List<BindingEntry> updatedBindings = new ArrayList<>(bindings);
        updatedBindings.add(new BindingEntry(action, combo));
        return persistBindings(updatedBindings);
    }

    public static synchronized boolean removeBinding(final String actionName) {
        final String action = canonicalActionName(actionName);
        if (!isValidActionName(action)) {
            return false;
        }

        final List<BindingEntry> updatedBindings = new ArrayList<>();
        boolean removed = false;
        for (final BindingEntry entry : bindings) {
            if (entry.action().equals(action)) {
                removed = true;
                continue;
            }
            updatedBindings.add(entry);
        }

        if (!removed) {
            return false;
        }

        return persistBindings(updatedBindings);
    }

    public static synchronized void clearBindings() {
        persistBindings(List.of());
    }

    public static synchronized boolean replaceBindings(final List<BindingEntry> rawBindings) {
        final List<BindingEntry> updatedBindings = new ArrayList<>(rawBindings.size());
        for (final BindingEntry entry : rawBindings) {
            final String action = canonicalActionName(entry.action());
            final String combo = canonicalComboExpression(entry.combo());
            if (!isValidActionName(action) || combo.isEmpty()) {
                return false;
            }

            updatedBindings.add(new BindingEntry(action, combo));
        }

        return persistBindings(updatedBindings);
    }

    public static boolean isValidActionName(final String actionName) {
        if (actionName == null) {
            return false;
        }

        if (extractCommandFromAction(actionName).isPresent()) {
            return true;
        }

        return ACTION_NAME_PATTERN.matcher(actionName).matches();
    }

    public static String canonicalActionName(final String actionName) {
        return normalizeActionName(actionName);
    }

    public static String canonicalComboExpression(final String comboExpression) {
        return normalizeComboExpression(comboExpression);
    }

    public static Optional<String> extractCommandFromAction(final String actionName) {
        if (actionName == null) {
            return Optional.empty();
        }

        final String trimmed = actionName.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        if (trimmed.startsWith("/")) {
            return Optional.of(trimmed);
        }

        if (startsWithIgnoreCase(trimmed, LEGACY_COMMAND_PREFIX)) {
            return normalizeCommand(trimmed.substring(LEGACY_COMMAND_PREFIX.length()));
        }

        if (startsWithIgnoreCase(trimmed, LEGACY_COMMAND_ALIAS_PREFIX)) {
            return normalizeCommand(trimmed.substring(LEGACY_COMMAND_ALIAS_PREFIX.length()));
        }

        return Optional.empty();
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }

        final List<BindingEntry> parsedBindings = new ArrayList<>();
        for (final String entry : KEYBINDS.get()) {
            final int splitIndex = entry.indexOf('=');
            if (splitIndex <= 0 || splitIndex >= entry.length() - 1) {
                continue;
            }

            final String action = canonicalActionName(entry.substring(0, splitIndex));
            final String combo = canonicalComboExpression(entry.substring(splitIndex + 1));
            if (!action.isEmpty() && !combo.isEmpty()) {
                parsedBindings.add(new BindingEntry(action, combo));
            }
        }

        bindings = List.copyOf(parsedBindings);
        revision++;
    }

    private static boolean persistBindings(final List<BindingEntry> updatedBindings) {
        final List<String> serializedEntries = new ArrayList<>(updatedBindings.size());
        for (final BindingEntry entry : updatedBindings) {
            serializedEntries.add(entry.action() + "=" + entry.combo());
        }

        try {
            KEYBINDS.set(serializedEntries);
            KEYBINDS.save();
            bindings = List.copyOf(updatedBindings);
            revision++;
            return true;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private static String normalizeActionName(final String actionName) {
        if (actionName == null) {
            return "";
        }

        final String trimmed = actionName.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        final Optional<String> command = extractCommandFromAction(trimmed);
        if (command.isPresent()) {
            return command.get();
        }

        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String normalizeComboExpression(final String comboExpression) {
        if (comboExpression == null) {
            return "";
        }

        return comboExpression.trim().toUpperCase(Locale.ROOT);
    }

    private static Optional<String> normalizeCommand(final String rawCommand) {
        if (rawCommand == null) {
            return Optional.empty();
        }

        final String trimmedCommand = rawCommand.trim();
        if (trimmedCommand.isEmpty()) {
            return Optional.empty();
        }

        if (trimmedCommand.startsWith("/")) {
            return Optional.of(trimmedCommand);
        }

        return Optional.of("/" + trimmedCommand);
    }

    private static boolean startsWithIgnoreCase(final String value, final String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
