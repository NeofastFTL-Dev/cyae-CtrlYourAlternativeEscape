package com.nftl.cyae.client;

import net.minecraftforge.eventbus.api.Event;

public class ComboKeybindTriggeredEvent extends Event {
    private final String actionName;

    public ComboKeybindTriggeredEvent(final String actionName) {
        this.actionName = actionName;
    }

    public String getActionName() {
        return actionName;
    }
}
