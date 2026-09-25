package me.chrommob.minestore.common.commandHolder.type;

import java.util.List;

public class CheckResponse {
    public static CheckResponse empty() {
        return new CheckResponse(false, null);
    }

    public CheckResponse(boolean status, List<CheckResponses> results) {
        this.status = status;
        this.results = results;
    }

    private boolean status;
    private List<CheckResponses> results;
    private String error;
    public static class CheckResponses {
        private int cmd_id;
        private boolean status;
        private String error;

        public int cmd_id() {
            return cmd_id;
        }

        public boolean status() {
            return status;
        }

        public String error() {
            return error;
        }
    }

    public boolean status() {
        return status;
    }

    public List<CheckResponses> results() {
        return results;
    }

    public String error() {
        return error;
    }

    /**
     * Ids the store still has in its queue. Read from {@code results} whatever
     * {@code status} says: the store sets {@code status} to false when none of
     * the ids exist, and that is exactly the answer that tells us which stored
     * commands are dead.
     */
    public java.util.Set<Integer> validIds() {
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        if (results == null) {
            return ids;
        }
        for (CheckResponses result : results) {
            if (result.status) {
                ids.add(result.cmd_id);
            }
        }
        return ids;
    }

    /** Ids the store explicitly rejected, with its reason. */
    public java.util.Map<Integer, String> rejectedIds() {
        java.util.Map<Integer, String> ids = new java.util.HashMap<>();
        if (results == null) {
            return ids;
        }
        for (CheckResponses result : results) {
            if (!result.status) {
                ids.put(result.cmd_id, result.error == null ? "Unknown error" : result.error);
            }
        }
        return ids;
    }

    /** False when the call itself failed and nothing is known about any id. */
    public boolean answered() {
        return results != null;
    }
}
