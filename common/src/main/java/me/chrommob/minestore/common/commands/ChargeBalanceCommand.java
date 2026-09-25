package me.chrommob.minestore.common.commands;

import com.google.gson.annotations.SerializedName;
import me.chrommob.minestore.api.Registries;
import me.chrommob.minestore.api.interfaces.commands.CommonConsoleUser;
import me.chrommob.minestore.api.interfaces.user.AbstractUser;
import me.chrommob.minestore.api.interfaces.user.CommonUser;
import me.chrommob.minestore.api.web.Result;
import me.chrommob.minestore.api.web.WebContext;
import me.chrommob.minestore.api.web.WebRequest;
import me.chrommob.minestore.common.MineStoreCommon;
import me.chrommob.minestore.common.config.ConfigKeys;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;

/**
 * Takes a virtual currency (Cevher) payment from the player's Vault balance and
 * tells the store the result.
 *
 * Upstream could not complete a single payment against this store: it posted to
 * {@code /api/{key}/payment/handle/} (404 here, the route is
 * {@code /api/payments/handle/{key}/virtualcurrency}), the body was
 * {@code ResponseData.toString()} (an object hash, not JSON), and without an
 * {@code Accept} header Laravel answered the failed validation with a redirect.
 * It also took the money before noticing a bad signature, and looked the player
 * up by name prefix. See {@link VirtualCurrencyPayment}.
 */
@SuppressWarnings("unused")
public class ChargeBalanceCommand {
    private final MineStoreCommon plugin;

    public ChargeBalanceCommand(MineStoreCommon plugin) {
        this.plugin = plugin;
    }

    @Permission("minestore.admin.chargeBalance")
    @Command("minestore|ms chargeBalance <username> <amount> <payment_internal_id> <signature>")
    public void onCharge(AbstractUser user, @Argument("username") String username, @Argument("amount") String amount, @Argument("payment_internal_id") String paymentInternalId, @Argument("signature") String signature) {
        String secretKey = ConfigKeys.API_KEYS.KEY.getValue();
        if (!VirtualCurrencyPayment.isValidAmount(amount)) {
            plugin.log("Virtual currency payment " + paymentInternalId + " for " + username + " has an invalid amount, nothing was charged.");
            return;
        }
        String expected = VirtualCurrencyPayment.signature(amount, paymentInternalId, username, secretKey);
        if (!VirtualCurrencyPayment.signatureMatches(expected, signature)) {
            // Not from our store (or the key was rotated on one side only).
            // Charging here would take the player's money for a payment the
            // store will reject, so stop before touching the balance.
            plugin.log("Virtual currency payment " + paymentInternalId + " for " + username + " has a signature that does not match the API key, nothing was charged.");
            return;
        }

        CommonUser player = Registries.USER_GETTER.get().get(username).commonUser();
        String status;
        double remaining;
        if (player instanceof CommonConsoleUser || !player.isOnline()) {
            // The store queues this as "player must be online", but the player
            // can leave between delivery and execution. Report a failure so the
            // payment does not sit in "processing" forever.
            status = VirtualCurrencyPayment.STATUS_FAILURE;
            remaining = 0;
        } else if (player.takeMoney(Double.parseDouble(amount))) {
            status = VirtualCurrencyPayment.STATUS_SUCCESS;
            remaining = player.getBalance();
        } else {
            status = VirtualCurrencyPayment.STATUS_FAILURE;
            remaining = player.getBalance();
        }

        plugin.paymentHandler().handlePayment(username, amount, paymentInternalId, VirtualCurrencyPayment.STATUS_SUCCESS.equals(status));

        String body = VirtualCurrencyPayment.body(status, username, amount, paymentInternalId, remaining, expected);
        WebRequest<Received> request = new WebRequest.Builder<>(Received.class)
                .path(VirtualCurrencyPayment.handlerPath(secretKey))
                .requiresApiKey(false)
                .type(WebRequest.Type.POST)
                .strBody(body)
                .build();
        Result<Received, WebContext> res = plugin.apiHandler().request(request);
        // The balance is already gone at this point, so a store restart must
        // not lose the answer. Retry transport errors and 5xx a few times; a
        // 4xx is final and retrying it would not change the outcome.
        for (int attempt = 1; attempt < REPORT_ATTEMPTS && res.isError() && isRetryable(res.context()); attempt++) {
            try {
                Thread.sleep(REPORT_RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            res = plugin.apiHandler().request(request);
        }
        if (res.isError()) {
            plugin.log("Could not report virtual currency payment " + paymentInternalId + " for " + username + " to the store (" + status + ").");
            plugin.debug(this.getClass(), res.context());
            return;
        }
        Received received = res.value();
        if (received == null || !received.success) {
            plugin.log("The store rejected virtual currency payment " + paymentInternalId + " for " + username + ": "
                    + (received == null ? "empty answer" : received.message));
        }
    }

    private static final int REPORT_ATTEMPTS = 5;
    private static final long REPORT_RETRY_DELAY_MS = 3000;

    private static boolean isRetryable(WebContext context) {
        int code = context.responseCode();
        return code <= 0 || code >= 500;
    }

    static class Received {
        @SerializedName("success")
        boolean success;
        String message;
    }
}
