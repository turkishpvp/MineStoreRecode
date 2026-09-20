package me.chrommob.minestore.common.commandGetters.dataTypes;

import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class GsonReponse {
    @SerializedName("type")
    @Expose
    private String type;

    @SerializedName("auth_id")
    @Expose
    private String auth_id;

    @SerializedName("id")
    @Expose
    private int id;

    @SerializedName("username")
    @Expose
    private String username;

    @SerializedName("command")
    @Expose
    private String command;

    /**
     * MineStore 3.8.8 sends this as the raw `tinyint` from the database, so the
     * wire value is `0` or `1`, not `true` or `false`. Declared as a boolean it
     * made Gson throw {@link com.google.gson.JsonSyntaxException}, and
     * {@code WebListener.fetchData} swallows that exception and returns an empty
     * list: every purchase went undelivered and nothing was logged anywhere.
     *
     * Kept as a primitive so all three shapes parse: boolean, number and string.
     */
    @SerializedName("is_online_required")
    @Expose
    private JsonPrimitive playerOnlineNeeded;

    public String getType() {
        return type;
    }

    public String authId() {
        return auth_id;
    }

    public boolean isPlayerOnlineNeeded() {
        if (playerOnlineNeeded == null) {
            return false;
        }
        if (playerOnlineNeeded.isBoolean()) {
            return playerOnlineNeeded.getAsBoolean();
        }
        if (playerOnlineNeeded.isNumber()) {
            return playerOnlineNeeded.getAsInt() != 0;
        }
        String raw = playerOnlineNeeded.getAsString();
        return "1".equals(raw) || "true".equalsIgnoreCase(raw);
    }

    public int requestId() {
        return id;
    }

    public String username() {
        return username;
    }

    public String command() {
        return command;
    }
}
