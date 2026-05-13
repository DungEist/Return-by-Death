package com.deist.rbd.core;

import com.deist.rbd.effects.MiasmaVisualHandler;
import com.deist.rbd.effects.RbdClientEffects;
import com.deist.rbd.effects.RbdNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

public class RbdClientEntrypoint implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(RbdNetworking.ROLLBACK_START_ID, (client, handler, buf, responseSender) -> {
            client.execute(RbdClientEffects::startRollback);
        });

        ClientPlayNetworking.registerGlobalReceiver(RbdNetworking.ROLLBACK_COMPLETE_ID, (client, handler, buf, responseSender) -> {
            int miasmaLevel = buf.readInt();
            int loopNumber  = buf.readInt();
            client.execute(() -> {
                RbdClientEffects.onRollbackComplete(miasmaLevel, loopNumber);
                MiasmaVisualHandler.onRbdComplete(miasmaLevel);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(RbdNetworking.MIASMA_SYNC_ID, (client, handler, buf, responseSender) -> {
            int level    = buf.readInt();
            int maxLevel = buf.readInt();
            client.execute(() -> MiasmaVisualHandler.setLevel(level, maxLevel));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            RbdClientEffects.tick();
            MiasmaVisualHandler.tick();
        });

        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            RbdClientEffects.renderOverlay(drawContext, tickDelta);
            MiasmaVisualHandler.renderVignette(drawContext);
        });
    }
}
