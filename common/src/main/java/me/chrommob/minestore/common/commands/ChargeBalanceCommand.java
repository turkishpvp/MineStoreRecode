package me.chrommob.minestore.common.commands;

import com.google.gson.annotations.SerializedName;
import me.chrommob.minestore.api.interfaces.user.AbstractUser;
import me.chrommob.minestore.api.web.Result;
import me.chrommob.minestore.api.web.WebContext;
import me.chrommob.minestore.api.web.WebRequest;
import me.chrommob.minestore.common.MineStoreCommon;
import me.chrommob.minestore.common.config.ConfigKeys;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;

/**
 * Takes a virtual currency (Cevher) payment from the player's balance and tells the
 * store the result.
 *
 * Upstream could not complete a single payment against this store: it posted to
 * {@code /api/{key}/payment/handle/} (404 here, the route is
 * {@code /api/payments/handle/{key}/virtualcurrency}), the body was
 * {@code ResponseData.toString()} (an object hash, not JSON), and without an
 * {@code Accept} header Laravel answered the failed validation with a redirect.
 * It also took the money before noticing a bad signature, and looked the player
 * up by name prefix. See {@link VirtualCurrencyPayment}.
 *
 * TurkishPvP: the player does NOT have to be online. Upstream charged through Vault
 * with the online {@code Player} and failed the payment otherwise; Cevher now charges
 * by UUID in its database, at most once per payment, and the store is told at most
 * once (see {@link VirtualCurrencyCharge}).
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

        VirtualCurrencyCharge.Settlement settlement = VirtualCurrencyCharge.run(plugin.cevherCharge(), paymentInternalId, username, amount,
                (success, remaining) -> report(secretKey, expected, username, amount, paymentInternalId, success, remaining),
                success -> plugin.paymentHandler().handlePayment(username, amount, paymentInternalId, success),
                () -> plugin.commandStorage().confirmCharge(paymentInternalId),
                plugin::log);
        plugin.debug(this.getClass(), "Virtual currency payment " + paymentInternalId + ": " + settlement);
    }

    private VirtualCurrencyCharge.Delivery report(String secretKey, String signature, String username, String amount,
                                                  String paymentInternalId, boolean success, double remaining) {
        String status = success ? VirtualCurrencyPayment.STATUS_SUCCESS : VirtualCurrencyPayment.STATUS_FAILURE;
        String body = VirtualCurrencyPayment.body(status, username, amount, paymentInternalId, remaining, signature);
        WebRequest<Received> request = new WebRequest.Builder<>(Received.class)
                .path(VirtualCurrencyPayment.handlerPath(secretKey))
                .requiresApiKey(false)
                .type(WebRequest.Type.POST)
                .strBody(body)
                .build();
        Result<Received, WebContext> res = plugin.apiHandler().request(request);
        // Retry only while the store is certainly unreachable: its handler is not idempotent.
        for (int attempt = 1; attempt < REPORT_ATTEMPTS && res.isError() && isRetryable(res.context()); attempt++) {
            try {
                Thread.sleep(REPORT_RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            res = plugin.apiHandler().request(request);
        }
        if (!res.isError()) {
            Received received = res.value();
            if (received == null || !received.success) {
                plugin.log("The store rejected virtual currency payment " + paymentInternalId + " for " + username + " (" + status + "): "
                        + (received == null ? "empty answer" : received.message));
            }
            return VirtualCurrencyCharge.Delivery.REACHED;
        }
        plugin.debug(this.getClass(), res.context());
        int code = res.context().responseCode();
        if (isRetryable(res.context()) || (code >= 400 && code < 500)) {
            // Unreachable, or the store refused before deciding anything: safe to send again later.
            plugin.log("Could not report virtual currency payment " + paymentInternalId + " for " + username
                    + " to the store (" + status + ", HTTP " + code + "), it will be sent again later.");
            return VirtualCurrencyCharge.Delivery.NOT_REACHED;
        }
        return VirtualCurrencyCharge.Delivery.UNKNOWN;
    }

    private static final int REPORT_ATTEMPTS = 5;
    private static final long REPORT_RETRY_DELAY_MS = 3000;

    /**
     * Only failures where the store certainly did not process the request: it
     * could not be reached, or the proxy in front of it said the app is down.
     */
    private static boolean isRetryable(WebContext context) {
        int code = context.responseCode();
        if (code == 502 || code == 503 || code == 504) {
            return true;
        }
        Throwable cause = context.getCause();
        return code <= 0 && (cause instanceof java.net.ConnectException || cause instanceof java.net.UnknownHostException);
    }

    static class Received {
        @SerializedName("success")
        boolean success;
        String message;
    }
}
