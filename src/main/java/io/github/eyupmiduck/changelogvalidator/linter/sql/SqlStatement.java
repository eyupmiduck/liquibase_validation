package io.github.eyupmiduck.changelogvalidator.linter.sql;

import java.util.Objects;

/**
 * A single SQL statement produced by {@link SqlStatementSplitter}.
 *
 * <p>The text never includes the terminating delimiter. Offsets are zero-based
 * indices into the source SQL; {@code endOffset} is exclusive.
 *
 * @param text        the statement text
 * @param startOffset the inclusive start offset in the source SQL
 * @param endOffset   the exclusive end offset in the source SQL
 */
public record SqlStatement(String text, int startOffset, int endOffset) {

    /**
     * Validates the statement's required components and its offset range.
     */
    public SqlStatement {
        Objects.requireNonNull(text, "text");
        if (startOffset < 0) {
            throw new IllegalArgumentException("startOffset must be non-negative, was " + startOffset);
        }
        if (endOffset < startOffset) {
            throw new IllegalArgumentException(
                    "endOffset (" + endOffset + ") must not precede startOffset (" + startOffset + ")");
        }
    }
}
