package me.chrommob.minestore.platforms.bukkit.db;

import me.chrommob.minestore.common.MineStoreCommon;
import me.chrommob.minestore.common.commandHolder.NetworkDeliveries;
import me.chrommob.minestore.common.commands.CevherCharge;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * TurkishPvP: reaches Cevher's offline, keyed charge on its Vault provider.
 *
 * Cevher is a Tulpar module in its own class loader, so there is no shared type to
 * call: the methods are looked up by name with JDK-only signatures
 * ({@code Map<String,String> storeCharge(String,String,String)},
 * {@code boolean storeChargeReport(String,String)}). The provider is looked up on
 * every call, so a Cevher reload is picked up, and nothing is charged while Cevher
 * does not advertise the protocol.
 */
public class CevherChargeBukkit implements CevherCharge {
    private final JavaPlugin plugin;
    private final MineStoreCommon common;

    public CevherChargeBukkit(JavaPlugin plugin, MineStoreCommon common) {
        this.plugin = plugin;
        this.common = common;
    }

    private Object provider() {
        if (!NetworkDeliveries.chargeProtocolAvailable()) {
            return null;
        }
        RegisteredServiceProvider<Economy> registration = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        return registration == null ? null : registration.getProvider();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, String> charge(String paymentId, String username, String amount) {
        Object provider = provider();
        if (provider == null) {
            return null;
        }
        try {
            Method method = provider.getClass().getMethod("storeCharge", String.class, String.class, String.class);
            Object answer = method.invoke(provider, paymentId, username, amount);
            return answer instanceof Map ? (Map<String, String>) answer : null;
        } catch (NoSuchMethodException e) {
            common.log("The economy provider " + provider.getClass().getName() + " has no keyed charge, Cevher payments wait.");
            return null;
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            common.debug(getClass(), e);
            // Unknown whether the charge was written: the ledger answers that on the next run.
            return null;
        }
    }

    @Override
    public boolean report(String paymentId, String phase) {
        Object provider = provider();
        if (provider == null) {
            return false;
        }
        try {
            Method method = provider.getClass().getMethod("storeChargeReport", String.class, String.class);
            return Boolean.TRUE.equals(method.invoke(provider, paymentId, phase));
        } catch (ReflectiveOperationException | RuntimeException e) {
            common.debug(getClass(), e);
            return false;
        }
    }
}
