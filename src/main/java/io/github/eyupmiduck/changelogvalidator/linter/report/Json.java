package io.github.eyupmiduck.changelogvalidator.linter.report;

/**
 * Minimal JSON string quoting for the reporters. Keeping this local avoids a
 * JSON dependency for a small, fixed output shape.
 */
final class Json {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Json() {
    }

    /**
     * Returns {@code value} as a JSON string literal, or {@code null}.
     *
     * @param value the value, possibly null
     * @return the JSON literal
     */
    static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder json = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                default -> {
                    if (character < 0x20 || (Character.isSurrogate(character) && !isPairedSurrogate(value, i))) {
                        appendUnicode(json, character);
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        return json.append('"').toString();
    }

    private static boolean isPairedSurrogate(String value, int index) {
        char character = value.charAt(index);
        if (Character.isHighSurrogate(character)) {
            return index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1));
        }
        return index > 0 && Character.isHighSurrogate(value.charAt(index - 1));
    }

    private static void appendUnicode(StringBuilder json, char character) {
        json.append("\\u")
                .append(HEX[(character >> 12) & 0xF])
                .append(HEX[(character >> 8) & 0xF])
                .append(HEX[(character >> 4) & 0xF])
                .append(HEX[character & 0xF]);
    }
}
