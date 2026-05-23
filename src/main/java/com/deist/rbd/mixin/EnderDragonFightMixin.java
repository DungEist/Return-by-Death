package com.deist.rbd.mixin;

import com.deist.rbd.core.RbdStateManager;
import net.minecraft.entity.boss.dragon.EnderDragonFight;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EnderDragonFight.class)
public class EnderDragonFightMixin {

    @Inject(method = "generateEndPortal", at = @At("HEAD"))
    private void preGenerateEndPortal(boolean previouslyKilled, CallbackInfo ci) {
        RbdStateManager.setCodeGenerating(true);
    }

    @Inject(method = "generateEndPortal", at = @At("TAIL"))
    private void postGenerateEndPortal(boolean previouslyKilled, CallbackInfo ci) {
        RbdStateManager.setCodeGenerating(false);
    }

    @Inject(method = "generateNewEndGateway", at = @At("HEAD"))
    private void preGenerateNewEndGateway(CallbackInfo ci) {
        RbdStateManager.setCodeGenerating(true);
    }

    @Inject(method = "generateNewEndGateway", at = @At("TAIL"))
    private void postGenerateNewEndGateway(CallbackInfo ci) {
        RbdStateManager.setCodeGenerating(false);
    }

    @Inject(method = "generateEndGateway", at = @At("HEAD"))
    private void preGenerateEndGateway(BlockPos pos, CallbackInfo ci) {
        RbdStateManager.setCodeGenerating(true);
    }

    @Inject(method = "generateEndGateway", at = @At("TAIL"))
    private void postGenerateEndGateway(BlockPos pos, CallbackInfo ci) {
        RbdStateManager.setCodeGenerating(false);
    }
}
