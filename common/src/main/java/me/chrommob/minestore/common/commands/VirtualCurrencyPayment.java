package me.chrommob.minestore.common.commands;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import me.chrommob.minestore.api.generic.ParamBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * The wire side of a virtual currency (Cevher) payment, kept free of platform
 * types so it can be tested.
 *
 * The store queues {@code ms chargeBalance <username> <amount> <payment_internal_id> <signature>}
 * and waits for the server to answer on {@code POST /api/payments/handle/{api_key}/virtualcurrency}
 * ({@code VirtualCurrencyController::handle}). The store recomputes the signature
 * from the {@code data} it receives, as {@code hash_hmac('sha256', http_build_query(ksort(data)), api_secret)},
 * so {@code price} goes back exactly as the store wrote it into the command
 * rather than through a double: the signature is then byte-for-byte the one the
 * store computed, whatever the decimal formatting on either side.
 */
public final class VirtualCurrencyPayment {
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILURE = "failure";

    private static final Gson GSON = new Gson();

    private VirtualCurrencyPayment() {
    }

    /** Store route for the answer; the key is a path segment, not a prefix. */
    public static String handlerPath(String apiKey) {
        return "payments/handle/" + apiKey + "/virtualcurrency";
    }

    /**
     * Same string the store signs: keys sorted, form-encoded like PHP's
     * {@code http_build_query}.
     */
    public static String signature(String price, String paymentInternalId, String username, String secretKey) {
        ParamBuilder paramBuilder = new ParamBuilder();
        paramBuilder.append("payment_internal_id", paymentInternalId);
        paramBuilder.append("price", price);
        paramBuilder.append("username", username);
        String text = paramBuilder.build().substring(1);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmacBytes = mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hmacBytes.length * 2);
            for (byte b : hmacBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /** Constant-time comparison, so the check does not leak how much matched. */
    public static boolean signatureMatches(String expected, String received) {
        if (expected == null || received == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                received.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    /** True when the amount is a plain non-negative number the store could have written. */
    public static boolean isValidAmount(String amount) {
        if (amount == null || amount.isEmpty()) {
            return false;
        }
        try {
            double value = Double.parseDouble(amount);
            return !Double.isNaN(value) && !Double.isInfinite(value) && value >= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Body for the store. {@code price} is the raw string from the command so the
     * store's recomputed signature matches; Laravel's {@code numeric} rule accepts it.
     */
    public static String body(String status, String username, String price, String paymentInternalId,
                              double remainingBalance, String signature) {
        JsonObject data = new JsonObject();
        data.addProperty("username", username);
        data.addProperty("price", price);
        data.addProperty("payment_internal_id", paymentInternalId);
        data.addProperty("remaining_balance", remainingBalance);
        data.addProperty("signature", signature);
        JsonObject root = new JsonObject();
        root.addProperty("status", status);
        root.add("data", data);
        return GSON.toJson(root);
    }
}
