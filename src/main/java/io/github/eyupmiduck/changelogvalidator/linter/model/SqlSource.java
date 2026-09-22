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
 * false (Liquibase's default is change-type dependent; this model uses the
 * {@code sql}/{@code sqlFile} default).
 *
 * @param kind            the source kind
 * @param path            the resolved file path for file kinds, otherwise null
 * @param text            the inline SQL for {@link Kind#INLINE_SQL}, otherwise null
 * @param splitStatements whether Liquibase splits the SQL into statements
 * @param endDelimiter    the statement delimiter
 * @param stripComments   whether Liquibase removes comments
 * @param dbms            the source-level dbms restriction, or null
 */
public record SqlSource(Kind kind, Path path, String text, boolean splitStatements, String endDelimiter,
                        boolean stripComments, String dbms) {

    /**
     * Validates the source's required components.
     */
    public SqlSource {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(endDelimiter, "endDelimiter");
    }

    /**
     * Returns whether this source is inline SQL.
     *
     * @return {@code true} for {@link Kind#INLINE_SQL}
     */
    public boolean isInline() {
        return kind == Kind.INLINE_SQL;
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
