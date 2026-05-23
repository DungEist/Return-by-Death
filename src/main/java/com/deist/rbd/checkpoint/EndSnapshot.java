package com.deist.rbd.checkpoint;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.boss.dragon.EnderDragonFight;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot riêng cho The End dimension.
 *
 * Logic:
 *   entrySnapshot  — chụp lần đầu tiên player BƯỚC VÀO The End.
 *                    Đây là snapshot được dùng để rollback.
 *                    Không bao giờ bị overwrite tự động.
 *
 *   exitPending    — chụp lần đầu tiên player ĐI RA khỏi The End qua End Portal.
 *                    Chưa overwrite entrySnapshot ngay.
 *                    Chỉ được commit vào entrySnapshot khi CheckpointManager.save()
 *                    được gọi (player set checkpoint mới).
 *
 * Ví dụ:
 *   Set CP → vào End (entrySnapshot chụp) → giết Dragon → ra Overworld
 *   (exitPending chụp) → chết trước khi set CP mới
 *   → rollback dùng entrySnapshot (Dragon còn sống, Crystal đủ) ✓
 *
 *   Set CP → vào End → giết Dragon → ra Overworld (exitPending) → set CP mới
 *   → exitPending commit thành entrySnapshot mới (Dragon chết, portal mở) ✓
 *
 * Bao gồm cả DragonFight state (NbtCompound) để restore đúng altar/exit portal.
 */
public class EndSnapshot {

    // Snapshot đang được dùng để rollback
    private NbtCompound entrySnapshot = null;
    private boolean entryTaken = false;

    // Snapshot chờ commit (chụp lúc ra End, chưa được set CP mới)
    private NbtCompound exitPending = null;

    // ── Singleton per server ───────────────────────────────────────────────────
    // Lưu trực tiếp trong CheckpointData, không cần singleton.

    // ── Capture ───────────────────────────────────────────────────────────────

    /**
     * Gọi khi player LẦN ĐẦU bước vào The End.
     * Nếu đã chụp rồi thì bỏ qua.
     */
    public void captureEntry(MinecraftServer server) {
        if (entryTaken) return;
        entrySnapshot = captureEnd(server);
        entryTaken = true;
        net.minecraft.server.world.ServerWorld endWorld = server.getWorld(net.minecraft.world.World.END);
        if (endWorld != null) {
            com.deist.rbd.log.RbdChangeLog.get(endWorld).clear();
            System.out.println("[RbD] EndSnapshot: cleared End block change log");
        }
        System.out.println("[RbD] EndSnapshot: captured entry snapshot");
    }

    /**
     * Gọi khi player LẦN ĐẦU đi ra khỏi The End qua End Portal.
     * Lưu vào exitPending, chưa overwrite entrySnapshot.
     * Nếu đã có exitPending rồi thì bỏ qua (chỉ lấy lần đầu).
     */
    public void captureExit(MinecraftServer server) {
        if (exitPending != null) return;
        exitPending = captureEnd(server);
        System.out.println("[RbD] EndSnapshot: captured exit snapshot (pending commit)");
    }

    /**
     * Commit exitPending vào entrySnapshot.
     * Gọi khi CheckpointManager.save() được gọi (player set checkpoint mới).
     * Sau đó reset exitPending.
     */
    public void commitExit() {
        if (exitPending != null) {
            entrySnapshot = exitPending;
            exitPending = null;
            System.out.println("[RbD] EndSnapshot: exit snapshot committed to entry");
        }
    }

    /**
     * Reset hoàn toàn — dùng khi player set checkpoint mới từ đầu
     * (ví dụ checkpoint mới ở Overworld khi chưa từng vào End).
     */
    public void reset() {
        entrySnapshot = null;
        entryTaken = false;
        exitPending = null;
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    public boolean hasEntrySnapshot() {
        return entrySnapshot != null;
    }

    public boolean hasEntryBeenTaken() {
        return entryTaken;
    }

    // ── Restore ───────────────────────────────────────────────────────────────

    /**
     * Restore The End về trạng thái entrySnapshot.
     * Bao gồm: entities (Dragon, Crystal, ...) + DragonFight state.
     *
     * Nếu không có entrySnapshot (player chưa bao giờ vào The End trong checkpoint này)
     * thì skip hoàn toàn — không đụng vào The End.
     */
    public void restore(MinecraftServer server) {
        if (entrySnapshot == null) {
            System.out.println("[RbD] EndSnapshot: no entry snapshot, skipping The End restore");
            return;
        }

        ServerWorld endWorld = server.getWorld(World.END);
        if (endWorld == null) return;

        // ── 1. Build lookup từ snapshot ───────────────────────────────────────
        NbtList entityList = entrySnapshot.getList("Entities", NbtCompound.COMPOUND_TYPE);
        java.util.Map<java.util.UUID, NbtCompound> snapshotByUuid = new java.util.HashMap<>();
        for (int i = 0; i < entityList.size(); i++) {
            NbtCompound nbt = entityList.getCompound(i);
            if (nbt.containsUuid("UUID")) {
                snapshotByUuid.put(nbt.getUuid("UUID"), nbt.copy());
            }
        }

        // ── 2. Xử lý entities hiện có trong The End ───────────────────────────
        List<Entity> toDiscard = new ArrayList<>();
        endWorld.iterateEntities().forEach(entity -> {
            if (entity instanceof PlayerEntity) return;
            if (entity instanceof ItemEntity
                    || entity instanceof ExperienceOrbEntity
                    || entity instanceof ProjectileEntity) {
                toDiscard.add(entity);
                return;
            }

            java.util.UUID uuid = entity.getUuid();
            NbtCompound snap = snapshotByUuid.get(uuid);
            if (snap != null) {
                try {
                    entity.readNbt(snap);
                } catch (Exception e) {
                    System.err.println("[RbD] EndSnapshot: readNbt failed for " + uuid + ": " + e.getMessage());
                }
                snapshotByUuid.remove(uuid);
            } else {
                toDiscard.add(entity);
            }
        });

        for (Entity entity : toDiscard) {
            entity.setRemoved(Entity.RemovalReason.DISCARDED);
        }

        // ── 3. Spawn entities có trong snapshot nhưng không có trong world ─────
        for (NbtCompound nbt : snapshotByUuid.values()) {
            try {
                EntityType.loadEntityWithPassengers(nbt, endWorld, entity -> {
                    endWorld.spawnEntity(entity);
                    return entity;
                });
            } catch (Exception e) {
                System.err.println("[RbD] EndSnapshot: spawn failed: " + e.getMessage());
            }
        }

        // ── 4. Restore DragonFight state ──────────────────────────────────────
        if (entrySnapshot.contains("DragonFight")) {
            NbtCompound dragonFightNbt = entrySnapshot.getCompound("DragonFight");
            try {
                net.minecraft.entity.boss.dragon.EnderDragonFight.Data data = net.minecraft.entity.boss.dragon.EnderDragonFight.Data.CODEC
                        .parse(net.minecraft.nbt.NbtOps.INSTANCE, dragonFightNbt)
                        .resultOrPartial(System.err::println)
                        .orElse(null);

                if (data != null) {
                    EnderDragonFight oldFight = endWorld.getEnderDragonFight();
                    if (oldFight != null) {
                        try {
                            ((com.deist.rbd.mixin.EnderDragonFightAccessor) oldFight).getBossBar().clearPlayers();
                            System.out.println("[RbD] EndSnapshot: cleared old DragonFight boss bar players");
                        } catch (Exception e) {
                            System.err.println("[RbD] EndSnapshot: failed to clear old boss bar: " + e.getMessage());
                        }
                    }
                    endWorld.setEnderDragonFight(new EnderDragonFight(endWorld, endWorld.getSeed(), data));
                    System.out.println("[RbD] EndSnapshot: DragonFight state restored");
                }
            } catch (Exception e) {
                System.err.println("[RbD] EndSnapshot: DragonFight restore failed: " + e.getMessage());
            }
        }

        System.out.println("[RbD] EndSnapshot: The End restored successfully");
    }

    // ── NBT serialization ─────────────────────────────────────────────────────

    public void writeNbt(NbtCompound out) {
        NbtCompound tag = new NbtCompound();
        tag.putBoolean("EntryTaken", entryTaken);
        if (entrySnapshot != null) tag.put("Entry", entrySnapshot.copy());
        if (exitPending != null) tag.put("ExitPending", exitPending.copy());
        out.put("EndSnapshot", tag);
    }

    public static EndSnapshot readNbt(NbtCompound in) {
        EndSnapshot snap = new EndSnapshot();
        if (!in.contains("EndSnapshot")) return snap;
        NbtCompound tag = in.getCompound("EndSnapshot");
        snap.entryTaken = tag.getBoolean("EntryTaken");
        if (tag.contains("Entry")) snap.entrySnapshot = tag.getCompound("Entry").copy();
        if (tag.contains("ExitPending")) snap.exitPending = tag.getCompound("ExitPending").copy();
        return snap;
    }

    // ── Internal capture ──────────────────────────────────────────────────────

    /**
     * Chụp toàn bộ state của The End: entities + DragonFight.
     * Force save trước để đảm bảo disk sync với memory.
     */
    private static NbtCompound captureEnd(MinecraftServer server) {
        ServerWorld endWorld = server.getWorld(World.END);
        NbtCompound snapshot = new NbtCompound();

        if (endWorld == null) return snapshot;

        // Force save The End để đảm bảo state mới nhất
        endWorld.getChunkManager().save(true);

        // Entities
        NbtList entityList = new NbtList();
        endWorld.iterateEntities().forEach(entity -> {
            if (entity instanceof PlayerEntity) return;
            if (entity instanceof ItemEntity
                    || entity instanceof ExperienceOrbEntity
                    || entity instanceof ProjectileEntity) return;
            try {
                NbtCompound nbt = new NbtCompound();
                entity.writeNbt(nbt);
                nbt.putString("id", EntityType.getId(entity.getType()).toString());
                entityList.add(nbt);
            } catch (Exception e) {
                System.err.println("[RbD] EndSnapshot: failed to serialize entity: " + e.getMessage());
            }
        });
        snapshot.put("Entities", entityList);

        // DragonFight state
        EnderDragonFight fight = endWorld.getEnderDragonFight();
        if (fight != null) {
            try {
                net.minecraft.entity.boss.dragon.EnderDragonFight.Data data = fight.toData();
                net.minecraft.nbt.NbtElement nbtElement = net.minecraft.entity.boss.dragon.EnderDragonFight.Data.CODEC
                        .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, data)
                        .resultOrPartial(System.err::println)
                        .orElse(null);

                if (nbtElement instanceof NbtCompound) {
                    snapshot.put("DragonFight", nbtElement);
                }
            } catch (Exception e) {
                System.err.println("[RbD] EndSnapshot: failed to serialize DragonFight: " + e.getMessage());
            }
        }

        return snapshot;
    }
}
