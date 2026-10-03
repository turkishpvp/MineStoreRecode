package me.chrommob.minestore.common.commands;

import java.util.Map;

/**
 * TurkishPvP: Cevher's keyed, offline-capable charge for website Cevher payments.
 *
 * Cevher implements it on its Vault provider ({@code storeCharge}, {@code storeChargeReport});
 * the platform side reaches it by reflection because the two plugins live in separate class
 * loaders. Only JDK types cross the boundary. See {@link VirtualCurrencyCharge} for the answer
 * fields and how they are used.
 */
public interface CevherCharge {
    /**
     * Charges the payment at most once (key {@code pay-<paymentId>} in Cevher's ledger).
     *
     * @return Cevher's answer, or null when Cevher is not there (not installed, reloading, too old)
     */
    Map<String, String> charge(String paymentId, String username, String amount);

    /** One step of the at-most-once store report: {@code begin}, {@code reached} or {@code unreached}. */
    boolean report(String paymentId, String phase);
}
