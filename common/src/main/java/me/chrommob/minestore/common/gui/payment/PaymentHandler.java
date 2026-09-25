package me.chrommob.minestore.common.gui.payment;

import me.chrommob.minestore.api.Registries;
import me.chrommob.minestore.common.MineStoreCommon;
import me.chrommob.minestore.api.interfaces.commands.CommonConsoleUser;
import me.chrommob.minestore.api.interfaces.user.CommonUser;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class PaymentHandler {
    private final MineStoreCommon plugin;
    private final Map<String, Set<String>> payments = new ConcurrentHashMap<>();
    public PaymentHandler(MineStoreCommon plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<Boolean> createPayment(String username, int itemId) {
        return plugin.webListener().createPayment(username, itemId).thenApply(paymentCreationResponse -> {
            if (!paymentCreationResponse.isSuccess()) {
                plugin.log("Failed to create payment: " + paymentCreationResponse.getMessage());
                return false;
            }
            if (!paymentCreationResponse.getPaymentResponse().isSuccess()) {
                plugin.log("Failed to create payment: " + paymentCreationResponse.getPaymentResponse().getData().getUrl());
                return false;
            }
            String orderId = paymentCreationResponse.getPaymentResponse().getData().getOrderId();
            payments.compute(username.toLowerCase(), (k, v) -> {
                if (v == null) {
                    v = new HashSet<>();
                }
                v.add(orderId);
                return v;
            });
            plugin.debug(this.getClass(), "Created payment for " + username + " with order id: " + orderId);
            return true;
        });
    }

    /**
     * Tells the player how a virtual currency payment went.
     *
     * Upstream only spoke when the payment had been started from the in-game GUI.
     * On this network Cevher purchases start on the website and finish here, while
     * the player is online, so the player hears about both. An empty message in
     * the language file keeps that line quiet.
     */
    public void handlePayment(String username, String price, String paymentInternalId, boolean success) {
        Set<String> orderIds = payments.get(username.toLowerCase());
        if (orderIds != null) {
            orderIds.remove(paymentInternalId);
        }
        CommonUser user = Registries.USER_GETTER.get().get(username).commonUser();
        if (user instanceof CommonConsoleUser || !user.isOnline()) {
            return;
        }
        String key = success ? "success-message" : "failure-message";
        String template = plugin.pluginConfig().langString("payment", key);
        if (template.trim().isEmpty()) {
            return;
        }
        user.sendMessage(plugin.miniMessage().deserialize(template.replace("%price%", price)));
    }
}
