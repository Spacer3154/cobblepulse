package fr.cobblepulse.gui;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Vue sur les N premiers emplacements d'un conteneur (inventaire de clan 27 emplacements pour les non-VIP). */
public final class SubContainer implements Container {
    private final Container parent;
    private final int size;

    public SubContainer(Container parent, int size) {
        this.parent = parent;
        this.size = Math.min(size, parent.getContainerSize());
    }

    @Override
    public int getContainerSize() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < size; i++) if (!parent.getItem(i).isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot < size ? parent.getItem(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return slot < size ? parent.removeItem(slot, amount) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return slot < size ? parent.removeItemNoUpdate(slot) : ItemStack.EMPTY;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < size) parent.setItem(slot, stack);
    }

    @Override
    public void setChanged() {
        parent.setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < size; i++) parent.setItem(i, ItemStack.EMPTY);
    }
}
