package io.github.rbaxtiyorov.logchi.spring.web;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/** Cleans request-derived values. Headers are attacker-controlled and end up in logs and responses. */
final class ContextValues {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int MAX_LENGTH = 128;

    private ContextValues() {
    }

    /** Reuses a well-formed incoming id so traces span services. Anything else gets a fresh one. */
    static String traceId(String incoming) {
        if (incoming != null && SAFE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return Long.toHexString(ThreadLocalRandom.current().nextLong() | Long.MIN_VALUE);
    }

    static String clean(String value) {
        StringBuilder sb = new StringBuilder(Math.min(value.length(), MAX_LENGTH));
        for (int i = 0; i < value.length() && sb.length() < MAX_LENGTH; i++) {
            char c = value.charAt(i);
            if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** {@code X-Tenant-Id} becomes {@code tenantId}. */
    static String mdcKey(String header) {
        String name = header.regionMatches(true, 0, "X-", 0, 2) ? header.substring(2) : header;
        StringBuilder key = new StringBuilder();
        boolean upper = false;
        for (char c : name.toCharArray()) {
            if (c == '-' || c == '_') {
                upper = key.length() > 0;
            } else if (upper) {
                key.append(Character.toUpperCase(c));
                upper = false;
            } else {
                key.append(key.length() == 0 ? String.valueOf(c).toLowerCase(Locale.ROOT).charAt(0) : c);
            }
        }
        return key.toString();
    }
}
