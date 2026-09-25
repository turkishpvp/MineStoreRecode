package me.chrommob.minestore.common.commandGetters;

import org.junit.Test;

import static org.junit.Assert.*;

public class DeliveryLedgerTest {
    @Test
    public void aRepeatedIdIsRecognised() {
        DeliveryLedger ledger = new DeliveryLedger();
        assertTrue(ledger.firstTime(7));
        assertFalse(ledger.firstTime(7));
        assertTrue(ledger.firstTime(8));
    }

    @Test
    public void theOldestIdsAreForgottenPastCapacity() {
        DeliveryLedger ledger = new DeliveryLedger(3);
        ledger.firstTime(1);
        ledger.firstTime(2);
        ledger.firstTime(3);
        ledger.firstTime(4);
        assertEquals(3, ledger.size());
        assertFalse(ledger.contains(1));
        assertTrue(ledger.contains(4));
    }

    @Test(expected = IllegalArgumentException.class)
    public void capacityMustBePositive() {
        new DeliveryLedger(0);
    }
}
