package me.chrommob.minestore.common.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Masks configured secrets in text that is about to be logged.
 *
 * The store puts the API key and the weblistener secret in the URL path
 * ({@code /api/<key>/...}, {@code /api/servers/<secret>/...}), the charge
 * signature in the body, and every failed request is written to
 * {@code logs/debug.log} with URL and body. The lobby's log folder held both
 * keys in clear text in dozens of files.
 */
public final class SecretRedactor {
    public static final String MASK = "<redacted>";
    /** Shorter values are not secrets worth masking and would shred the log. */
    private static final int MIN_SECRET_LENGTH = 6;

    private final List<String> secrets;

    public SecretRedactor(Collection<String> secrets) {
        List<String> usable = new ArrayList<>();
        for (String secret : secrets) {
            if (secret != null && secret.length() >= MIN_SECRET_LENGTH && !usable.contains(secret)) {
                usable.add(secret);
            }
        }
        // Longest first, so a secret that contains another is masked whole.
        usable.sort(Comparator.comparingInt(String::length).reversed());
        this.secrets = usable;
    }

    public String redact(String text) {
        if (text == null || secrets.isEmpty()) {
            return text;
        }
        String out = text;
        for (String secret : secrets) {
            out = out.replace(secret, MASK);
        }
        return out;
    }
}
