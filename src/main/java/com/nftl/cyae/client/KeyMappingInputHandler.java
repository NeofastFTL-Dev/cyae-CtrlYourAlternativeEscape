package com.nftl.cyae.client;

import com.nftl.cyae.Cyae;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Cyae.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class KeyMappingInputHandler {
    private KeyMappingInputHandler() {
    }

    @SubscribeEvent
    public static void onClientTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        final Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isWindowActive()) {
            return;
        }

        if (minecraft.screen instanceof KeybindEditorScreen) {
            return;
        }

        while (ModKeyMappings.OPEN_KEYBIND_EDITOR.consumeClick()) {
            minecraft.setScreen(new KeybindEditorScreen(minecraft.screen));
        }
    }
}
