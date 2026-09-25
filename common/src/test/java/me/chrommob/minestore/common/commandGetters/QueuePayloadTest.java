package me.chrommob.minestore.common.commandGetters;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import me.chrommob.minestore.common.commandGetters.dataTypes.GsonReponse;
import me.chrommob.minestore.common.commandGetters.dataTypes.PostResponse;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** Payloads in the exact shape SettingsController and ItemsController::sendListener write. */
public class QueuePayloadTest {
    private final Gson gson = new Gson();

    private List<GsonReponse> parseQueue(String json) {
        return gson.fromJson(json, new TypeToken<List<GsonReponse>>() { }.getType());
    }

    @Test
    public void queueRowWithBooleanFlagAndPackageName() {
        List<GsonReponse> rows = parseQueue("[{\"username\":\"Deniz\",\"is_online_required\":true,"
                + "\"command\":\"cevher sure Deniz Seckin 30\",\"package_name\":\"Seçkin 1 Ay\",\"id\":41}]");
        assertEquals(1, rows.size());
        GsonReponse row = rows.get(0);
        assertNull(row.getType());
        assertEquals("Deniz", row.username());
        assertEquals("cevher sure Deniz Seckin 30", row.command());
        assertEquals(41, row.requestId());
        assertTrue(row.isPlayerOnlineNeeded());
    }

    @Test
    public void onlineFlagAcceptsTinyintAndStrings() {
        List<GsonReponse> rows = parseQueue("[{\"username\":\"a\",\"is_online_required\":0,\"command\":\"x\",\"id\":1},"
                + "{\"username\":\"a\",\"is_online_required\":1,\"command\":\"x\",\"id\":2},"
                + "{\"username\":\"a\",\"is_online_required\":\"1\",\"command\":\"x\",\"id\":3},"
                + "{\"username\":\"a\",\"is_online_required\":false,\"command\":\"x\",\"id\":4},"
                + "{\"username\":\"a\",\"command\":\"x\",\"id\":5}]");
        assertFalse(rows.get(0).isPlayerOnlineNeeded());
        assertTrue(rows.get(1).isPlayerOnlineNeeded());
        assertTrue(rows.get(2).isPlayerOnlineNeeded());
        assertFalse(rows.get(3).isPlayerOnlineNeeded());
        assertFalse(rows.get(4).isPlayerOnlineNeeded());
    }

    @Test
    public void authorizationRowWithNumericAuthId() {
        List<GsonReponse> rows = parseQueue("[{\"type\":\"authorization\",\"username\":\"Deniz\",\"auth_id\":1234,\"id\":9}]");
        assertEquals("authorization", rows.get(0).getType());
        assertEquals("1234", rows.get(0).authId());
    }

    @Test
    public void emptyQueueIsAnArray() {
        assertTrue(parseQueue("[]").isEmpty());
    }

    @Test
    public void deliveredAnswerParses() {
        PostResponse response = gson.fromJson("{\"status\":true,\"processed\":[3],\"results\":[{\"id\":3,\"status\":true},"
                + "{\"id\":4,\"status\":false,\"error\":\"Command not found\"}]}", PostResponse.class);
        assertTrue(response.status);
        assertArrayEquals(new int[]{3}, response.processedIds);
        assertEquals("Command not found", response.results[1].error);
    }
}
