package com.deist.rbd.mixin;

import com.deist.rbd.core.RbdStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerEntity.class)
public class PlayerInteractionMixin {

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void onAttack(Entity target, CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        if (RbdStateManager.isWaitingForRollback(player.getUuid())) {
            ci.cancel();
        }
    }

    @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
    private void onDropItem(ItemStack stack, boolean throwRandomly, boolean retainOwnership, CallbackInfoReturnable<net.minecraft.entity.ItemEntity> cir) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        if (RbdStateManager.isWaitingForRollback(player.getUuid())) {
            cir.setReturnValue(null);
        }
    }

    // Block interaction with blocks and entities
    // We can also target the ServerPlayNetworkHandler but Mixin on Player is easier.
}
