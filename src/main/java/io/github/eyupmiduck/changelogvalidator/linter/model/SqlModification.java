package io.github.eyupmiduck.changelogvalidator.linter.model;

import java.util.Objects;

/**
 * One {@code <modifySql>} child: a transformation Liquibase applies to the SQL
 * of a changeset before running it.
 *
 * <p>The {@code dbms} filter is kept so a transformation for another database is
 * not applied; the linter targets PostgreSQL, so a modification applies when
 * {@code dbms} is unset or contains {@code postgresql}. The {@code context} and
 * {@code labels} filters are parsed by the model but not evaluated, because the
 * linter has no run context (see ADR 0003).
 *
 * @param kind            the transformation
 * @param value           the text to append or prepend, or the text to replace
 * @param with            the replacement text for {@link Kind#REPLACE} and
 *                        {@link Kind#REGEXP_REPLACE}, otherwise null
 * @param applyToRollback whether the transformation also applies to rollback SQL
 * @param dbms            the comma-separated dbms filter, or null
 */
public record SqlModification(Kind kind, String value, String with, boolean applyToRollback, String dbms) {

    /**
     * Validates the modification's required components.
     */
    public SqlModification {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        if ((kind == Kind.REPLACE || kind == Kind.REGEXP_REPLACE) && with == null) {
            throw new NullPointerException("with");
        }
    }

    /**
     * Returns whether this modification applies to PostgreSQL.
     *
     * @return {@code true} when {@code dbms} is unset or lists {@code postgresql}
     */
    public boolean appliesToPostgres() {
        if (dbms == null || dbms.isBlank()) {
            return true;
        }
        for (String candidate : dbms.split(",")) {
            if (candidate.strip().equalsIgnoreCase("postgresql")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Applies the transformation to {@code sql}.
     *
     * @param sql the SQL to transform
     * @return the transformed SQL
     */
    public String apply(String sql) {
        return switch (kind) {
            case APPEND -> sql + value;
            case PREPEND -> value + sql;
            case REPLACE -> sql.replace(value, with);
            case REGEXP_REPLACE -> sql.replaceAll(value, with);
            case APPEND_IF_NOT_PRESENT -> sql.endsWith(value) ? sql : sql + value;
        };
    }

    /**
     * The transformation kind, matching the {@code <modifySql>} child elements.
     */
    public enum Kind {

        /** {@code <append value="..."/>}. */
        APPEND,

        /** {@code <prepend value="..."/>}. */
        PREPEND,

        /** {@code <replace replace="..." with="..."/>}. */
        REPLACE,

        /** {@code <regExpReplace replace="..." with="..."/>}. */
        REGEXP_REPLACE,

        /** {@code <appendSqlIfNotPresent value="..."/>}. */
        APPEND_IF_NOT_PRESENT;

        /**
         * Returns the kind for a {@code <modifySql>} child element name.
         *
         * @param elementName the child element name
         * @return the kind, or null when the element is not a modification
         */
        public static Kind from(String elementName) {
            return switch (elementName) {
                case "append" -> APPEND;
                case "prepend" -> PREPEND;
                case "replace" -> REPLACE;
                case "regExpReplace" -> REGEXP_REPLACE;
                case "appendSqlIfNotPresent" -> APPEND_IF_NOT_PRESENT;
                default -> null;
            };
        }
    }
}
