package io.github.eyupmiduck.changelogvalidator.linter;

import java.util.Locale;

/**
 * The severity of a linter rule or finding, ordered from least to most severe.
 */
public enum Severity {

    /**
     * Informational; never fails the run.
     */
    INFO,

    /**
     * A likely problem; fails only when the fault threshold is warning.
     */
    WARNING,

    /**
     * A definite problem; fails by default.
     */
    ERROR;

    /**
     * Parses a severity name, ignoring case.
     *
     * @param value the severity name
     * @return the severity
     * @throws IllegalArgumentException when the value is not a known severity
     */
    public static Severity from(String value) {
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown severity: " + value, e);
        }
    }

    /**
     * Returns whether this severity is at least {@code other}.
     *
     * @param other the threshold severity
     * @return {@code true} when this severity is at least as severe
     */
    public boolean atLeast(Severity other) {
        return ordinal() >= other.ordinal();
    }
}
