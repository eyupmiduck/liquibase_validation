package io.github.eyupmiduck.changelogvalidator.linter.model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A SQL source attached to a changeset: an inline {@code <sql>} element, a file
 * referenced by {@code <sqlFile>}, or a stored-routine body referenced by
 * {@code <createProcedure>} or {@code <createFunction>}.
 *
 * <p>The split attributes are the effective values, following Liquibase:
 * {@code splitStatements} defaults to true, {@code endDelimiter} to {@code ;}
 * (an explicitly empty delimiter is preserved), and {@code stripComments} to
 * false. Liquibase's {@code stripComments} default is change-type dependent;
 * this model applies the {@code sql}/{@code sqlFile} default, so a
 * {@code createProcedure}/{@code createFunction} routine body inherits it too
 * (see ADR 0003).
 *
 * @param kind            the source kind
 * @param path            the resolved file path for file kinds, otherwise null
 * @param text            the inline SQL for inline sources, otherwise null
 * @param splitStatements whether Liquibase splits the SQL into statements
 * @param endDelimiter    the statement delimiter
 * @param stripComments   whether Liquibase removes comments
 * @param dbms            the source-level dbms restriction, or null
 */
public record SqlSource(Kind kind, Path path, String text, boolean splitStatements, String endDelimiter,
                        boolean stripComments, String dbms) {

    /**
     * Validates the source's required components and its kind-specific shape:
     * exactly one of {@code path} or {@code text} is set, matching the kind.
     */
    public SqlSource {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(endDelimiter, "endDelimiter");
        switch (kind) {
            case INLINE_SQL -> {
                Objects.requireNonNull(text, "text for INLINE_SQL");
                if (path != null) {
                    throw new IllegalArgumentException("INLINE_SQL must not set path");
                }
            }
            case SQL_FILE -> {
                Objects.requireNonNull(path, "path for SQL_FILE");
                if (text != null) {
                    throw new IllegalArgumentException("SQL_FILE must not set text");
                }
            }
            case ROUTINE_BODY -> {
                if ((path == null) == (text == null)) {
                    throw new IllegalArgumentException("ROUTINE_BODY must set exactly one of path or text");
                }
            }
        }
    }

    /**
     * Returns whether this source is inline SQL (no file to read). A routine body
     * given as element text is inline too.
     *
     * @return {@code true} when {@code path} is null
     */
    public boolean isInline() {
        return path == null;
    }

    /**
     * The kind of SQL source.
     */
    public enum Kind {

        /**
         * Inline SQL from a {@code <sql>} element.
         */
        INLINE_SQL,

        /**
         * SQL loaded from a file referenced by {@code <sqlFile>}.
         */
        SQL_FILE,

        /**
         * A routine body referenced by {@code <createProcedure>} or {@code <createFunction>}.
         */
        ROUTINE_BODY
    }
}
