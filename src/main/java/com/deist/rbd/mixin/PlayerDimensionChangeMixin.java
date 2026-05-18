package com.deist.rbd.mixin;

import com.deist.rbd.checkpoint.CheckpointManager;
import com.deist.rbd.checkpoint.EndSnapshot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Detect player vào/ra The End để trigger EndSnapshot.
 *
 * Vào The End lần đầu  → captureEntry()
 * Ra khỏi The End lần đầu → captureExit()
 */
@Mixin(ServerPlayerEntity.class)
public class PlayerDimensionChangeMixin {

    @Inject(
        method = "moveToWorld",
        at = @At("TAIL")
    )
    private void onMoveToWorld(ServerWorld destination, CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity)(Object) this;
        ServerWorld origin = player.getServerWorld();

        EndSnapshot endSnap = CheckpointManager.getEndSnapshot();
        if (endSnap == null) return;

        boolean comingFromEnd = origin.getRegistryKey() == World.END;
        boolean goingToEnd   = destination.getRegistryKey() == World.END;

        if (goingToEnd && !endSnap.hasEntryBeenTaken()) {
            // Lần đầu vào The End — chụp ngay trước khi player load vào
            // Dùng destination world vì player chưa teleport xong
            endSnap.captureEntry(player.getServer());
        }

        if (comingFromEnd && destination.getRegistryKey() != World.END) {
            // Ra khỏi The End lần đầu qua End Portal
            endSnap.captureExit(player.getServer());
        }
    }
}
