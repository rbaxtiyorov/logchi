package io.github.rbaxtiyorov.logchi.core.mask;

import io.github.rbaxtiyorov.logchi.core.LogchiSettings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            "cardnumber", "card_number", "cvv", "cvc", "cookie");

    /** 14 to 19 digits, optionally grouped. 13 is excluded because epoch milliseconds have 13 digits. */
    private static final Pattern CARD = Pattern.compile("(?<![\\d-])\\d(?:[ -]?\\d){13,18}(?![\\d-])");

    public static final Masker NONE = new Masker(Set.of(), List.of(), false, "***", false);

    /** A whole word, taken possessively, followed by {@code =} or {@code :}. */
    private static final Pattern KEY =
            Pattern.compile("(?<![\\w-])([\\w-]++)(\\\\?[\"']?\\]?[ \\t]*(?:=>|[:=])[ \\t]*)");

    /** Class names that often start a message, as in {@code TokenService: refreshed}. */
    private static final Pattern TYPE_NAME = Pattern.compile("[A-Z]\\w*(?:Exception|Error|Resolver|Filter|Service"
            + "|Config|Configuration|Provider|Handler|Controller|Manager|Repository|Factory|Validator|Interceptor"
            + "|Listener)");

    /**
     * The value after a key. Quoted values are scanned with possessive loops, since a group that alternates
     * per character recurses in java.util.regex and overflows the stack on a long token. A closing quote is
     * optional, so a value cut off by truncation is still masked, and no part crosses a line break.
     */
    private static final Pattern VALUE = Pattern.compile("(?i)"
            // A quoted value may sit on the line after its key, as pretty printers other than Jackson's
            // write it. The break is a real one, or the two characters \n once a message was made one line.
            + "((?:(?:\\r?\\n|(?:\\\\r)?\\\\n)[ \\t]*(?=\\\\?[\"']))?)"
            + "(?:(?:bearer|basic|token)[ \\t]+)?"
            + "(\\\\\"[^\\\\\"\\r\\n]*+(?:\\\\\")?"
            + "|\"[^\"\\\\\\r\\n]*+(?:\\\\[^\\r\\n][^\"\\\\\\r\\n]*+)*+\"?"
            + "|'[^'\\r\\n]*+'?"
            + "|[^\\s,;&}\\])\"']+)");

    private static final Pattern PLAIN = Pattern.compile("-?\\d{1,9}(?:\\.\\d+)?|true|false");

    private final Pattern anyKey;

    private final Set<String> keys;
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
        // Most lines hold no key word at all. Finding that out first costs far less than scanning every word.
        this.anyKey = keys.isEmpty() ? null : Pattern.compile(
                keys.stream().map(Pattern::quote).collect(java.util.stream.Collectors.joining("|")),
                Pattern.CASE_INSENSITIVE);
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
        if (anyKey != null && (result.indexOf('=') >= 0 || result.indexOf(':') >= 0)
                && anyKey.matcher(result).find()) {
            result = maskKeyValues(result);
        }
        if (cards && hasEnoughDigits(result)) {
            result = maskCards(result);
        }
        for (Pattern pattern : patterns) {
            result = pattern.matcher(result).replaceAll(Matcher.quoteReplacement(replacement));
        }
        return result;
    }

    private String maskKeyValues(String text) {
        Matcher key = KEY.matcher(text);
        Matcher value = VALUE.matcher(text);
        StringBuilder out = null;
        int copied = 0;
        int from = 0;
        // Each word is read once and tested in Java. Letting the regex look for a key inside a word
        // makes it backtrack over the rest of the word, which is quadratic on input like "tokentoken...".
        while (from <= text.length() && key.find(from)) {
            String word = key.group(1);
            if (!isSensitiveKey(word) || isTypeName(word)) {
                from = key.end(1);
                continue;
            }
            value.region(key.end(), text.length());
            if (!value.lookingAt() || value.group(2).equals("null")) {
                from = key.end();
                continue;
            }
            String found = value.group(2);
            if (PLAIN.matcher(found).matches() && !namesASecret(word)) {
                // tokenCount=5 and cookieConsent=true describe a secret, they are not one.
                from = value.end();
                continue;
            }
            String masked;
            if (found.startsWith("\\\"")) {
                masked = "\\\"" + replacement + "\\\"";
            } else if (found.startsWith("\"")) {
                masked = '"' + replacement + '"';
            } else if (found.startsWith("'")) {
                masked = '\'' + replacement + '\'';
            } else {
                masked = replacement;
            }
            if (out == null) {
                out = new StringBuilder(text.length());
            }
            out.append(text, copied, key.end()).append(value.group(1)).append(masked);
            copied = value.end();
            from = value.end();
        }
        return out == null ? text : out.append(text, copied, text.length()).toString();
    }

    /** An exception name such as {@code InvalidTokenException} is not a key. */
    /** The word is a key itself or ends in one, as {@code password} and {@code refreshToken} do. */
    private boolean namesASecret(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        return keys.stream().anyMatch(lower::endsWith);
    }

    private static boolean isTypeName(String word) {
        return TYPE_NAME.matcher(word).matches();
    }

    /** A card number has at least 14 digits. Most lines have fewer, and counting is cheaper than the regex. */
    private static boolean hasEnoughDigits(String text) {
        int digits = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9' && ++digits >= 14) {
                return true;
            }
        }
        return false;
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
