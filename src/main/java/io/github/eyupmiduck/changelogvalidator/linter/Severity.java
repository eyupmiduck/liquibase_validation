package io.github.eyupmiduck.changelogvalidator.linter;

import java.util.Locale;
import java.util.Objects;

/**
 * The severity of a linter rule or finding, ordered from least to most severe.
 */
public enum Severity {

    /**
     * Informational; never fails the run.
     */
    INFO(0),

    /**
     * A likely problem; fails only when the fault threshold is warning.
     */
    WARNING(1),

    /**
     * A definite problem; fails by default.
     */
    ERROR(2);

    private final int level;

    Severity(int level) {
        this.level = level;
    }

    /**
     * Parses a severity name, ignoring case.
     *
     * @param value the severity name
     * @return the severity
     * @throws IllegalArgumentException when the value is null or not a known severity
     */
    public static Severity from(String value) {
        if (value == null) {
            throw new IllegalArgumentException("unknown severity: null");
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown severity: " + value, e);
        }
    }

    /**
     * Returns whether this severity is at least {@code other}. The comparison uses
     * an explicit level, so it does not depend on the constants' declaration order.
     *
     * @param other the threshold severity
     * @return {@code true} when this severity is at least as severe
     */
    public boolean atLeast(Severity other) {
        Objects.requireNonNull(other, "other");
        return level >= other.level;
    }
}
