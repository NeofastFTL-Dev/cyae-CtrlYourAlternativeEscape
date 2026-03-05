package com.nftl.cyae.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.nftl.cyae.Cyae;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = Cyae.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModKeyMappings {
    public static final String CATEGORY = "key.categories.cyae";
    public static final KeyMapping OPEN_KEYBIND_EDITOR = new KeyMapping(
            "key.cyae.open_keybind_editor",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            CATEGORY
    );

    private ModKeyMappings() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(final RegisterKeyMappingsEvent event) {
        event.register(OPEN_KEYBIND_EDITOR);
    }
}
