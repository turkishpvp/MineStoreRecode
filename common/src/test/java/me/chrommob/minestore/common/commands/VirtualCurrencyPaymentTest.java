package me.chrommob.minestore.common.commands;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.*;

public class VirtualCurrencyPaymentTest {
    // Expected values computed with PHP 8 exactly as VirtualCurrencyController does:
    // ksort($data); hash_hmac('sha256', http_build_query($data), 'test-key')
    private static final String PHP_SIGNATURE_150 = "a71add7e27ea27de000ded74e49f3218d21f99d23003dfc9f7c2e6b6c3356bb4";
    private static final String PHP_SIGNATURE_12_5 = "3d4f24aa84fb48b8d94829217e25605eaaccca697d0ce9bc56ff15c6cf8cab1a";

    @Test
    public void signatureMatchesTheStoreForWholeAmounts() {
        assertEquals(PHP_SIGNATURE_150, VirtualCurrencyPayment.signature("150", "a1b2c3", "Deniz_1", "test-key"));
    }

    @Test
    public void signatureMatchesTheStoreForDecimalAmounts() {
        assertEquals(PHP_SIGNATURE_12_5, VirtualCurrencyPayment.signature("12.5", "a1b2c3", "Deniz_1", "test-key"));
    }

    @Test
    public void signatureCheckRejectsForgedAndMissingValues() {
        assertTrue(VirtualCurrencyPayment.signatureMatches(PHP_SIGNATURE_150, PHP_SIGNATURE_150.toUpperCase()));
        assertFalse(VirtualCurrencyPayment.signatureMatches(PHP_SIGNATURE_150, PHP_SIGNATURE_12_5));
        assertFalse(VirtualCurrencyPayment.signatureMatches(PHP_SIGNATURE_150, null));
    }

    @Test
    public void handlerPathIsTheStoreRouteWithTheKeyInTheMiddle() {
        assertEquals("payments/handle/KEY/virtualcurrency", VirtualCurrencyPayment.handlerPath("KEY"));
    }

    @Test
    public void bodyKeepsThePriceStringAndHasTheFieldsTheStoreValidates() {
        String body = VirtualCurrencyPayment.body("success", "Deniz_1", "150", "a1b2c3", 42.5, PHP_SIGNATURE_150);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        assertEquals("success", root.get("status").getAsString());
        JsonObject data = root.getAsJsonObject("data");
        assertEquals("Deniz_1", data.get("username").getAsString());
        assertTrue(data.get("price").getAsJsonPrimitive().isString());
        assertEquals("150", data.get("price").getAsString());
        assertEquals("a1b2c3", data.get("payment_internal_id").getAsString());
        assertEquals(42.5, data.get("remaining_balance").getAsDouble(), 0.0);
        assertEquals(PHP_SIGNATURE_150, data.get("signature").getAsString());
    }

    @Test
    public void amountValidation() {
        assertTrue(VirtualCurrencyPayment.isValidAmount("150"));
        assertTrue(VirtualCurrencyPayment.isValidAmount("12.5"));
        assertFalse(VirtualCurrencyPayment.isValidAmount("-1"));
        assertFalse(VirtualCurrencyPayment.isValidAmount("NaN"));
        assertFalse(VirtualCurrencyPayment.isValidAmount("abc"));
        assertFalse(VirtualCurrencyPayment.isValidAmount(""));
        assertFalse(VirtualCurrencyPayment.isValidAmount(null));
    }
}
