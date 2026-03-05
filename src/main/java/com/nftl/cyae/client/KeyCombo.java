package com.nftl.cyae.client;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class KeyCombo {
    private static final String GLFW_PREFIX = "GLFW_KEY_";
    private static final String GLFW_MOUSE_PREFIX = "GLFW_MOUSE_BUTTON_";
    private static final int MOUSE_CODE_OFFSET = 10_000;
    private static final Map<String, Set<Integer>> TOKEN_TO_INPUTS = buildInputLookup();

    private final List<Set<Integer>> requiredInputs;

    private KeyCombo(final List<Set<Integer>> requiredInputs) {
        this.requiredInputs = List.copyOf(requiredInputs);
    }

    public static Optional<KeyCombo> parse(final String expression) {
        if (expression == null || expression.isBlank()) {
            return Optional.empty();
        }

        final String[] rawTokens = expression.split("\\+");
        final List<Set<Integer>> requiredInputs = new ArrayList<>(rawTokens.length);
        for (final String rawToken : rawTokens) {
            final String token = normalizeToken(rawToken);
            if (token.isEmpty()) {
                return Optional.empty();
            }

            final Set<Integer> matchingInputs = TOKEN_TO_INPUTS.get(token);
            if (matchingInputs == null) {
                return Optional.empty();
            }

            requiredInputs.add(matchingInputs);
        }

        if (requiredInputs.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new KeyCombo(requiredInputs));
    }

    public boolean matches(final Set<Integer> pressedInputs) {
        for (final Set<Integer> acceptedInputs : requiredInputs) {
            boolean satisfied = false;
            for (final int inputCode : acceptedInputs) {
                if (pressedInputs.contains(inputCode)) {
                    satisfied = true;
                    break;
                }
            }

            if (!satisfied) {
                return false;
            }
        }

        return true;
    }

    public boolean matches(final long windowHandle) {
        for (final Set<Integer> acceptedInputs : requiredInputs) {
            boolean satisfied = false;
            for (final int inputCode : acceptedInputs) {
                if (isInputDown(windowHandle, inputCode)) {
                    satisfied = true;
                    break;
                }
            }

            if (!satisfied) {
                return false;
            }
        }

        return true;
    }

    public static int keyInputCode(final int keyCode) {
        return keyCode;
    }

    public static int mouseInputCode(final int mouseButton) {
        return MOUSE_CODE_OFFSET + mouseButton;
    }

    public boolean includesInput(final InputConstants.Key key) {
        if (key == null || key == InputConstants.UNKNOWN) {
            return false;
        }

        final int inputCode;
        final InputConstants.Type type = key.getType();
        if (type == InputConstants.Type.MOUSE) {
            inputCode = mouseInputCode(key.getValue());
        } else if (type == InputConstants.Type.KEYSYM || type == InputConstants.Type.SCANCODE) {
            inputCode = keyInputCode(key.getValue());
        } else {
            return false;
        }

        for (final Set<Integer> acceptedInputs : requiredInputs) {
            if (acceptedInputs.contains(inputCode)) {
                return true;
            }
        }

        return false;
    }

    public boolean matchesRecentPresses(final Map<Integer, Long> recentPressTimes, final long nowMillis, final long windowMillis) {
        final long minAllowedPress = nowMillis - windowMillis;
        for (final Set<Integer> acceptedInputs : requiredInputs) {
            boolean satisfied = false;
            for (final int inputCode : acceptedInputs) {
                final Long pressedAt = recentPressTimes.get(inputCode);
                if (pressedAt != null && pressedAt >= minAllowedPress && pressedAt <= nowMillis) {
                    satisfied = true;
                    break;
                }
            }

            if (!satisfied) {
                return false;
            }
        }

        return true;
    }

    private static Map<String, Set<Integer>> buildInputLookup() {
        final Map<String, Set<Integer>> lookup = new HashMap<>();
        for (final Field field : GLFW.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            try {
                if (field.getName().startsWith(GLFW_PREFIX)) {
                    final int keyCode = field.getInt(null);
                    if (keyCode == GLFW.GLFW_KEY_UNKNOWN) {
                        continue;
                    }

                    final String keyName = field.getName().substring(GLFW_PREFIX.length());
                    lookup.put(keyName, Set.of(keyInputCode(keyCode)));
                } else if (field.getName().startsWith(GLFW_MOUSE_PREFIX)) {
                    final int mouseButton = field.getInt(null);
                    if (mouseButton < 0) {
                        continue;
                    }

                    final String buttonName = field.getName().substring(GLFW_MOUSE_PREFIX.length());
                    lookup.put(buttonName, Set.of(mouseInputCode(mouseButton)));
                }
            } catch (IllegalAccessException ignored) {
                // GLFW key constants are public; this path is defensive.
            }
        }

        addAlias(lookup, "CTRL", GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL);
        addAlias(lookup, "CONTROL", GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL);
        addAlias(lookup, "SHIFT", GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT);
        addAlias(lookup, "ALT", GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT);
        addAlias(lookup, "META", GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER);
        addAlias(lookup, "SUPER", GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER);
        addAlias(lookup, "WIN", GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER);
        addAlias(lookup, "CMD", GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER);
        addAlias(lookup, "ESC", GLFW.GLFW_KEY_ESCAPE);
        addAlias(lookup, "RETURN", GLFW.GLFW_KEY_ENTER);
        addAliasFromToken(lookup, "ENTER", "ENTER");
        addAliasFromToken(lookup, "BKSP", "BACKSPACE");
        addAliasFromToken(lookup, "DEL", "DELETE");
        addAliasFromToken(lookup, "PGUP", "PAGE_UP");
        addAliasFromToken(lookup, "PGDN", "PAGE_DOWN");
        addAliasFromToken(lookup, "INS", "INSERT");
        addAliasFromToken(lookup, "CAPSLOCK", "CAPS_LOCK");

        addAliasFromToken(lookup, "LCTRL", "LEFT_CONTROL");
        addAliasFromToken(lookup, "RCTRL", "RIGHT_CONTROL");
        addAliasFromToken(lookup, "LCONTROL", "LEFT_CONTROL");
        addAliasFromToken(lookup, "RCONTROL", "RIGHT_CONTROL");
        addAliasFromToken(lookup, "LEFTCTRL", "LEFT_CONTROL");
        addAliasFromToken(lookup, "RIGHTCTRL", "RIGHT_CONTROL");
        addAliasFromToken(lookup, "LSHIFT", "LEFT_SHIFT");
        addAliasFromToken(lookup, "RSHIFT", "RIGHT_SHIFT");
        addAliasFromToken(lookup, "LEFTSHIFT", "LEFT_SHIFT");
        addAliasFromToken(lookup, "RIGHTSHIFT", "RIGHT_SHIFT");
        addAliasFromToken(lookup, "LALT", "LEFT_ALT");
        addAliasFromToken(lookup, "RALT", "RIGHT_ALT");
        addAliasFromToken(lookup, "LEFTALT", "LEFT_ALT");
        addAliasFromToken(lookup, "RIGHTALT", "RIGHT_ALT");
        addAliasFromToken(lookup, "LMETA", "LEFT_SUPER");
        addAliasFromToken(lookup, "RMETA", "RIGHT_SUPER");
        addAliasFromToken(lookup, "LWIN", "LEFT_SUPER");
        addAliasFromToken(lookup, "RWIN", "RIGHT_SUPER");
        addAliasFromToken(lookup, "LCMD", "LEFT_SUPER");
        addAliasFromToken(lookup, "RCMD", "RIGHT_SUPER");

        addMouseAlias(lookup, "MOUSE_LEFT", GLFW.GLFW_MOUSE_BUTTON_LEFT);
        addMouseAlias(lookup, "MOUSE_RIGHT", GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        addMouseAlias(lookup, "MOUSE_MIDDLE", GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
        addMouseAlias(lookup, "LMB", GLFW.GLFW_MOUSE_BUTTON_LEFT);
        addMouseAlias(lookup, "RMB", GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        addMouseAlias(lookup, "MMB", GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
        addMouseAlias(lookup, "MOUSE1", GLFW.GLFW_MOUSE_BUTTON_1);
        addMouseAlias(lookup, "MOUSE2", GLFW.GLFW_MOUSE_BUTTON_2);
        addMouseAlias(lookup, "MOUSE3", GLFW.GLFW_MOUSE_BUTTON_3);
        addMouseAlias(lookup, "MOUSE4", GLFW.GLFW_MOUSE_BUTTON_4);
        addMouseAlias(lookup, "MOUSE5", GLFW.GLFW_MOUSE_BUTTON_5);
        addMouseAlias(lookup, "MOUSE6", GLFW.GLFW_MOUSE_BUTTON_6);
        addMouseAlias(lookup, "MOUSE7", GLFW.GLFW_MOUSE_BUTTON_7);
        addMouseAlias(lookup, "MOUSE8", GLFW.GLFW_MOUSE_BUTTON_8);
        addMouseAlias(lookup, "MB1", GLFW.GLFW_MOUSE_BUTTON_1);
        addMouseAlias(lookup, "MB2", GLFW.GLFW_MOUSE_BUTTON_2);
        addMouseAlias(lookup, "MB3", GLFW.GLFW_MOUSE_BUTTON_3);
        addMouseAlias(lookup, "MB4", GLFW.GLFW_MOUSE_BUTTON_4);
        addMouseAlias(lookup, "MB5", GLFW.GLFW_MOUSE_BUTTON_5);
        addMouseAlias(lookup, "MB6", GLFW.GLFW_MOUSE_BUTTON_6);
        addMouseAlias(lookup, "MB7", GLFW.GLFW_MOUSE_BUTTON_7);
        addMouseAlias(lookup, "MB8", GLFW.GLFW_MOUSE_BUTTON_8);

        return Map.copyOf(lookup);
    }

    private static void addAlias(final Map<String, Set<Integer>> lookup, final String alias, final int... keyCodes) {
        final Set<Integer> values = new HashSet<>();
        for (final int keyCode : keyCodes) {
            values.add(keyInputCode(keyCode));
        }

        lookup.put(alias, Set.copyOf(values));
    }

    private static void addMouseAlias(final Map<String, Set<Integer>> lookup, final String alias, final int mouseButton) {
        lookup.put(alias, Set.of(mouseInputCode(mouseButton)));
    }

    private static void addAliasFromToken(final Map<String, Set<Integer>> lookup, final String alias, final String canonicalToken) {
        final Set<Integer> existing = lookup.get(canonicalToken);
        if (existing != null) {
            lookup.put(alias, existing);
        }
    }

    private static boolean isInputDown(final long windowHandle, final int inputCode) {
        if (inputCode >= MOUSE_CODE_OFFSET) {
            final int mouseButton = inputCode - MOUSE_CODE_OFFSET;
            return GLFW.glfwGetMouseButton(windowHandle, mouseButton) == GLFW.GLFW_PRESS;
        }

        return GLFW.glfwGetKey(windowHandle, inputCode) == GLFW.GLFW_PRESS;
    }

    private static String normalizeToken(final String token) {
        String normalized = token.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (normalized.startsWith(GLFW_PREFIX)) {
            normalized = normalized.substring(GLFW_PREFIX.length());
        }
        if (normalized.startsWith("KEY_")) {
            normalized = normalized.substring("KEY_".length());
        }
        if (normalized.startsWith(GLFW_MOUSE_PREFIX)) {
            normalized = normalized.substring(GLFW_MOUSE_PREFIX.length());
        }
        if (normalized.startsWith("MOUSE_BUTTON_")) {
            normalized = normalized.substring("MOUSE_BUTTON_".length());
        }
        if (normalized.startsWith("BUTTON_")) {
            normalized = normalized.substring("BUTTON_".length());
        }
        return normalized;
    }
}
