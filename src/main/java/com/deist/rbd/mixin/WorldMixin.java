package com.deist.rbd.mixin;

import com.deist.rbd.core.RbdStateManager;
import com.deist.rbd.log.ChangeType;
import com.deist.rbd.log.RbdChangeLog;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public class WorldMixin {

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z", at = @At("HEAD"))
    private void onSetBlock(BlockPos pos, BlockState newState, int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> cir) {
        if (RbdStateManager.isRollingBack()) return;

        World world = (World) (Object) this;
        if (world.isClient()) return; // Chỉ log trên server

        ServerWorld serverWorld = (ServerWorld) world;
        BlockState oldState = world.getBlockState(pos);

        if (oldState.equals(newState)) return;

        NbtCompound beNbt = null;
        BlockEntity be = world.getBlockEntity(pos);
        if (be != null) {
            beNbt = be.createNbt();
        }

        ChangeType cause = RbdStateManager.getCurrentCause();
        if (cause == ChangeType.UNKNOWN) {
            cause = ChangeType.WORLD_GEN;
        }

        RbdChangeLog.get(serverWorld).record(pos, oldState, newState, beNbt, cause, serverWorld.getTime());
    }
}
