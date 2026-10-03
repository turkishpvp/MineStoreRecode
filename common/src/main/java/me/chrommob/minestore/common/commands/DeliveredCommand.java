package me.chrommob.minestore.common.commands;

import me.chrommob.minestore.api.interfaces.commands.CommonConsoleUser;
import me.chrommob.minestore.api.interfaces.user.AbstractUser;
import me.chrommob.minestore.common.MineStoreCommon;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;

/**
 * TurkishPvP: the receiving end of a network delivery.
 *
 * Cevher runs this from the console once it has applied a store command (or
 * found it already applied). Only then is the command reported to the store as
 * executed; see {@link me.chrommob.minestore.common.commandHolder.NetworkDeliveries}.
 * Console only: a player who could run it would be able to stop retries of
 * someone else's purchase.
 */
@SuppressWarnings("unused")
public class DeliveredCommand {
    private final MineStoreCommon plugin;

    public DeliveredCommand(MineStoreCommon plugin) {
        this.plugin = plugin;
    }

    @Permission("minestore.delivered")
    @Command("minestore|ms delivered <id>")
    public void onDelivered(AbstractUser abstractUser, @Argument("id") int id) {
        if (!(abstractUser.commonUser() instanceof CommonConsoleUser)) {
            return;
        }
        if (plugin.commandStorage() == null) {
            plugin.log("Delivery " + id + " was confirmed before the store connection was ready; it will be retried and confirmed again.");
            return;
        }
        plugin.commandStorage().confirmNetworkDelivery(id);
    }
}
