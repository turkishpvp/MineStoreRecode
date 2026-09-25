package me.chrommob.minestore.common.commands;

import me.chrommob.minestore.api.Registries;
import me.chrommob.minestore.api.interfaces.commands.CommonConsoleUser;
import me.chrommob.minestore.api.interfaces.user.AbstractUser;
import me.chrommob.minestore.api.interfaces.user.CommonUser;
import me.chrommob.minestore.api.scheduler.MineStoreScheduledTask;
import me.chrommob.minestore.common.MineStoreCommon;
import me.chrommob.minestore.common.subsription.SubscriptionUtil;
import me.chrommob.minestore.common.subsription.json.ReturnSubscriptionObject;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;

@SuppressWarnings("unused")
public class SubscriptionsCommand {
    private final MineStoreCommon plugin;
    public SubscriptionsCommand(MineStoreCommon plugin) {
        this.plugin = plugin;
    }
    @Permission("minestore.subscriptions")
    @Command("minestore|ms subscriptions")
    public void onSubscription(AbstractUser user) {
        CommonUser commonUser = user.commonUser();
        if (commonUser instanceof CommonConsoleUser) {
            commonUser.sendMessage("[MineStore] You can't use this command from console!");
            return;
        }
        Registries.MINESTORE_SCHEDULER.get().runDelayed(new MineStoreScheduledTask("subscription", () -> {
            ReturnSubscriptionObject result = SubscriptionUtil.getSubscription(commonUser.getName());
            if (result == null) {
                send(commonUser, plugin.pluginConfig().langString("subscription", "error"));
                return;
            }
            // The store answers in English ("No payments found.", "User not
            // found.", ...). Every unsuccessful answer means the same thing to
            // the player, so it gets the language file's line instead.
            if (!result.isSuccess() || result.urls() == null) {
                send(commonUser, plugin.pluginConfig().langString("subscription", "none"));
                return;
            }
            send(commonUser, plugin.pluginConfig().langString("subscription", "title"));
            String status = plugin.pluginConfig().langString("subscription", "status");
            if (!status.isEmpty() && result.message() != null) {
                send(commonUser, status.replace("%message%", result.message()));
            }
            for (String url : result.urls()) {
                // Stripe answers null when the portal is off; other gateways
                // answer a sentence, not a link.
                if (url == null || url.trim().isEmpty()) {
                    continue;
                }
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    send(commonUser, plugin.pluginConfig().langString("subscription", "url").replace("%url%", url));
                } else {
                    send(commonUser, plugin.pluginConfig().langString("subscription", "note").replace("%note%", url));
                }
            }
        }, 0));
    }

    private void send(CommonUser user, String miniMessage) {
        if (miniMessage == null || miniMessage.isEmpty()) {
            return;
        }
        user.sendMessage(plugin.miniMessage().deserialize(miniMessage));
    }
}
