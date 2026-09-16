package com.nstut.endless.vertical;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertSame;

class VerticalNetworkBridgeLifecycleTest {
    @Test
    void shutdownKeepsProcessGlobalSenderForNextIntegratedServerSession() throws Exception {
        Field senderField = VerticalNetworkBridge.class.getDeclaredField("sender");
        senderField.setAccessible(true);
        Object original = senderField.get(null);
        VerticalNetworkBridge.PageSender sender = (player, snapshot) -> {};
        try {
            VerticalNetworkBridge.registerSender(sender);
            VerticalNetworkBridge.shutdown();
            assertSame(sender, senderField.get(null),
                "server stop must not unregister the loader-global sparse-page transport");
        } finally {
            senderField.set(null, original);
        }
    }
}
