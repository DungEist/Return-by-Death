package com.deist.rbd.rollback;

import com.deist.rbd.checkpoint.CheckpointData;
import com.deist.rbd.checkpoint.CheckpointManager;
import com.deist.rbd.core.RbdMod;
import com.deist.rbd.core.RbdPendingDeletions;
import com.deist.rbd.core.RbdStateManager;
import com.deist.rbd.log.RbdChangeLog;
import com.deist.rbd.log.RbdEntityLog;
import com.deist.rbd.mixin.RaidManagerAccessor;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EyeOfEnderEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
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

import java.util.*;

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
                // Build UUID sets from checkpoint snapshot
                Set<UUID> checkpointUuids = new HashSet<>();
                for (NbtCompound snap : cp.entitySnapshots) {
                    if (snap.containsUuid("UUID")) checkpointUuids.add(snap.getUuid("UUID"));
                }

                RbdStateManager.setRollingBack(true);
                try {
                    world.getServer().getWorlds().forEach(sw -> {
                        RbdEntityLog log = RbdEntityLog.get(sw);

                        // Step A: Remove entities SPAWNED after checkpoint
                        Set<UUID> spawnedUuids = log.getSpawnedUuids();
                        RbdPendingDeletions.addAll(spawnedUuids);
                        List<Entity> toRemove = new ArrayList<>();
                        sw.iterateEntities().forEach(entity -> {
                            if (entity instanceof PlayerEntity) return;
                            if (spawnedUuids.contains(entity.getUuid())) toRemove.add(entity);
                            if (entity instanceof ProjectileEntity || entity instanceof EyeOfEnderEntity)
                                toRemove.add(entity);
                        });
                        toRemove.forEach(e -> {
                            e.discard();
                            RbdPendingDeletions.remove(e.getUuid());
                        });

                        // Step B: Re-apply checkpoint NBT for entities still alive
                        Map<UUID, NbtCompound> snapshotByUuid = new HashMap<>();
                        for (NbtCompound snap : cp.entitySnapshots) {
                            if (snap.containsUuid("UUID")) {
                                String snapDim = snap.getString("RbdDim");
                                if (new Identifier(snapDim).equals(sw.getRegistryKey().getValue())) {
                                    snapshotByUuid.put(snap.getUuid("UUID"), snap);
                                }
                            }
                        }
                        sw.iterateEntities().forEach(entity -> {
                            if (entity instanceof PlayerEntity) return;
                            NbtCompound snap = snapshotByUuid.get(entity.getUuid());
                            if (snap != null) {
                                NbtCompound clean = snap.copy();
                                clean.remove("RbdDim");
                                entity.readNbt(clean);
                            }
                        });

                        // Step C: Respawn entities từ snapshot mà HIỆN KHÔNG TỒN TẠI trong world
                        for (NbtCompound snap : cp.entitySnapshots) {
                            if (!snap.containsUuid("UUID")) continue;
                            String snapDim = snap.getString("RbdDim");
                            if (!new Identifier(snapDim).equals(sw.getRegistryKey().getValue())) continue;
                            
                            UUID snapUuid = snap.getUuid("UUID");
                            if (spawnedUuids.contains(snapUuid)) continue; // spawned after checkpoint, đã xóa
                            
                            Entity existing = sw.getEntity(snapUuid);
                            if (existing == null) {
                                // Entity có trong checkpoint nhưng đã bị kill/remove → respawn
                                NbtCompound clean = snap.copy();
                                clean.remove("RbdDim");
                                EntityType.loadEntityWithPassengers(clean, sw, entity -> {
                                    sw.spawnEntity(entity);
                                    return entity;
                                });
                            }
                        }

                        // Step D: Restore entities KILLED after checkpoint (từ delta log — cho unloaded chunk entities)
                        log.restoreKilled(sw, checkpointUuids);
                    });
                } finally {
                    RbdStateManager.setRollingBack(false);
                }

                // === 3. ITEM ENTITIES ===
                world.getServer().getWorlds().forEach(sw -> {
                    sw.getEntitiesByType(EntityType.ITEM, e -> true).forEach(Entity::discard);
                    sw.getEntitiesByType(EntityType.EXPERIENCE_ORB, e -> true).forEach(Entity::discard);
                });
                RbdStateManager.setRollingBack(true);
                try {
                    for (NbtCompound itemNbt : cp.itemEntitySnapshots) {
                        String dimId = itemNbt.getString("RbdDim");
                        ServerWorld sw = world.getServer().getWorld(
                            RegistryKey.of(RegistryKeys.WORLD, new Identifier(dimId))
                        );
                        if (sw == null) sw = targetWorld;
                        NbtCompound clean = itemNbt.copy();
                        clean.remove("RbdDim");
                        final ServerWorld fsw = sw;
                        EntityType.loadEntityWithPassengers(clean, fsw, entity -> {
                            fsw.spawnEntity(entity);
                            return entity;
                        });
                    }
                } finally {
                    RbdStateManager.setRollingBack(false);
                }

                // === 4. RESTORE CONTAINER BLOCK ENTITIES ===
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

                // === 5. RESTORE ENDER CHEST ===
                if (cp.enderChestInventory.contains("Items")) {
                    player.getEnderChestInventory().readNbtList(cp.enderChestInventory.getList("Items", net.minecraft.nbt.NbtElement.COMPOUND_TYPE));
                }

                // === 6. WEATHER ===
                ServerWorldProperties wp = (ServerWorldProperties) world.getServer().getOverworld().getLevelProperties();
                wp.setClearWeatherTime(cp.clearWeatherTime);
                wp.setRainTime(cp.rainTime);
                wp.setThunderTime(cp.thunderTime);
                wp.setRaining(cp.raining);
                wp.setThundering(cp.thundering);

                // === 7. WORLD TIME ===
                targetWorld.setTimeOfDay(cp.worldTime);

                // === 8. CANCEL ALL RAIDS ===
                // === 8. CANCEL ALL RAIDS ===
                // Invalidate each raid — do NOT call raids.clear() directly,
                // that corrupts RaidManager state causing random mob vanishing.
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

                // === 9. RESET MOB ANGER ===
                // (already handled by entity respawn from clean snapshots,
                //  but also reset any leftover entities that weren't in snapshot)
                world.getServer().getWorlds().forEach(sw -> {
                    sw.iterateEntities().forEach(entity -> {
                        if (entity instanceof Angerable angerable) {
                            angerable.stopAnger();
                            if (entity instanceof MobEntity mob) mob.setTarget(null);
                        }
                    });
                });

                // === 10. PLAYER RESTORE ===
                double safeY = findSafeY(targetWorld, cp.pos);
                player.teleport(targetWorld, cp.pos.getX() + 0.5, safeY, cp.pos.getZ() + 0.5, cp.yaw, cp.pitch);
                player.setHealth(cp.health);
                player.getHungerManager().setFoodLevel(cp.hunger);
                player.getHungerManager().setSaturationLevel(cp.saturation);
                player.experienceLevel = cp.experienceLevel;
                player.experienceProgress = cp.experienceProgress;
                player.getSculkShriekerWarningManager().ifPresent(manager -> manager.setWarningLevel(cp.sculkWarningLevel));

                player.getInventory().clear();
                for (int i = 0; i < cp.inventory.size(); i++) {
                    player.getInventory().setStack(i, cp.inventory.get(i).copy());
                }

                player.clearStatusEffects();
                for (NbtCompound effectNbt : cp.statusEffects) {
                    StatusEffectInstance effect = StatusEffectInstance.fromNbt(effectNbt);
                    if (effect != null) player.addStatusEffect(effect);
                }

                // === 11. FINALIZE ===
                cp.loopNumber++;
                final ServerWorld ft = targetWorld;

                CheckpointManager.save(player, ft);

                player.setNoGravity(false);
                player.getAbilities().invulnerable = false;
                player.sendAbilitiesUpdate();
                RbdStateManager.setWaitingForRollback(player.getUuid(), false);
                player.setHealth(cp.health);

                net.minecraft.network.PacketByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
                buf.writeInt(miasmaLevel);
                buf.writeInt(cp.loopNumber); // needed for Aishiteru trigger on client
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                        com.deist.rbd.effects.RbdNetworking.ROLLBACK_COMPLETE_ID, buf);

                // Restore journal if it was lost from inventory
                com.deist.rbd.journal.DeathJournalItem.ensureJournal(player);
            });
        });
    }

    private static double findSafeY(ServerWorld world, BlockPos checkpointPos) {
        // Thử đứng trên block tại checkpoint pos
        // Tìm Y cao nhất mà không phải solid block trong khoảng ±2 block
        for (int dy = 0; dy <= 2; dy++) {
            BlockPos feet = checkpointPos.up(dy);
            BlockPos head = feet.up(1);
            net.minecraft.block.BlockState feetState = world.getBlockState(feet);
            net.minecraft.block.BlockState headState = world.getBlockState(head);
            
            if (!feetState.isSolidBlock(world, feet) && !headState.isSolidBlock(world, head)) {
                // Đứng được ở đây — tính Y chính xác dựa trên collision shape của block bên dưới
                BlockPos below = feet.down();
                net.minecraft.block.BlockState belowState = world.getBlockState(below);
                if (!belowState.isAir()) {
                    // Lấy top của block bên dưới
                    var shapes = belowState.getCollisionShape(world, below);
                    if (!shapes.isEmpty()) {
                        return below.getY() + shapes.getBoundingBox().maxY;
                    }
                }
                return feet.getY();
            }
        }
        // Fallback: đứng trên đỉnh block checkpoint
        return checkpointPos.getY() + 1.0;
    }
}
