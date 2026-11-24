package io.github.eyupmiduck.changelogvalidator.linter.model;

import java.util.Locale;

/**
 * Parses the boolean spellings Liquibase accepts, so an attribute such as
 * {@code runInTransaction="yes"} or {@code splitStatements="1"} is not silently
 * read as false.
 *
 * <p>{@link Boolean#parseBoolean(String)} recognises only {@code true}, while
 * Liquibase's {@code BooleanParser} also accepts {@code t}, {@code yes},
 * {@code y}, {@code on} and {@code 1} (and the corresponding false spellings).
 */
final class LiquibaseBoolean {

    private LiquibaseBoolean() {
    }

    /**
     * Parses a Liquibase boolean value.
     *
     * @param value the attribute value, or null
     * @return the parsed value, or null when the value is missing or not a
     * recognised boolean spelling
     */
    static Boolean parse(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true", "t", "yes", "y", "on", "1" -> Boolean.TRUE;
            case "false", "f", "no", "n", "off", "0" -> Boolean.FALSE;
            default -> null;
        };
    }
}
