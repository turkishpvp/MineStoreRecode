package me.chrommob.minestore.common.commands;

import me.chrommob.minestore.common.commands.VirtualCurrencyCharge.Delivery;
import me.chrommob.minestore.common.commands.VirtualCurrencyCharge.Settlement;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class VirtualCurrencyChargeTest {

    /** Same contract as Cevher's StoreCharge + ledger, in memory. */
    private static final class FakeCevher implements CevherCharge {
        final Map<String, Double> balances = new HashMap<>();
        final Map<String, String> status = new HashMap<>();
        final Map<String, String> report = new HashMap<>();
        final Map<String, Long> reportSince = new HashMap<>();
        long now = 0;
        boolean down = false;
        boolean dbError = false;
        int charges = 0;

        @Override
        public Map<String, String> charge(String paymentId, String username, String amount) {
            if (down) {
                return null;
            }
            Map<String, String> answer = new LinkedHashMap<>();
            if (dbError) {
                answer.put("outcome", "ERROR");
                return answer;
            }
            boolean fresh = !status.containsKey(paymentId);
            if (fresh) {
                double price = Double.parseDouble(amount);
                double balance = balances.getOrDefault(username, 0.0);
                if (balance >= price) {
                    balances.put(username, balance - price);
                    status.put(paymentId, "charged");
                    charges++;
                } else {
                    status.put(paymentId, "declined");
                }
                report.put(paymentId, "");
                reportSince.put(paymentId, now);
            }
            answer.put("outcome", "charged".equals(status.get(paymentId)) ? "CHARGED" : "DECLINED");
            answer.put("fresh", Boolean.toString(fresh));
            answer.put("report", report.get(paymentId));
            answer.put("reportAgeMs", Long.toString(now - reportSince.get(paymentId)));
            answer.put("balance", Double.toString(balances.getOrDefault(username, 0.0)));
            return answer;
        }

        @Override
        public boolean report(String paymentId, String phase) {
            String state = report.get(paymentId);
            if (state == null) {
                return false;
            }
            String from = "begin".equals(phase) ? "" : "reporting";
            String to = "begin".equals(phase) ? "reporting" : "reached".equals(phase) ? "reported" : "";
            if (!from.equals(state)) {
                return false;
            }
            report.put(paymentId, to);
            reportSince.put(paymentId, now);
            return true;
        }
    }

    private FakeCevher cevher;
    private final List<String> reports = new ArrayList<>();
    private final List<Boolean> told = new ArrayList<>();
    private final List<String> logs = new ArrayList<>();
    private int confirms;
    private Delivery nextDelivery;

    @Before
    public void setUp() {
        cevher = new FakeCevher();
        cevher.balances.put("Ali", 1000.0);
        nextDelivery = Delivery.REACHED;
    }

    private Settlement run(String paymentId, String amount) {
        return VirtualCurrencyCharge.run(cevher, paymentId, "Ali", amount,
                (success, remaining) -> {
                    reports.add((success ? "success" : "failure") + " " + remaining);
                    return nextDelivery;
                },
                told::add, () -> confirms++, logs::add);
    }

    @Test
    public void anOfflinePlayerPaysAndTheStoreIsToldOnce() {
        assertEquals(Settlement.SETTLED, run("ORD1", "150"));
        assertEquals(1, reports.size());
        assertEquals("success 850.0", reports.get(0));
        assertEquals(1, confirms);
        assertEquals(1, told.size());
        assertTrue(told.get(0));

        // a second queue row of the same payment, a retry, a human re-run
        assertEquals(Settlement.SETTLED, run("ORD1", "150"));
        assertEquals(Settlement.SETTLED, run("ORD1", "150"));
        assertEquals("charged once", 1, cevher.charges);
        assertEquals("reported once", 1, reports.size());
        assertEquals("player told once", 1, told.size());
        assertEquals(3, confirms);
    }

    @Test
    public void notEnoughCevherIsReportedAsAFailureOnce() {
        assertEquals(Settlement.SETTLED, run("ORD2", "5000"));
        assertEquals("failure 1000.0", reports.get(0));
        assertFalse(told.get(0));
        cevher.balances.put("Ali", 9000.0);
        assertEquals(Settlement.SETTLED, run("ORD2", "5000"));
        assertEquals(0, cevher.charges);
        assertEquals(1, reports.size());
    }

    @Test
    public void anUnreachableStoreGetsTheSameResultLaterWithoutChargingAgain() {
        nextDelivery = Delivery.NOT_REACHED;
        assertEquals(Settlement.WAITING, run("ORD3", "100"));
        assertEquals(0, confirms);
        nextDelivery = Delivery.REACHED;
        assertEquals(Settlement.SETTLED, run("ORD3", "100"));
        assertEquals(1, cevher.charges);
        assertEquals(2, reports.size());
        assertEquals(reports.get(0), reports.get(1));
        assertEquals("player told once", 1, told.size());
    }

    @Test
    public void anUnclearReportIsNeverResentAndBecomesInDoubtAfterAWhile() {
        nextDelivery = Delivery.UNKNOWN;
        assertEquals(Settlement.WAITING, run("ORD4", "100"));
        nextDelivery = Delivery.REACHED;
        cevher.now += VirtualCurrencyCharge.REPORT_STALE_MILLIS - 1;
        assertEquals("still young, maybe another run", Settlement.WAITING, run("ORD4", "100"));
        cevher.now += 1;
        assertEquals(Settlement.IN_DOUBT, run("ORD4", "100"));
        assertEquals("never resent", 1, reports.size());
        assertEquals(1, confirms);
        assertTrue(logs.stream().anyMatch(line -> line.startsWith("IN DOUBT")));
    }

    @Test
    public void whileCevherIsAwayNothingIsChargedOrReported() {
        cevher.down = true;
        assertEquals(Settlement.WAITING, run("ORD5", "100"));
        assertEquals(Settlement.WAITING, VirtualCurrencyCharge.run(null, "ORD5", "Ali", "100",
                (s, r) -> Delivery.REACHED, told::add, () -> confirms++, logs::add));
        cevher.dbError = true;
        cevher.down = false;
        assertEquals(Settlement.WAITING, run("ORD5", "100"));
        assertTrue(reports.isEmpty());
        assertEquals(0, confirms);
        assertEquals(0, cevher.charges);
    }

    @Test
    public void anInvalidCommandIsConfirmedSoItStopsComingBack() {
        CevherCharge refusing = new CevherCharge() {
            @Override
            public Map<String, String> charge(String paymentId, String username, String amount) {
                Map<String, String> answer = new HashMap<>();
                answer.put("outcome", "INVALID");
                answer.put("message", "bad");
                return answer;
            }

            @Override
            public boolean report(String paymentId, String phase) {
                fail("nothing to report");
                return false;
            }
        };
        assertEquals(Settlement.INVALID, VirtualCurrencyCharge.run(refusing, "x", "Ali", "1",
                (s, r) -> Delivery.REACHED, told::add, () -> confirms++, logs::add));
        assertEquals(1, confirms);
    }

    @Test
    public void aRunThatLosesTheReportRaceWaits() {
        // another run charged and began reporting a moment ago
        cevher.charge("ORD6", "Ali", "10");
        cevher.report("ORD6", "begin");
        assertEquals(Settlement.WAITING, run("ORD6", "10"));
        assertTrue(reports.isEmpty());
        assertEquals(0, confirms);
    }
}
