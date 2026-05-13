package com.deist.rbd.mixin;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.village.raid.Raid;
import net.minecraft.village.raid.RaidManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(RaidManager.class)
public interface RaidManagerAccessor {
    @Accessor("raids")
    Map<Integer, Raid> getRaids();
}
