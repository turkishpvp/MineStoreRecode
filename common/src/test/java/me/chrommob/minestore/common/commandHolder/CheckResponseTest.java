package me.chrommob.minestore.common.commandHolder;

import com.google.gson.Gson;
import me.chrommob.minestore.common.commandHolder.type.CheckResponse;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

/** Answers of SettingsController::validateCommandInQueue. */
public class CheckResponseTest {
    private final Gson gson = new Gson();

    @Test
    public void mixedAnswer() {
        CheckResponse response = gson.fromJson("{\"status\":true,\"results\":[{\"cmd_id\":5,\"status\":true,\"error\":null},"
                + "{\"cmd_id\":6,\"status\":false,\"error\":\"Command not found\"}]}", CheckResponse.class);
        assertTrue(response.answered());
        assertEquals(Collections.singleton(5), response.validIds());
        assertEquals("Command not found", response.rejectedIds().get(6));
    }

    @Test
    public void allGoneIsStillAnAnswer() {
        // The store sets status=false when none of the ids exist. Upstream stopped
        // there, so dead commands stayed in savedCommands.json and were retried on
        // every join.
        CheckResponse response = gson.fromJson("{\"status\":false,\"results\":[{\"cmd_id\":6,\"status\":false,\"error\":\"Command not found\"}]}", CheckResponse.class);
        assertTrue(response.answered());
        assertTrue(response.validIds().isEmpty());
        assertTrue(response.rejectedIds().containsKey(6));
    }

    @Test
    public void failedCallIsNotAnAnswer() {
        assertFalse(CheckResponse.empty().answered());
        CheckResponse error = gson.fromJson("{\"status\":false,\"error\":\"Invalid server credentials\"}", CheckResponse.class);
        assertFalse(error.answered());
        assertTrue(error.validIds().isEmpty());
        assertTrue(error.rejectedIds().isEmpty());
    }
}
