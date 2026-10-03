package me.chrommob.minestore.common.commands;

import java.util.Map;
import java.util.function.Consumer;

/**
 * TurkishPvP: one run of a website Cevher payment ({@code ms chargeBalance}), kept free of
 * platform types so it can be tested.
 *
 * The player does not have to be online: Cevher charges by UUID in its database. Two things
 * must each happen at most once, however often the command runs (store re-queues, one queue
 * row per cart item, retries after a crash, a human re-running it):
 * <ol>
 *   <li>the charge: Cevher's ledger, key {@code pay-<payment_id>}, written in the same
 *       transaction as the balance change;</li>
 *   <li>the report to the store: the store's handler is not idempotent (a second accepted
 *       "success" runs the purchase again). Cevher's ledger row carries the report state,
 *       and only the caller that moves it from "" to "reporting" sends the report.</li>
 * </ol>
 * The queue rows are confirmed (reported "executed") only once the store has the result.
 */
public final class VirtualCurrencyCharge {

    /** A report left in "reporting" this long was interrupted; its outcome is unknown. */
    public static final long REPORT_STALE_MILLIS = 5 * 60_000L;

    /** How a run ended. */
    public enum Settlement {
        /** The store has the result (now or from an earlier run); queue rows confirmed. */
        SETTLED,
        /** Nothing final yet (Cevher not ready, database error, store unreachable): run again later. */
        WAITING,
        /** The report may or may not have reached the store; not resent, needs a human. Queue rows confirmed. */
        IN_DOUBT,
        /** The command itself is unusable; nothing was charged. Queue rows confirmed. */
        INVALID
    }

    /** What sending the report achieved. */
    public enum Delivery {
        /** The store answered (accepted or refused): it has decided. */
        REACHED,
        /** The store certainly did not process it (unreachable, or refused before deciding). Safe to resend. */
        NOT_REACHED,
        /** Unknown whether the store processed it (timeout, server error). Must not be resent. */
        UNKNOWN
    }

    @FunctionalInterface
    public interface Reporter {
        Delivery send(boolean success, double remainingBalance);
    }

    @FunctionalInterface
    public interface Outcome {
        /** First time this payment was decided: tell the player. */
        void decided(boolean success);
    }

    private VirtualCurrencyCharge() {
    }

    public static Settlement run(CevherCharge cevher, String paymentId, String username, String amount,
                                 Reporter reporter, Outcome outcome, Runnable confirm, Consumer<String> log) {
        String label = "Virtual currency payment " + paymentId + " for " + username;
        Map<String, String> answer = cevher == null ? null : cevher.charge(paymentId, username, amount);
        if (answer == null) {
            log.accept(label + ": Cevher is not available, nothing was charged; trying again later.");
            return Settlement.WAITING;
        }
        String result = String.valueOf(answer.get("outcome"));
        if ("INVALID".equals(result)) {
            log.accept(label + " was not charged, Cevher refused the command: " + answer.get("message"));
            confirm.run();
            return Settlement.INVALID;
        }
        if (!"CHARGED".equals(result) && !"DECLINED".equals(result)) {
            log.accept(label + " could not be processed (" + answer.get("message") + "), trying again later.");
            return Settlement.WAITING;
        }
        boolean success = "CHARGED".equals(result);
        if (Boolean.parseBoolean(answer.get("fresh"))) {
            outcome.decided(success);
        }
        String report = answer.get("report") == null ? "" : answer.get("report");
        if ("reported".equals(report)) {
            confirm.run();
            return Settlement.SETTLED;
        }
        if ("reporting".equals(report)) {
            if (number(answer.get("reportAgeMs")) < REPORT_STALE_MILLIS) {
                // Another run is reporting right now.
                return Settlement.WAITING;
            }
            log.accept("IN DOUBT: " + label + " (" + result + ") was being reported to the store when that report was "
                    + "interrupted. It is NOT resent automatically (the store would run the purchase twice). Check the "
                    + "payment in the store panel and settle it by hand.");
            confirm.run();
            return Settlement.IN_DOUBT;
        }
        if (!cevher.report(paymentId, "begin")) {
            // Someone else took the report between the charge and here.
            return Settlement.WAITING;
        }
        Delivery delivery = reporter.send(success, number(answer.get("balance")));
        switch (delivery) {
            case REACHED:
                if (!cevher.report(paymentId, "reached")) {
                    log.accept(label + ": the store has the result but Cevher could not record that.");
                }
                confirm.run();
                return Settlement.SETTLED;
            case NOT_REACHED:
                if (!cevher.report(paymentId, "unreached")) {
                    log.accept(label + ": could not release the report in Cevher; it will be treated as in doubt.");
                }
                return Settlement.WAITING;
            default:
                log.accept(label + ": the store did not answer clearly, it may or may not have the result. "
                        + "Not resending; check the payment in the store panel.");
                return Settlement.WAITING;
        }
    }

    private static double number(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
