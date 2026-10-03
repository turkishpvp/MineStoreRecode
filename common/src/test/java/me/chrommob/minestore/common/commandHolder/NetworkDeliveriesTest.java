package me.chrommob.minestore.common.commandHolder;

import me.chrommob.minestore.api.interfaces.commands.ParsedResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class NetworkDeliveriesTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<String> executed = new ArrayList<>();
    private final List<Integer> acked = new ArrayList<>();
    /** What the store answers on a retry check; null = no answer. */
    private Set<Integer> storeRejects = new HashSet<>();
    private File file;

    @Before
    public void setUp() {
        System.setProperty(NetworkDeliveries.PROTOCOL_PROPERTY, NetworkDeliveries.PROTOCOL_VERSION);
        System.setProperty(NetworkDeliveries.CHARGE_PROPERTY, NetworkDeliveries.CHARGE_VERSION);
        file = new File(folder.getRoot(), "networkDeliveries.json");
    }

    @After
    public void tearDown() {
        System.clearProperty(NetworkDeliveries.PROTOCOL_PROPERTY);
        System.clearProperty(NetworkDeliveries.CHARGE_PROPERTY);
    }

    private NetworkDeliveries deliveries() {
        NetworkDeliveries deliveries = new NetworkDeliveries(file,
                response -> {
                    // what CommandStorage.execute would dispatch
                    executed.add(NetworkDeliveries.needsKey(response.command())
                            ? NetworkDeliveries.withKey(response.command(), response.commandId())
                            : response.command());
                    // the file must already hold the command when it runs
                    assertTrue("persisted before running", file.exists());
                },
                acked::add, message -> { }, clock::get,
                ids -> CompletableFuture.completedFuture(storeRejects == null ? null : new HashSet<>(storeRejects)));
        deliveries.load();
        return deliveries;
    }

    private static ParsedResponse command(int id, String command, ParsedResponse.COMMAND_TYPE type) {
        return new ParsedResponse(ParsedResponse.TYPE.COMMAND, type, command, "Ali", id);
    }

    @Test
    public void onlyCevherStoreCommandsAndCevherPaymentsAreHandled() {
        assertTrue(NetworkDeliveries.handles("cevher sure Ali Seckin 30"));
        assertTrue(NetworkDeliveries.handles("/cevher ver Ali 500"));
        assertTrue(NetworkDeliveries.handles("CEVHER Al Ali 5"));
        assertTrue(NetworkDeliveries.handles("cevher chargeback Ali 5"));
        assertFalse(NetworkDeliveries.handles("cevher ayarla Ali 5"));
        assertFalse(NetworkDeliveries.handles("cevher"));
        assertFalse(NetworkDeliveries.handles("ogrant Ali Seckin 30d global x"));
        assertTrue(NetworkDeliveries.handles("ms chargeBalance Ali 5 ORD1 sig"));
        assertTrue(NetworkDeliveries.handles("/minestore chargeBalance Ali 5 ORD1 sig"));
        assertFalse("payments are keyed by payment id in Cevher, not by queue id",
                NetworkDeliveries.needsKey("ms chargeBalance Ali 5 ORD1 sig"));
        assertFalse(NetworkDeliveries.handles("ms chargeBalance Ali 5"));
        assertFalse(NetworkDeliveries.handles("user perm add Ali core.tags.OG perm global"));
        assertFalse(NetworkDeliveries.handles(""));
        assertFalse(NetworkDeliveries.handles(null));
        assertEquals("ORD1", NetworkDeliveries.chargePaymentId("ms chargeBalance Ali 5 ORD1 sig"));
        assertNull(NetworkDeliveries.chargePaymentId("cevher ver Ali 5"));
    }

    @Test
    public void aPaymentWaitsForItsOwnProtocolOnly() {
        ParsedResponse payment = command(7, "ms chargeBalance Ali 150 ORD1 sig", ParsedResponse.COMMAND_TYPE.ONLINE);
        assertTrue(NetworkDeliveries.accepts(payment));
        System.clearProperty(NetworkDeliveries.CHARGE_PROPERTY);
        assertFalse(NetworkDeliveries.accepts(payment));
        assertTrue(NetworkDeliveries.accepts(command(8, "cevher ver Ali 500", ParsedResponse.COMMAND_TYPE.OFFLINE)));
    }

    @Test
    public void everyQueuedCopyOfAPaymentIsConfirmedTogetherAndRunUnkeyed() {
        NetworkDeliveries deliveries = deliveries();
        // the store queues the same payment once per cart item
        deliveries.submit(command(7, "ms chargeBalance Ali 150 ORD1 sig", ParsedResponse.COMMAND_TYPE.ONLINE));
        deliveries.submit(command(8, "ms chargeBalance Ali 150 ORD1 sig", ParsedResponse.COMMAND_TYPE.ONLINE));
        deliveries.submit(command(9, "ms chargeBalance Ali 20 ORD2 sig", ParsedResponse.COMMAND_TYPE.ONLINE));
        assertEquals("ms chargeBalance Ali 150 ORD1 sig", executed.get(0));

        deliveries.confirmCharge("ORD1");
        assertFalse(deliveries.isPending(7));
        assertFalse(deliveries.isPending(8));
        assertTrue(deliveries.isPending(9));
        assertEquals(2, acked.size());

        deliveries.confirmCharge(null);
        deliveries.confirmCharge("ORD404");
        assertEquals(2, acked.size());
    }

    @Test
    public void anUnsettledPaymentIsRetriedButOnlyWhileCevherOffersCharges() {
        NetworkDeliveries deliveries = deliveries();
        deliveries.submit(command(7, "ms chargeBalance Ali 150 ORD1 sig", ParsedResponse.COMMAND_TYPE.ONLINE));
        deliveries.submit(command(8, "cevher ver Ali 500", ParsedResponse.COMMAND_TYPE.OFFLINE));
        System.clearProperty(NetworkDeliveries.CHARGE_PROPERTY);
        clock.addAndGet(NetworkDeliveries.FIRST_RETRY_MILLIS);
        deliveries.retryDue();
        assertEquals("only the delivery ran again", 3, executed.size());
        assertEquals("cevher ver Ali 500 ms-8", executed.get(2));

        System.setProperty(NetworkDeliveries.CHARGE_PROPERTY, NetworkDeliveries.CHARGE_VERSION);
        deliveries.retryDue();
        assertEquals(4, executed.size());
        assertEquals("ms chargeBalance Ali 150 ORD1 sig", executed.get(3));
    }

    @Test
    public void theKeyIsAppendedOrReplacesThePlaceholderAndIsNeverDoubled() {
        assertEquals("cevher sure Ali Seckin 30 ms-9", NetworkDeliveries.withKey("cevher sure Ali Seckin 30", 9));
        assertEquals("cevher ver Ali 500 ms-9", NetworkDeliveries.withKey("cevher ver Ali 500 {command_id}", 9));
        assertEquals("cevher ver Ali 500 ms-9", NetworkDeliveries.withKey("cevher ver Ali 500 ms-9", 9));
    }

    @Test
    public void withoutCevherAdvertisingTheProtocolNothingIsRouted() {
        ParsedResponse response = command(5, "cevher sure Ali Seckin 30", ParsedResponse.COMMAND_TYPE.ONLINE);
        assertTrue(NetworkDeliveries.accepts(response));
        System.clearProperty(NetworkDeliveries.PROTOCOL_PROPERTY);
        assertFalse(NetworkDeliveries.accepts(response));
        assertFalse(NetworkDeliveries.accepts(command(-1, "cevher sure Ali Seckin 30", ParsedResponse.COMMAND_TYPE.ONLINE)));
    }

    @Test
    public void aRequireOnlineRankPurchaseRunsAtOnceAndIsAckedOnlyOnConfirmation() {
        NetworkDeliveries deliveries = deliveries();
        deliveries.submit(command(5, "cevher sure Ali Seckin 30", ParsedResponse.COMMAND_TYPE.ONLINE));

        assertEquals(1, executed.size());
        assertEquals("cevher sure Ali Seckin 30 ms-5", executed.get(0));
        assertTrue(deliveries.isPending(5));
        assertTrue(acked.isEmpty());

        deliveries.confirm(5);
        assertFalse(deliveries.isPending(5));
        assertEquals(1, acked.size());
        assertEquals(Integer.valueOf(5), acked.get(0));
    }

    @Test
    public void anUnconfirmedDeliveryIsRetriedWithGrowingGapsAndTheSameKey() {
        NetworkDeliveries deliveries = deliveries();
        deliveries.submit(command(5, "cevher ver Ali 500", ParsedResponse.COMMAND_TYPE.OFFLINE));

        clock.addAndGet(NetworkDeliveries.FIRST_RETRY_MILLIS - 1);
        deliveries.retryDue();
        assertEquals(1, executed.size());

        clock.addAndGet(1);
        deliveries.retryDue();
        assertEquals(2, executed.size());
        assertEquals(executed.get(0), executed.get(1));

        clock.addAndGet(NetworkDeliveries.FIRST_RETRY_MILLIS);
        deliveries.retryDue();
        assertEquals("second gap is longer", 2, executed.size());
        clock.addAndGet(NetworkDeliveries.FIRST_RETRY_MILLIS);
        deliveries.retryDue();
        assertEquals(3, executed.size());

        deliveries.confirm(5);
        clock.addAndGet(NetworkDeliveries.MAX_RETRY_MILLIS * 2);
        deliveries.retryDue();
        assertEquals(3, executed.size());
    }

    @Test
    public void aPurchaseTheStoreDroppedMeanwhileIsNotRetried() {
        NetworkDeliveries deliveries = deliveries();
        deliveries.submit(command(5, "cevher sure Ali Seckin 30", ParsedResponse.COMMAND_TYPE.ONLINE));
        storeRejects.add(5);
        clock.addAndGet(NetworkDeliveries.FIRST_RETRY_MILLIS);
        deliveries.retryDue();
        assertEquals(1, executed.size());
        assertFalse(deliveries.isPending(5));
        assertTrue("never reported executed", acked.isEmpty());
    }

    @Test
    public void noAnswerFromTheStoreMeansNoRetryThisRound() {
        NetworkDeliveries deliveries = deliveries();
        deliveries.submit(command(5, "cevher sure Ali Seckin 30", ParsedResponse.COMMAND_TYPE.ONLINE));
        storeRejects = null;
        clock.addAndGet(NetworkDeliveries.FIRST_RETRY_MILLIS);
        deliveries.retryDue();
        assertEquals(1, executed.size());
        assertTrue(deliveries.isPending(5));
    }

    @Test
    public void retriesWaitWhileCevherIsDown() {
        NetworkDeliveries deliveries = deliveries();
        deliveries.submit(command(5, "cevher ver Ali 500", ParsedResponse.COMMAND_TYPE.OFFLINE));
        System.clearProperty(NetworkDeliveries.PROTOCOL_PROPERTY);
        clock.addAndGet(NetworkDeliveries.MAX_RETRY_MILLIS * 3);
        deliveries.retryDue();
        assertEquals(1, executed.size());
    }

    @Test
    public void aRestartBringsBackWhatWasNotConfirmed() {
        NetworkDeliveries before = deliveries();
        before.submit(command(5, "cevher sure Ali Seckin 30", ParsedResponse.COMMAND_TYPE.ONLINE));
        before.submit(command(6, "cevher ver Ali 500", ParsedResponse.COMMAND_TYPE.OFFLINE));
        before.confirm(6);

        executed.clear();
        NetworkDeliveries after = deliveries();
        assertEquals(1, after.size());
        assertTrue(after.isPending(5));

        clock.addAndGet(NetworkDeliveries.STARTUP_DELAY_MILLIS - 1);
        after.retryDue();
        assertTrue("waits for the plugins to come up", executed.isEmpty());
        clock.addAndGet(1);
        after.retryDue();
        assertEquals(1, executed.size());
        assertEquals("cevher sure Ali Seckin 30 ms-5", executed.get(0));
    }

    @Test
    public void submittingTheSameIdAgainKeepsOneEntry() {
        NetworkDeliveries deliveries = deliveries();
        deliveries.submit(command(5, "cevher ver Ali 500", ParsedResponse.COMMAND_TYPE.OFFLINE));
        deliveries.submit(command(5, "cevher ver Ali 500", ParsedResponse.COMMAND_TYPE.OFFLINE));
        assertEquals(1, deliveries.size());
    }

    @Test
    public void aConfirmationForAnIdNotOnFileIsStillReportedAsExecuted() {
        NetworkDeliveries deliveries = deliveries();
        deliveries.confirm(42);
        assertEquals(1, acked.size());
    }

    @Test
    public void anUnreadableFileIsSetAsideNotOverwritten() throws Exception {
        Files.write(file.toPath(), "{not json".getBytes(StandardCharsets.UTF_8));
        NetworkDeliveries deliveries = deliveries();
        assertEquals(0, deliveries.size());
        File[] aside = folder.getRoot().listFiles((dir, name) -> name.startsWith("networkDeliveries.json.unreadable-"));
        assertNotNull(aside);
        assertEquals(1, aside.length);
    }
}
