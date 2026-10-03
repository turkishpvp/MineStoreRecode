package me.chrommob.minestore.common.commandHolder;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import me.chrommob.minestore.api.interfaces.commands.ParsedResponse;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/**
 * TurkishPvP: store commands that are delivered network-wide and reported as
 * executed only after the receiving plugin confirms them.
 *
 * <p>Which commands: {@code cevher ver|al|sure|chargeback ...}. Cevher applies them
 * to a player wherever they are (another lobby, practice, offline), so the
 * store's "player must be online" flag, which upstream reads as "online on THIS
 * server", would only delay them: this server is the only one that pulls the
 * queue (two pullers could run a purchase twice), and with lobby load balancing
 * about half of the buyers never land here.
 *
 * <p>How it stays at most once:
 * <ul>
 *   <li>the queue id is appended as {@code ms-<id>} (or replaces
 *       {@code {command_id}} when the store command spells it out). Cevher keeps
 *       a MySQL ledger keyed on it with a primary key, so running the same id
 *       again, from a retry, a restart or by hand, is a no-op;</li>
 *   <li>the command is written to {@code networkDeliveries.json} BEFORE it runs
 *       and removed only when Cevher confirms ({@code minestore delivered <id>}).
 *       Until then it is re-run with growing gaps, and only then is the store
 *       told "executed". A crash between the two leaves the file, so the command
 *       comes back after the restart instead of being lost.</li>
 * </ul>
 *
 * <p>Website Cevher payments ({@code ms chargeBalance <user> <amount> <payment_id> <sig>})
 * go through here as well. The store always queues them as "player must be
 * online", but Cevher charges the balance by UUID in its database, so the player
 * does not have to be anywhere. The command is not keyed: Cevher keys the charge on
 * the payment id ({@code pay-<payment_id>}), which also covers the store queueing
 * the same payment once per cart item. {@code ChargeBalanceCommand} confirms it
 * ({@link #confirmCharge}) once the store has been told the result.
 *
 * <p>Gate: all of this happens only while Cevher advertises the protocol in
 * the {@link #PROTOCOL_PROPERTY} system property ({@link #CHARGE_PROPERTY} for
 * payments). An older Cevher that does not know the key must never receive
 * retries; without the property the command is held in the store's queue.
 */
public final class NetworkDeliveries {
    public static final String PROTOCOL_PROPERTY = "turkishpvp.cevher.delivery";
    public static final String PROTOCOL_VERSION = "1";
    public static final String PLACEHOLDER = "{command_id}";
    public static final String CHARGE_PROPERTY = "turkishpvp.cevher.charge";
    public static final String CHARGE_VERSION = "1";

    private static final String ROOT = "cevher";
    private static final Set<String> MINESTORE_ROOTS = new HashSet<>(Arrays.asList("ms", "minestore"));
    private static final String CHARGE = "chargebalance";
    private static final Set<String> SUBCOMMANDS = new HashSet<>(Arrays.asList("ver", "al", "sure", "chargeback"));

    static final long FIRST_RETRY_MILLIS = 30_000L;
    static final long MAX_RETRY_MILLIS = 10 * 60_000L;
    static final long STARTUP_DELAY_MILLIS = 20_000L;

    private final File file;
    private final Consumer<ParsedResponse> executor;
    private final IntConsumer ackExecuted;
    private final Consumer<String> log;
    private final LongSupplier clock;
    private final RetryCheck check;
    private final Gson gson = new Gson();
    private final Map<Integer, Pending> pending = new LinkedHashMap<>();

    public NetworkDeliveries(File file, Consumer<ParsedResponse> executor, IntConsumer ackExecuted,
                             Consumer<String> log, LongSupplier clock, RetryCheck check) {
        this.file = file;
        this.executor = executor;
        this.ackExecuted = ackExecuted;
        this.log = log;
        this.clock = clock;
        this.check = check;
    }

    /**
     * Asks the store which of these ids it no longer has (refunded, deleted).
     * Completes with those ids, or with null when the store did not answer.
     * A retry can come days later; a purchase refunded meanwhile must not run.
     */
    @FunctionalInterface
    public interface RetryCheck {
        CompletableFuture<Set<Integer>> rejected(Set<Integer> ids);
    }

    /** One command waiting for its confirmation. Field names are the file format. */
    static final class Pending {
        int id;
        String username;
        String command;
        int attempts;
        long nextAttemptAt;

        Pending() {
        }

        Pending(int id, String username, String command) {
            this.id = id;
            this.username = username;
            this.command = command;
        }

        ParsedResponse toParsedResponse() {
            return new ParsedResponse(ParsedResponse.TYPE.COMMAND, ParsedResponse.COMMAND_TYPE.OFFLINE, command, username, id);
        }
    }

    public static boolean protocolAvailable() {
        return PROTOCOL_VERSION.equals(System.getProperty(PROTOCOL_PROPERTY));
    }

    public static boolean chargeProtocolAvailable() {
        return CHARGE_VERSION.equals(System.getProperty(CHARGE_PROPERTY));
    }

    /** Whether Cevher can take this command now (it is one of ours and its protocol is advertised). */
    public static boolean available(String command) {
        if (isCharge(command)) {
            return chargeProtocolAvailable();
        }
        return needsKey(command) && protocolAvailable();
    }

    /** Cevher store deliveries and website Cevher payments. */
    public static boolean handles(String command) {
        return needsKey(command) || isCharge(command);
    }

    /** {@code cevher ver|al|sure|chargeback ...}, with or without a leading slash: these carry {@code ms-<id>}. */
    public static boolean needsKey(String command) {
        String[] parts = words(command);
        if (parts.length < 2) {
            return false;
        }
        return ROOT.equalsIgnoreCase(parts[0]) && SUBCOMMANDS.contains(parts[1].toLowerCase(Locale.ROOT));
    }

    /** {@code ms|minestore chargeBalance <user> <amount> <payment_id> <signature>}. */
    public static boolean isCharge(String command) {
        String[] parts = words(command);
        return parts.length == 6 && MINESTORE_ROOTS.contains(parts[0].toLowerCase(Locale.ROOT))
                && CHARGE.equals(parts[1].toLowerCase(Locale.ROOT));
    }

    /** The payment id of a {@link #isCharge} command, else null. */
    public static String chargePaymentId(String command) {
        return isCharge(command) ? words(command)[4] : null;
    }

    private static String[] words(String command) {
        if (command == null || command.trim().isEmpty()) {
            return new String[0];
        }
        String[] parts = command.trim().split("\\s+");
        if (parts[0].startsWith("/")) {
            parts[0] = parts[0].substring(1);
        }
        return parts;
    }

    /** Whether this response goes through here rather than the upstream path. */
    public static boolean accepts(ParsedResponse response) {
        return response.type() == ParsedResponse.TYPE.COMMAND && response.commandId() > 0
                && available(response.command());
    }

    /**
     * The command with its delivery key. Idempotent: a command that already ends
     * with this key (a stored copy that was keyed before) is left alone.
     */
    public static String withKey(String command, int id) {
        String key = "ms-" + id;
        if (command.contains(PLACEHOLDER)) {
            return command.replace(PLACEHOLDER, key);
        }
        String trimmed = command.trim();
        if (trimmed.endsWith(" " + key)) {
            return trimmed;
        }
        return trimmed + " " + key;
    }

    /** Remembers the command (on disk first) and runs it. */
    public void submit(ParsedResponse response) {
        Pending entry;
        synchronized (this) {
            entry = pending.get(response.commandId());
            if (entry == null) {
                entry = new Pending(response.commandId(), response.username(), response.command());
                pending.put(entry.id, entry);
            }
            scheduleNext(entry);
            save();
        }
        executor.accept(entry.toParsedResponse());
    }

    /**
     * Cevher applied the command (or had already). Forget it and tell the store.
     * The store is told even for an id this file does not know: Cevher only
     * confirms what it has applied, so "executed" is true either way.
     */
    public void confirm(int id) {
        synchronized (this) {
            if (pending.remove(id) != null) {
                save();
            }
        }
        ackExecuted.accept(id);
    }

    /**
     * The store has been told the result of this payment (or it was settled earlier):
     * confirm every queued copy of it. The store queues one copy per cart item.
     */
    public void confirmCharge(String paymentId) {
        if (paymentId == null) {
            return;
        }
        List<Integer> ids = new ArrayList<>();
        synchronized (this) {
            for (Pending entry : pending.values()) {
                if (paymentId.equals(chargePaymentId(entry.command))) {
                    ids.add(entry.id);
                }
            }
        }
        for (int id : ids) {
            confirm(id);
        }
    }

    public synchronized boolean isPending(int id) {
        return pending.containsKey(id);
    }

    public synchronized int size() {
        return pending.size();
    }

    /** Re-runs what is due. Called from the queue poll, roughly every ten seconds. */
    public void retryDue() {
        List<Pending> due = new ArrayList<>();
        long now = clock.getAsLong();
        synchronized (this) {
            for (Pending entry : pending.values()) {
                // Cevher down or reloading: an older module might come back, so wait.
                if (entry.nextAttemptAt <= now && available(entry.command)) {
                    scheduleNext(entry);
                    due.add(entry);
                }
            }
            if (!due.isEmpty()) {
                save();
            }
        }
        if (due.isEmpty()) {
            return;
        }
        Set<Integer> ids = new HashSet<>();
        for (Pending entry : due) {
            ids.add(entry.id);
        }
        CompletableFuture<Set<Integer>> answer;
        try {
            answer = check.rejected(ids);
        } catch (RuntimeException e) {
            answer = null;
        }
        if (answer == null) {
            log.accept("Could not check unconfirmed deliveries with the store, trying again later.");
            return;
        }
        answer.whenComplete((rejected, error) -> {
            if (error != null || rejected == null) {
                log.accept("Could not check unconfirmed deliveries with the store, trying again later.");
                return;
            }
            for (Pending entry : due) {
                if (rejected.contains(entry.id)) {
                    // The store dropped it (refund, manual delete): forget it, do not run it.
                    synchronized (this) {
                        if (pending.remove(entry.id) != null) {
                            save();
                        }
                    }
                    log.accept("Dropping delivery " + entry.id + " for " + entry.username + ", the store no longer has it.");
                    continue;
                }
                log.accept("Delivery " + entry.id + " for " + entry.username + " is not confirmed yet, running it again (attempt "
                        + entry.attempts + "); Cevher will not apply it twice.");
                executor.accept(entry.toParsedResponse());
            }
        });
    }

    private void scheduleNext(Pending entry) {
        entry.attempts++;
        long delay = FIRST_RETRY_MILLIS << Math.min(entry.attempts - 1, 5);
        entry.nextAttemptAt = clock.getAsLong() + Math.min(delay, MAX_RETRY_MILLIS);
    }

    /** Loads what was waiting when the server stopped; the first retry waits for the plugins to come up. */
    public synchronized void load() {
        pending.clear();
        if (!file.exists()) {
            return;
        }
        Type type = new TypeToken<List<Pending>>() {
        }.getType();
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            List<Pending> stored = gson.fromJson(reader, type);
            long first = clock.getAsLong() + STARTUP_DELAY_MILLIS;
            for (Pending entry : stored == null ? Collections.<Pending>emptyList() : stored) {
                if (entry == null || entry.id <= 0 || entry.command == null) {
                    continue;
                }
                entry.nextAttemptAt = first;
                pending.put(entry.id, entry);
            }
        } catch (Exception e) {
            // Do not overwrite a file we could not read: it may be the only copy of a
            // purchase. Set it aside for a human and start empty.
            File aside = new File(file.getParentFile(), file.getName() + ".unreadable-" + clock.getAsLong());
            boolean moved = file.renameTo(aside);
            log.accept("Could not read " + file.getName() + " (" + e.getMessage() + "). "
                    + (moved ? "Moved it to " + aside.getName() : "Could not move it aside")
                    + "; the store deliveries in it are NOT being retried, check them by hand.");
            pending.clear();
            return;
        }
        if (!pending.isEmpty()) {
            log.accept(pending.size() + " store deliveries are waiting for confirmation, they will be retried.");
        }
    }

    /** Write to a temporary file and move it over, so a crash mid-write cannot lose the list. */
    private void save() {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp.toPath(), StandardCharsets.UTF_8)) {
                gson.toJson(new ArrayList<>(pending.values()), writer);
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.accept("Could not write " + file.getName() + ": " + e.getMessage());
        }
    }
}
