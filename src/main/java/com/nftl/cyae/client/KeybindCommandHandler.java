package com.nftl.cyae.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.nftl.cyae.ClientConfig;
import com.nftl.cyae.Cyae;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Locale;

@Mod.EventBusSubscriber(modid = Cyae.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class KeybindCommandHandler {
    private KeybindCommandHandler() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(final RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("cyae-keybind")
                        .then(Commands.literal("list").executes(context -> listBindings(context.getSource())))
                        .then(
                                Commands.literal("set")
                                        .then(
                                                Commands.argument("action", StringArgumentType.word())
                                                        .then(
                                                                Commands.argument("combo", StringArgumentType.greedyString())
                                                                        .executes(KeybindCommandHandler::setBinding)
                                                        )
                                        )
                        )
                        .then(
                                Commands.literal("remove")
                                        .then(
                                                Commands.argument("action", StringArgumentType.word())
                                                        .executes(KeybindCommandHandler::removeBinding)
                                        )
                        )
                        .then(Commands.literal("ui").executes(context -> openEditor(context.getSource())))
                        .then(Commands.literal("clear").executes(context -> clearBindings(context.getSource())))
        );
    }

    private static int listBindings(final CommandSourceStack source) {
        final List<ClientConfig.BindingEntry> bindings = ClientConfig.getBindings();
        if (bindings.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No combo keybinds configured."), false);
            return 1;
        }

        source.sendSuccess(() -> Component.literal("Configured combo keybinds:"), false);
        for (final ClientConfig.BindingEntry entry : bindings) {
            source.sendSuccess(() -> Component.literal(" - " + entry.action() + " = " + entry.combo()), false);
        }
        return bindings.size();
    }

    private static int setBinding(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final String action = normalizeActionName(StringArgumentType.getString(context, "action"));
        final String combo = normalizeComboExpression(StringArgumentType.getString(context, "combo"));

        if (!ClientConfig.isValidActionName(action)) {
            source.sendFailure(Component.literal("Invalid action name. Use a control action (jump/key.jump) or slash command (/spawn)."));
            return 0;
        }

        if (KeyCombo.parse(combo).isEmpty()) {
            source.sendFailure(Component.literal("Invalid combo: " + combo));
            return 0;
        }

        if (!ClientConfig.setBinding(action, combo)) {
            source.sendFailure(Component.literal("Unable to save keybind."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Added: " + action + " = " + combo), false);
        return 1;
    }

    private static int removeBinding(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final String action = normalizeActionName(StringArgumentType.getString(context, "action"));

        if (!ClientConfig.isValidActionName(action)) {
            source.sendFailure(Component.literal("Invalid action name."));
            return 0;
        }

        if (!ClientConfig.removeBinding(action)) {
            source.sendFailure(Component.literal("No keybind found for action: " + action));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Removed keybind(s) for action: " + action), false);
        return 1;
    }

    private static int clearBindings(final CommandSourceStack source) {
        ClientConfig.clearBindings();
        source.sendSuccess(() -> Component.literal("Cleared all combo keybinds."), false);
        return 1;
    }

    private static int openEditor(final CommandSourceStack source) {
        final Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreen(new KeybindEditorScreen(minecraft.screen)));
        source.sendSuccess(() -> Component.literal("Opened CYAE keybind editor."), false);
        return 1;
    }

    private static String normalizeActionName(final String actionName) {
        return ClientConfig.canonicalActionName(actionName);
    }

    private static String normalizeComboExpression(final String comboExpression) {
        return comboExpression.trim().toUpperCase(Locale.ROOT);
    }
}
