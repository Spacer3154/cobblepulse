package fr.cobblepulse.gui;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;

import java.util.function.Consumer;
import java.util.function.Predicate;

/** Coffre modifiable (inventaire de clan, editeur de recompenses) avec controle d'acces continu. */
public final class SharedChestMenu extends ChestMenu {
    private final Predicate<Player> access;
    private final Consumer<Player> onClose;

    public SharedChestMenu(int id, Inventory inv, Container container, int rows, Predicate<Player> access, Consumer<Player> onClose) {
        super(LockedMenu.type(rows), id, inv, container, rows);
        this.access = access;
        this.onClose = onClose;
    }

    @Override
    public boolean stillValid(Player player) {
        return access.test(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (onClose != null) onClose.accept(player);
    }
}
