package com.deist.rbd.journal;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.List;

/**
 * Death Journal — custom item that opens as a Written Book.
 * Data is stored in item NBT and backed up to disk by JournalManager.
 * Auto-restored to inventory after respawn if missing.
 */
public class DeathJournalItem extends Item {

    public static DeathJournalItem INSTANCE;

    /** Wrap a legacy-formatted string into the minimal JSON that WrittenBookItem expects. */
    private static String textToJson(String legacyText) {
        // Escape the string for JSON and wrap in a literal text node
        String escaped = legacyText
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n");
        return "{\"text\":\"" + escaped + "\"}";
    }

    public DeathJournalItem(Settings settings) {
        super(settings);
    }

    @Override
    public Text getName(ItemStack stack) {
        return Text.literal("§5Death Journal");
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        return true;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient && user instanceof ServerPlayerEntity player) {
            // Build a proper WrittenBook stack from journal data
            ItemStack book = buildBook(player);
            // Place book in hand FIRST, then force-sync the inventory slot to the client,
            // then send OpenWrittenBookS2CPacket — client reads the slot and sees the WrittenBook.
            player.setStackInHand(hand, book);
            player.playerScreenHandler.sendContentUpdates(); // ← critical: syncs slot before open
            player.networkHandler.sendPacket(
                new net.minecraft.network.packet.s2c.play.OpenWrittenBookS2CPacket(hand)
            );
            // Restore the Death Journal item next tick
            com.deist.rbd.core.RbdMod.scheduleTask(1, () -> {
                player.setStackInHand(hand, stack);
                player.playerScreenHandler.sendContentUpdates();
            });
        }
        return TypedActionResult.success(stack);
    }

    /** Build a Written Book ItemStack from the journal entries of the player. */
    public static ItemStack buildBook(ServerPlayerEntity player) {
        List<JournalEntry> entries = JournalManager.loadEntries(player.server, player.getUuid());

        NbtList pages = new NbtList();

        // Cover page
        pages.add(NbtString.of(textToJson(
            "§5§lReturn by Death\n\n" +
            "§7Total loops: §f" + entries.size() + "\n\n" +
            "§8\"Only I remember...\""
        )));

        // One page per entry (newest first)
        for (int i = entries.size() - 1; i >= 0; i--) {
            JournalEntry e = entries.get(i);
            String dim = e.deathDim.replace("minecraft:", "");
            String page = String.format(
                "§5Loop #%d\n§7━━━━━━━━━━\n" +
                "§fTime: §7%s\n" +
                "§fCause: §7%s\n" +
                "§fAt: §7%.0f, %.0f, %.0f\n" +
                "§fDim: §7%s\n" +
                "§fMiasma: §7Lv.%d",
                e.loopNumber,
                e.formatSurvival(),
                e.formatCause(),
                e.deathX, e.deathY, e.deathZ,
                dim,
                e.miasmaLevel
            );
            pages.add(NbtString.of(textToJson(page)));
        }

        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        NbtCompound nbt = book.getOrCreateNbt();
        nbt.putString("title", "Death Journal");
        nbt.putString("author", player.getName().getString());
        nbt.putBoolean("resolved", true);
        nbt.putInt("generation", 0);
        nbt.put("pages", pages);
        return book;
    }

    /** Check if a player has a Death Journal in their inventory. */
    public static boolean hasJournal(PlayerEntity player) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.getItem() == INSTANCE) return true;
        }
        return false;
    }

    /** Give a fresh Death Journal to the player if they don't have one. */
    public static void ensureJournal(ServerPlayerEntity player) {
        if (!hasJournal(player)) {
            ItemStack journal = new ItemStack(INSTANCE);
            journal.setCustomName(Text.literal("§5Death Journal"));
            player.getInventory().insertStack(journal);
        }
    }
}
