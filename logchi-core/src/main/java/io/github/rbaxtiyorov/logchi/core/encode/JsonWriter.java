package io.github.rbaxtiyorov.logchi.core.encode;

/** Writes one flat JSON object. Hand-written so core depends on neither Jackson 2 nor Jackson 3. */
final class JsonWriter {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final StringBuilder sb = new StringBuilder(512).append('{');
    private boolean first = true;

    JsonWriter field(String key, String value) {
        name(key);
        if (value == null) {
            sb.append("null");
        } else {
            string(value);
        }
        return this;
    }

    JsonWriter field(String key, long value) {
        name(key);
        sb.append(value);
        return this;
    }

    JsonWriter field(String key, boolean value) {
        name(key);
        sb.append(value);
        return this;
    }

    /** Writes a number, or a string for NaN and infinities, which JSON cannot represent. */
    JsonWriter field(String key, Number value) {
        double d = value.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return field(key, value.toString());
        }
        name(key);
        sb.append(value);
        return this;
    }

    String finish() {
        return sb.append("}\n").toString();
    }

    private void name(String key) {
        if (first) {
            first = false;
        } else {
            sb.append(',');
        }
        string(key);
        sb.append(':');
    }

    private void string(String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u00").append(HEX[c >> 4]).append(HEX[c & 0xF]);
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
