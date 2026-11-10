package io.github.rbaxtiyorov.logchi.core.mask;

import io.github.rbaxtiyorov.logchi.core.LogchiSettings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Removes secrets from rendered log text.
 *
 * <p>Key matching is deliberately broad: any key containing a sensitive word is masked, so
 * {@code accessToken} and {@code x-auth-token} are caught by {@code token}. A false positive costs a
 * redacted value; a false negative puts a secret on disk.
 */
public final class Masker {

    public static final List<String> DEFAULT_KEYS = List.of(
            "password", "passwd", "pwd", "secret", "token", "authorization", "api-key", "apikey", "api_key",
            "cardnumber", "card_number", "cvv", "cvc");

    /** 14 to 19 digits, optionally grouped. 13 is excluded because epoch milliseconds have 13 digits. */
    private static final Pattern CARD = Pattern.compile("(?<![\\d-])\\d(?:[ -]?\\d){13,18}(?![\\d-])");

    public static final Masker NONE = new Masker(Set.of(), List.of(), false, "***", false);

    private final Set<String> keys;
    private final Pattern keyValue;
    private final List<Pattern> patterns;
    private final boolean cards;
    private final String replacement;
    private final boolean active;

    private Masker(Set<String> keys, List<Pattern> patterns, boolean cards, String replacement, boolean active) {
        this.keys = keys;
        this.patterns = patterns;
        this.cards = cards;
        this.replacement = replacement;
        this.active = active;
        this.keyValue = keys.isEmpty() ? null : compile(keys);
    }

    public static Masker from(LogchiSettings.Mask settings) {
        if (!settings.isEnabled()) {
            return NONE;
        }
        Set<String> keys = new LinkedHashSet<>();
        if (settings.isUseDefaults()) {
            keys.addAll(DEFAULT_KEYS);
        }
        settings.getKeys().forEach(k -> keys.add(k.toLowerCase(Locale.ROOT)));
        List<Pattern> patterns = new ArrayList<>();
        settings.getPatterns().forEach(p -> patterns.add(Pattern.compile(p)));
        return new Masker(keys, patterns, settings.isCards(), settings.getReplacement(), true);
    }

    private static Pattern compile(Set<String> keys) {
        String alternatives = keys.stream().map(Pattern::quote).collect(Collectors.joining("|"));
        // The look-behind anchors matching to word starts, which keeps this linear on long tokens.
        return Pattern.compile("(?i)(?<![\\w-])([\\w-]*(?:" + alternatives + ")[\\w-]*)"
                + "(\\\\?[\"']?\\s*[:=]\\s*)"
                + "((?:(?:bearer|basic|token)\\s+)?"
                + "(?:\\\\\"[^\\\\\"]*\\\\\"|\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*'|[^\\s,;&}\\])\"']+))");
    }

    public boolean isSensitiveKey(String key) {
        if (!active || key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        return keys.stream().anyMatch(lower::contains);
    }

    public String replacement() {
        return replacement;
    }

    public String mask(String text) {
        if (!active || text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        if (keyValue != null && (result.indexOf('=') >= 0 || result.indexOf(':') >= 0)) {
            result = maskKeyValues(result);
        }
        if (cards) {
            result = maskCards(result);
        }
        for (Pattern pattern : patterns) {
            result = pattern.matcher(result).replaceAll(Matcher.quoteReplacement(replacement));
        }
        return result;
    }

    private String maskKeyValues(String text) {
        Matcher m = keyValue.matcher(text);
        if (!m.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        do {
            String value = m.group(3);
            String masked;
            if (value.startsWith("\\\"")) {
                masked = "\\\"" + replacement + "\\\"";
            } else if (value.startsWith("\"")) {
                masked = '"' + replacement + '"';
            } else if (value.startsWith("'")) {
                masked = '\'' + replacement + '\'';
            } else {
                masked = replacement;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + m.group(2) + masked));
        } while (m.find());
        m.appendTail(out);
        return out.toString();
    }

    private String maskCards(String text) {
        Matcher m = CARD.matcher(text);
        StringBuilder out = null;
        while (m.find()) {
            if (luhn(m.group())) {
                if (out == null) {
                    out = new StringBuilder(text.length());
                }
                m.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
        }
        if (out == null) {
            return text;
        }
        m.appendTail(out);
        return out.toString();
    }

    private static boolean luhn(String candidate) {
        int sum = 0;
        boolean doubled = false;
        for (int i = candidate.length() - 1; i >= 0; i--) {
            char c = candidate.charAt(i);
            if (c < '0' || c > '9') {
                continue;
            }
            int digit = c - '0';
            if (doubled) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubled = !doubled;
        }
        return sum % 10 == 0;
    }
}
