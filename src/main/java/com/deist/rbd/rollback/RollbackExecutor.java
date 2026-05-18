package com.deist.rbd.rollback;

import com.deist.rbd.checkpoint.CheckpointData;
import com.deist.rbd.checkpoint.CheckpointManager;
import com.deist.rbd.core.RbdMod;
import com.deist.rbd.core.RbdStateManager;
import com.deist.rbd.log.RbdChangeLog;
import com.deist.rbd.mixin.RaidManagerAccessor;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.raid.Raid;
import net.minecraft.village.raid.RaidManager;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class RollbackExecutor {

    public static void execute(ServerPlayerEntity player, ServerWorld world, int miasmaLevel) {
        CheckpointData cp = CheckpointManager.load(world);
        if (cp == null) return;

        player.setNoGravity(true);
        player.getAbilities().invulnerable = true;
        player.sendAbilitiesUpdate();
        RbdStateManager.setWaitingForRollback(player.getUuid(), true);

        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                com.deist.rbd.effects.RbdNetworking.ROLLBACK_START_ID,
                net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create());

        RbdMod.scheduleTask(40, () -> {
            world.getServer().execute(() -> {

                ServerWorld targetWorld = world.getServer().getWorld(
                        RegistryKey.of(RegistryKeys.WORLD,
                                new Identifier(cp.dimension)));
                if (targetWorld == null)
                    targetWorld = world.getServer().getOverworld();

                // ── 1. BLOCK ROLLBACK ─────────────────────────────────────────
                for (RegistryKey<World> dimKey :
                        new HashSet<>(RbdChangeLog.getAllTrackedDimensions())) {
                    ServerWorld dw = world.getServer().getWorld(dimKey);
                    if (dw != null) RbdChangeLog.get(dw).rollback(dw);
                }

                // ── 2. ENTITY ROLLBACK ────────────────────────────────────────
                RbdStateManager.setRollingBack(true);
                try {
                    // Overworld + Nether
                    cp.entitySnapshot.restore(world.getServer());

                    // The End — xử lý riêng bởi EndSnapshot
                    cp.endSnapshot.restore(world.getServer());
                } finally {
                    RbdStateManager.setRollingBack(false);
                }

                // ── 3. BLOCK ENTITY (CONTAINER) RESTORE ──────────────────────
                for (NbtCompound beNbt : cp.blockEntitySnapshots) {
                    String dimId = beNbt.getString("RbdBeDim");
                    ServerWorld sw = world.getServer().getWorld(
                            RegistryKey.of(RegistryKeys.WORLD,
                                    new Identifier(dimId)));
                    if (sw == null) continue;
                    BlockPos bePos = new BlockPos(
                            beNbt.getInt("RbdBeX"),
                            beNbt.getInt("RbdBeY"),
                            beNbt.getInt("RbdBeZ"));
                    net.minecraft.block.entity.BlockEntity be =
                            sw.getBlockEntity(bePos);
                    if (be != null) { be.readNbt(beNbt); be.markDirty(); }
                }

                // ── 4. ENDER CHEST ────────────────────────────────────────────
                if (cp.enderChestInventory.contains("Items")) {
                    player.getEnderChestInventory().readNbtList(
                            cp.enderChestInventory.getList("Items",
                                    net.minecraft.nbt.NbtElement.COMPOUND_TYPE));
                }

                // ── 5. WEATHER ────────────────────────────────────────────────
                net.minecraft.world.level.ServerWorldProperties wp =
                        (net.minecraft.world.level.ServerWorldProperties)
                        world.getServer().getOverworld().getLevelProperties();
                wp.setClearWeatherTime(cp.clearWeatherTime);
                wp.setRainTime(cp.rainTime);
                wp.setThunderTime(cp.thunderTime);
                wp.setRaining(cp.raining);
                wp.setThundering(cp.thundering);

                // ── 6. WORLD TIME ─────────────────────────────────────────────
                targetWorld.setTimeOfDay(cp.worldTime);

                // ── 7. RAIDS ──────────────────────────────────────────────────
                world.getServer().getWorlds().forEach(sw -> {
                    RaidManager rm = sw.getRaidManager();
                    if (rm == null) return;
                    try {
                        RaidManagerAccessor accessor = (RaidManagerAccessor) rm;
                        new ArrayList<>(accessor.getRaids().values())
                                .forEach(Raid::invalidate);
                    } catch (Exception e) { e.printStackTrace(); }
                });
                player.removeStatusEffect(
                        net.minecraft.entity.effect.StatusEffects.BAD_OMEN);
                player.removeStatusEffect(
                        net.minecraft.entity.effect.StatusEffects.HERO_OF_THE_VILLAGE);

                // ── 8. MOB ANGER RESET ────────────────────────────────────────
                world.getServer().getWorlds().forEach(sw ->
                    sw.iterateEntities().forEach(entity -> {
                        if (entity instanceof Angerable a) {
                            a.stopAnger();
                            if (entity instanceof MobEntity mob) mob.setTarget(null);
                        }
                    })
                );

                // ── 9. PLAYER RESTORE ─────────────────────────────────────────
                double safeY = findSafeY(targetWorld, cp.pos);
                player.teleport(targetWorld,
                        cp.pos.getX() + 0.5, safeY, cp.pos.getZ() + 0.5,
                        cp.yaw, cp.pitch);
                player.setHealth(cp.health);
                player.getHungerManager().setFoodLevel(cp.hunger);
                player.getHungerManager().setSaturationLevel(cp.saturation);
                player.experienceLevel    = cp.experienceLevel;
                player.experienceProgress = cp.experienceProgress;
                player.getSculkShriekerWarningManager().ifPresent(
                        m -> m.setWarningLevel(cp.sculkWarningLevel));

                player.getInventory().clear();
                for (int i = 0; i < cp.inventory.size(); i++)
                    player.getInventory().setStack(i, cp.inventory.get(i).copy());

                player.clearStatusEffects();
                for (NbtCompound en : cp.statusEffects) {
                    StatusEffectInstance effect = StatusEffectInstance.fromNbt(en);
                    if (effect != null) player.addStatusEffect(effect);
                }

                // ── 10. FINALIZE ──────────────────────────────────────────────
                cp.loopNumber++;
                final ServerWorld ft = targetWorld;
                CheckpointManager.save(player, ft);

                player.setNoGravity(false);
                player.getAbilities().invulnerable = false;
                player.sendAbilitiesUpdate();
                RbdStateManager.setWaitingForRollback(player.getUuid(), false);
                player.setHealth(cp.health);

                net.minecraft.network.PacketByteBuf buf =
                        net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
                buf.writeInt(miasmaLevel);
                buf.writeInt(cp.loopNumber);
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                        com.deist.rbd.effects.RbdNetworking.ROLLBACK_COMPLETE_ID, buf);

                com.deist.rbd.journal.DeathJournalItem.ensureJournal(player);
            });
        });
    }

    /**
     * Tìm Y an toàn để teleport player, tránh rơi xuyên block không đầy đủ
     * (slab, carpet, fence, v.v.).
     */
    private static double findSafeY(ServerWorld world, BlockPos checkpointPos) {
        for (int dy = 0; dy <= 2; dy++) {
            BlockPos feet = checkpointPos.up(dy);
            BlockPos head = feet.up(1);

            net.minecraft.block.BlockState feetState = world.getBlockState(feet);
            net.minecraft.block.BlockState headState = world.getBlockState(head);

            if (!feetState.isSolidBlock(world, feet)
                    && !headState.isSolidBlock(world, head)) {
                BlockPos below = feet.down();
                net.minecraft.block.BlockState belowState = world.getBlockState(below);
                if (!belowState.isAir()) {
                    var shape = belowState.getCollisionShape(world, below);
                    if (!shape.isEmpty()) {
                        return below.getY() + shape.getBoundingBox().maxY;
                    }
                }
                return feet.getY();
            }
        }
        return checkpointPos.getY() + 1.0;
    }
}
