package me.chrommob.minestore.common.util;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class SecretRedactorTest {
    @Test
    public void masksKeysInUrlsAndBodies() {
        SecretRedactor redactor = new SecretRedactor(Arrays.asList("ApiKey123456", "Secret98765"));
        String line = "URL: https://store.example/api/ApiKey123456/donation_goal/ and https://store.example/api/servers/Secret98765/commands/queue/";
        String out = redactor.redact(line);
        assertFalse(out.contains("ApiKey123456"));
        assertFalse(out.contains("Secret98765"));
        assertTrue(out.contains("/api/" + SecretRedactor.MASK + "/donation_goal/"));
    }

    @Test
    public void longerSecretIsMaskedWholeWhenItContainsAShorterOne() {
        SecretRedactor redactor = new SecretRedactor(Arrays.asList("abcdef", "abcdefghij"));
        assertEquals("x " + SecretRedactor.MASK + " y", redactor.redact("x abcdefghij y"));
    }

    @Test
    public void ignoresNullsAndTooShortValues() {
        SecretRedactor redactor = new SecretRedactor(Arrays.asList(null, "", "abc"));
        assertEquals("abc stays", redactor.redact("abc stays"));
        assertNull(redactor.redact(null));
    }
}
