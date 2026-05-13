package com.deist.rbd.mixin;

import com.deist.rbd.checkpoint.CheckpointManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public class SpawnPointMixin {

    @Inject(method = "setSpawnPoint", at = @At("RETURN"))
    private void onSetSpawnPoint(RegistryKey<World> dimension, BlockPos pos, float angle, boolean forced, boolean sendMessage, CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        // setSpawnPoint is called when interacting with beds/anchors or commands.
        // We trigger a checkpoint save here.
        if (pos != null) {
            CheckpointManager.save(player, player.getServerWorld());
        }
    }
}
