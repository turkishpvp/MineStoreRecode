package me.chrommob.minestore.common.commandHolder;

import me.chrommob.minestore.api.Registries;
import me.chrommob.minestore.api.event.MineStoreEventBus;
import me.chrommob.minestore.api.event.types.MineStoreExecuteEvent;
import me.chrommob.minestore.api.event.types.MineStoreExecuteIntentEvent;
import me.chrommob.minestore.api.event.types.MineStorePlayerJoinEvent;
import me.chrommob.minestore.api.interfaces.commands.ParsedResponse;
import me.chrommob.minestore.common.MineStoreCommon;
import me.chrommob.minestore.common.commandHolder.type.CheckResponse;
import me.chrommob.minestore.common.commandHolder.type.StoredCommand;
import me.chrommob.minestore.common.config.ConfigKeys;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class CommandStorage {
    private final MineStoreCommon plugin;
    public CommandStorage(MineStoreCommon plugin) {
        this.plugin = plugin;
        MineStoreEventBus.registerListener(plugin.getInternalAddon(), MineStorePlayerJoinEvent.class, event -> onPlayerJoin(event.getUsername()));
    }
    private Map<String, List<String>> commands;
    private Map<String, List<StoredCommand>> newCommands;
    private NetworkDeliveries network;
    private volatile boolean savedMigrated = false;

    private void remove(String username, String command) {
        plugin.debug(this.getClass(), "Removing " + command + " for " + username + " from command storage");
        List<String> stored = commands.get(username.toLowerCase());
        if (stored == null) {
            return;
        }
        stored.remove(command);
        plugin.commandDumper().update(commands);
    }

    private void removeNewCommand(StoredCommand storedCommand, String username) {
        plugin.debug(this.getClass(), "Removing " + storedCommand.command() + " for " + username + " from new command storage");
        List<StoredCommand> stored = newCommands.get(username.toLowerCase());
        if (stored == null) {
            return;
        }
        stored.remove(storedCommand);
        if (stored.isEmpty()) {
            newCommands.remove(username.toLowerCase());
        }
        plugin.newCommandDumper().update(newCommands);
    }

    private void addCommand(String username, String command, int requestId) {
        plugin.debug(this.getClass(), "Adding " + command + " to " + username + " in command storage");
        username = username.toLowerCase();
        if (MineStoreCommon.version().requires("3.0.0")) {
            addNewCommand(username, command, requestId);
        } else {
            add(username, command);
        }
    }

    private void add(String username, String command) {
        if (commands.containsKey(username)) {
            commands.get(username).add(command);
        } else {
            commands.put(username, new ArrayList<>(Collections.singletonList(command)));
        }
        plugin.commandDumper().update(commands);
    }

    private void addNewCommand(String username, String command, int requestId) {
        if (newCommands.containsKey(username)) {
            newCommands.get(username).add(new StoredCommand(command, requestId));
        } else {
            newCommands.put(username, new ArrayList<>(Collections.singletonList(new StoredCommand(command, requestId))));
        }
        plugin.newCommandDumper().update(newCommands);
    }

    private void onPlayerJoin(String username) {
        username = username.toLowerCase();
        List<ParsedResponse> parsedResponses;
        if (MineStoreCommon.version().requires("3.0.0")) {
            parsedResponses = playerJoinNew(username);
        } else {
            parsedResponses = playerJoinOld(username);
        }
        if (parsedResponses.isEmpty()) {
            return;
        }
        plugin.debug(this.getClass(), "Executing new commands for " + username);
        executeWithOnlineCheck(parsedResponses, false);
    }

    private List<ParsedResponse> playerJoinNew(String username) {
        List<ParsedResponse> parsedResponses = new ArrayList<>();
        if (!newCommands.containsKey(username)) {
            return parsedResponses;
        }
        for (StoredCommand storedCommand : newCommands.get(username)) {
            parsedResponses.add(storedCommand.toParsedResponse(username));
        }
        return parsedResponses;
    }

    private List<ParsedResponse> playerJoinOld(String username) {
        List<ParsedResponse> parsedResponses = new ArrayList<>();
        if (!commands.containsKey(username)) {
            return parsedResponses;
        }
        for (String storedCommand : commands.get(username)) {
            parsedResponses.add(new ParsedResponse(ParsedResponse.TYPE.COMMAND, ParsedResponse.COMMAND_TYPE.ONLINE, storedCommand, username, 0));
        }
        return parsedResponses;
    }

    /**
     * @return ids handed to {@link NetworkDeliveries}: the caller must not report
     *         them as executed, that happens when Cevher confirms them
     */
    public Set<Integer> listener(List<ParsedResponse> commands) {
        Set<Integer> networkIds = new HashSet<>();
        for (ParsedResponse command : commands) {
            if (NetworkDeliveries.accepts(command)) {
                // Network-wide (see NetworkDeliveries): Cevher applies it wherever the
                // player is, online or not, and confirms it.
                networkIds.add(command.commandId());
                network.submit(command);
                continue;
            }
            // TurkishPvP: the store's "player must be online" flag is ignored. Every
            // command runs as soon as it arrives, the player does not have to be on
            // this server or online at all (owner's decision, 3 Oct 2026). Upstream
            // parked these in savedCommands.json until the player joined THIS server,
            // which with lobby load balancing meant many purchases never ran.
            execute(command);
        }
        return networkIds;
    }

    public boolean isNetworkPending(int id) {
        return network != null && network.isPending(id);
    }

    public void submitNetworkDelivery(ParsedResponse response) {
        network.submit(response);
    }

    public void confirmNetworkDelivery(int id) {
        network.confirm(id);
    }

    /** The store has the result of this Cevher payment: confirm its queued copies. */
    public void confirmCharge(String paymentId) {
        if (network != null) {
            network.confirmCharge(paymentId);
        }
    }

    /**
     * Called on every queue poll: re-runs unconfirmed network deliveries and, once,
     * moves Cevher commands that an older build parked in savedCommands.json for
     * "when the player joins this server" over to the network path.
     */
    public void networkTick() {
        if (network == null) {
            return;
        }
        if (!savedMigrated) {
            savedMigrated = true;
            migrateSaved();
        }
        network.retryDue();
    }

    private void migrateSaved() {
        if (newCommands == null || !MineStoreCommon.version().requires(3, 2, 5)) {
            return;
        }
        List<ParsedResponse> parked = new ArrayList<>();
        List<ParsedResponse> others = new ArrayList<>();
        for (Map.Entry<String, List<StoredCommand>> entry : newCommands.entrySet()) {
            for (StoredCommand stored : entry.getValue()) {
                ParsedResponse response = stored.toParsedResponse(entry.getKey());
                if (NetworkDeliveries.accepts(response)) {
                    parked.add(response);
                } else if (!NetworkDeliveries.handles(response.command())) {
                    // Parked by an older build for "when the player joins". The
                    // online requirement is gone: run it now (after the same store
                    // check the join path does), then report it executed.
                    others.add(response);
                }
                // A Cevher command whose protocol is not up stays parked for later.
            }
        }
        if (!others.isEmpty()) {
            plugin.log("Running " + others.size() + " stored commands that were waiting for their player.");
            executeWithOnlineCheck(others, false);
        }
        if (parked.isEmpty()) {
            return;
        }
        Set<Integer> ids = new HashSet<>();
        for (ParsedResponse response : parked) {
            ids.add(response.commandId());
        }
        // Same check the join path does: a purchase refunded while it was parked
        // must not run.
        plugin.webListener().checkCommands(ids).thenAccept(check -> {
            if (!check.answered()) {
                plugin.log("Could not check parked Cevher commands with the store, trying again next round: " + check.error());
                savedMigrated = false;
                return;
            }
            for (ParsedResponse response : parked) {
                int id = response.commandId();
                if (!check.validIds().contains(id) && check.rejectedIds().containsKey(id)) {
                    plugin.log("Not running parked \"" + response.command() + "\" (id " + id + "), the store no longer has it: " + check.rejectedIds().get(id));
                    removeNewCommand(StoredCommand.fromParsedResponse(response), response.username());
                    continue;
                }
                plugin.log("Delivering parked command " + id + " for " + response.username() + " network-wide.");
                network.submit(response);
                removeNewCommand(StoredCommand.fromParsedResponse(response), response.username());
            }
        }).exceptionally(e -> {
            plugin.debug(this.getClass(), e);
            savedMigrated = false;
            return null;
        });
    }

    private void executeWithOnlineCheck(List<ParsedResponse> parsedCommands, boolean newCommands) {
        if (parsedCommands.isEmpty()) {
            return;
        }
        if (MineStoreCommon.version().requires(3, 2, 5)) {
            Set<Integer> toCheckIds = new HashSet<>();
            for (ParsedResponse parsedResponse : parsedCommands) {
                toCheckIds.add(parsedResponse.commandId());
            }
            plugin.webListener().checkCommands(toCheckIds).thenAcceptAsync(checkResponses -> {
                if (!checkResponses.answered()) {
                    // Nothing is known. Stored commands stay stored for the next
                    // join; fresh ones were already acknowledged as delivered, so
                    // they must be stored now or they are lost.
                    plugin.log("Could not check commands with the store, they will run on the player's next join: " + checkResponses.error());
                    if (newCommands) {
                        for (ParsedResponse parsedResponse : parsedCommands) {
                            addCommand(parsedResponse.username(), parsedResponse.command(), parsedResponse.commandId());
                        }
                    }
                    return;
                }
                Set<Integer> successIds = checkResponses.validIds();
                Map<Integer, String> errors = checkResponses.rejectedIds();
                List<ParsedResponse> successful = new ArrayList<>();
                for (ParsedResponse parsedResponse : parsedCommands) {
                    if (!successIds.contains(parsedResponse.commandId()) && errors.containsKey(parsedResponse.commandId())) {
                        if (!newCommands) {
                            removeNewCommand(StoredCommand.fromParsedResponse(parsedResponse), parsedResponse.username());
                        }
                        plugin.log("Not running \"" + parsedResponse.command() + "\" (id " + parsedResponse.commandId() + "), the store no longer has it: " + errors.get(parsedResponse.commandId()));
                        continue;
                    }
                    successful.add(parsedResponse);
                }
                executeWithApiCheck(successful, newCommands);
            }).exceptionally(e -> {
                plugin.debug(this.getClass(), e);
                return null;
            });
        } else {
            executeWithApiCheck(parsedCommands, newCommands);
        }
    }

    private void executeWithApiCheck(List<ParsedResponse> parsedCommands, boolean newCommands) {
        CompletableFuture.runAsync(() -> {
            for (ParsedResponse parsedResponse : parsedCommands) {
                if (!shouldExecute(parsedResponse)) {
                    if (!newCommands) {
                        continue;
                    }
                    addCommand(parsedResponse.username(), parsedResponse.command(), parsedResponse.commandId());
                    continue;
                }
                execute(parsedResponse);
                try {
                    //Give the server some time to process the command
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                }
                if (MineStoreCommon.version().requires(3, 0, 0)) {
                    plugin.webListener().postExecuted(String.valueOf(parsedResponse.commandId()));
                }
                if (newCommands) {
                    continue;
                }
                if (MineStoreCommon.version().requires(3, 0, 0)) {
                    removeNewCommand(StoredCommand.fromParsedResponse(parsedResponse), parsedResponse.username());
                } else {
                    remove(parsedResponse.username(), parsedResponse.command());
                }
            }
        }).exceptionally(e -> {
            plugin.debug(this.getClass(), e);
            return null;
        });
    }

    public boolean shouldExecute(ParsedResponse parsedResponse) {
        MineStoreExecuteIntentEvent intent = new MineStoreExecuteIntentEvent(parsedResponse.commandType(), parsedResponse.username(), parsedResponse.command(), parsedResponse.commandId());
        intent.call();
        return !intent.isCancelled();
    }

    private void execute(ParsedResponse parsedResponse) {
        String command = parsedResponse.command();
        String username = parsedResponse.username();
        int requestId = parsedResponse.commandId();
        if (requestId > 0 && NetworkDeliveries.needsKey(command) && NetworkDeliveries.protocolAvailable()) {
            // Every path (network, legacy join path, parked copies) carries the key,
            // so Cevher applies a purchase at most once whichever path runs it.
            command = NetworkDeliveries.withKey(command, requestId);
        }
        MineStoreExecuteEvent event = new MineStoreExecuteEvent(username, command, requestId);
        event.call();
        if (!event.isCancelled() &&ConfigKeys.COMMAND_EXEC_LOGGING.getValue()) {
            plugin.log("Executing command: " + command);
        }
        Registries.COMMAND_EXECUTER.get().execute(event);
    }

    public void init() {
        commands = plugin.commandDumper().load();
        newCommands = plugin.newCommandDumper().load();
        File folder = Registries.CONFIG_FILE.get().getParentFile();
        network = new NetworkDeliveries(new File(folder, "networkDeliveries.json"), this::execute,
                id -> plugin.webListener().postExecuted(String.valueOf(id)), plugin::log, System::currentTimeMillis,
                ids -> plugin.webListener().checkCommands(ids).thenApply(check -> {
                    if (!check.answered()) {
                        return null;
                    }
                    Set<Integer> rejected = new HashSet<>();
                    for (Integer id : ids) {
                        if (!check.validIds().contains(id) && check.rejectedIds().containsKey(id)) {
                            rejected.add(id);
                        }
                    }
                    return rejected;
                }));
        network.load();
        savedMigrated = false;
    }
}
