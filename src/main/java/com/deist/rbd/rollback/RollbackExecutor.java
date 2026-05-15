package com.deist.rbd.rollback;

import com.deist.rbd.checkpoint.CheckpointData;
import com.deist.rbd.checkpoint.CheckpointManager;
import com.deist.rbd.core.RbdMod;
import com.deist.rbd.core.RbdStateManager;
import com.deist.rbd.log.RbdChangeLog;
import com.deist.rbd.mixin.RaidManagerAccessor;
import net.minecraft.block.entity.BlockEntity;
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
import net.minecraft.world.level.ServerWorldProperties;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
                    RegistryKey.of(RegistryKeys.WORLD, new Identifier(cp.dimension))
                );
                if (targetWorld == null) targetWorld = world.getServer().getOverworld();

                // === 1. ROLLBACK BLOCKS in ALL tracked dimensions ===
                for (RegistryKey<World> dimKey : new HashSet<>(RbdChangeLog.getAllTrackedDimensions())) {
                    ServerWorld dw = world.getServer().getWorld(dimKey);
                    if (dw != null) RbdChangeLog.get(dw).rollback(dw);
                }

                // === 2. ENTITY ROLLBACK ===
                // EntitySnapshot handles everything: loaded + unloaded chunks, all dimensions.
                // Steps internally: discard all non-player entities → spawn from snapshot.
                // Item entities are intentionally NOT in the snapshot → automatically cleared.
                RbdStateManager.setRollingBack(true);
                try {
                    cp.entitySnapshot.restore(world.getServer());
                } finally {
                    RbdStateManager.setRollingBack(false);
                }

                // === 3. RESTORE CONTAINER BLOCK ENTITIES ===
                for (NbtCompound beNbt : cp.blockEntitySnapshots) {
                    String dimId = beNbt.getString("RbdBeDim");
                    ServerWorld sw = world.getServer().getWorld(
                        RegistryKey.of(RegistryKeys.WORLD, new Identifier(dimId))
                    );
                    if (sw == null) continue;
                    BlockPos bePos = new BlockPos(
                        beNbt.getInt("RbdBeX"), beNbt.getInt("RbdBeY"), beNbt.getInt("RbdBeZ")
                    );
                    BlockEntity be = sw.getBlockEntity(bePos);
                    if (be != null) {
                        be.readNbt(beNbt);
                        be.markDirty();
                    }
                }

                // === 4. RESTORE ENDER CHEST ===
                if (cp.enderChestInventory.contains("Items")) {
                    player.getEnderChestInventory().readNbtList(
                        cp.enderChestInventory.getList("Items",
                            net.minecraft.nbt.NbtElement.COMPOUND_TYPE));
                }

                // === 5. WEATHER ===
                ServerWorldProperties wp =
                    (ServerWorldProperties) world.getServer().getOverworld().getLevelProperties();
                wp.setClearWeatherTime(cp.clearWeatherTime);
                wp.setRainTime(cp.rainTime);
                wp.setThunderTime(cp.thunderTime);
                wp.setRaining(cp.raining);
                wp.setThundering(cp.thundering);

                // === 6. WORLD TIME ===
                targetWorld.setTimeOfDay(cp.worldTime);

                // === 7. CANCEL ALL RAIDS ===
                world.getServer().getWorlds().forEach(sw -> {
                    RaidManager rm = sw.getRaidManager();
                    if (rm != null) {
                        try {
                            RaidManagerAccessor accessor = (RaidManagerAccessor) rm;
                            List<Raid> raidList = new ArrayList<>(accessor.getRaids().values());
                            raidList.forEach(Raid::invalidate);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
                player.removeStatusEffect(net.minecraft.entity.effect.StatusEffects.BAD_OMEN);
                player.removeStatusEffect(net.minecraft.entity.effect.StatusEffects.HERO_OF_THE_VILLAGE);

                // === 8. RESET MOB ANGER ===
                world.getServer().getWorlds().forEach(sw -> {
                    sw.iterateEntities().forEach(entity -> {
                        if (entity instanceof Angerable angerable) {
                            angerable.stopAnger();
                            if (entity instanceof MobEntity mob) mob.setTarget(null);
                        }
                    });
                });

                // === 9. PLAYER RESTORE ===
                double safeY = findSafeY(targetWorld, cp.pos);
                player.teleport(targetWorld,
                    cp.pos.getX() + 0.5, safeY, cp.pos.getZ() + 0.5,
                    cp.yaw, cp.pitch);
                player.setHealth(cp.health);
                player.getHungerManager().setFoodLevel(cp.hunger);
                player.getHungerManager().setSaturationLevel(cp.saturation);
                player.experienceLevel = cp.experienceLevel;
                player.experienceProgress = cp.experienceProgress;
                player.getSculkShriekerWarningManager().ifPresent(
                    manager -> manager.setWarningLevel(cp.sculkWarningLevel));

                player.getInventory().clear();
                for (int i = 0; i < cp.inventory.size(); i++) {
                    player.getInventory().setStack(i, cp.inventory.get(i).copy());
                }

                player.clearStatusEffects();
                for (NbtCompound effectNbt : cp.statusEffects) {
                    StatusEffectInstance effect = StatusEffectInstance.fromNbt(effectNbt);
                    if (effect != null) player.addStatusEffect(effect);
                }

                // === 10. FINALIZE ===
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
     * Find a safe Y to teleport the player to, accounting for non-full blocks
     * (slabs, carpets, fences, etc.) at the checkpoint position.
     *
     * Strategy: look for the topmost collision shape surface starting at
     * checkpointPos.Y - 1 up to checkpointPos.Y + 2.
     */
    private static double findSafeY(ServerWorld world, BlockPos checkpointPos) {
        for (int dy = 0; dy <= 2; dy++) {
            BlockPos feet = checkpointPos.up(dy);
            BlockPos head = feet.up(1);

            net.minecraft.block.BlockState feetState = world.getBlockState(feet);
            net.minecraft.block.BlockState headState = world.getBlockState(head);

            boolean feetClear = !feetState.isSolidBlock(world, feet);
            boolean headClear = !headState.isSolidBlock(world, head);

            if (feetClear && headClear) {
                // Room to stand — figure out exact Y from the block below
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
        // Fallback: one block above checkpoint (safe even if block is full)
        return checkpointPos.getY() + 1.0;
    }
}
