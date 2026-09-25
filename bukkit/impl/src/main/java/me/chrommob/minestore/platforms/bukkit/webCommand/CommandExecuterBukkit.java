package me.chrommob.minestore.platforms.bukkit.webCommand;

import me.chrommob.minestore.api.interfaces.commands.CommandExecuterCommon;
import me.chrommob.minestore.common.MineStoreCommon;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class CommandExecuterBukkit extends CommandExecuterCommon {

    private final JavaPlugin plugin;
    private final MineStoreCommon pl;

    public CommandExecuterBukkit(JavaPlugin plugin, MineStoreCommon pl) {
        this.plugin = plugin;
        this.pl = pl;
    }

    @Override
    public void execute(String command) {
        pl.runOnMainThread(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    @Override
    public boolean isOnline(String username) {
        // Exact match. getPlayer(String) matches by prefix, so "Ali" counted as
        // online while only "Ali123" was, and an online-only command ran for a
        // player who was not there.
        return plugin.getServer().getPlayerExact(username) != null;
    }
}
