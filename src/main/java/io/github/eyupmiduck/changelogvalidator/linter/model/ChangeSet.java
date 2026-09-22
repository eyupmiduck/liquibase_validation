package io.github.eyupmiduck.changelogvalidator.linter.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A Liquibase changeset with the attributes and SQL sources the linter's rules
 * need.
 *
 * <p>Only SQL-bearing changes are exposed: inline {@code <sql>}, {@code <sqlFile>}
 * and stored-routine bodies. Changesets that use structured change types (for
 * example {@code <addColumn>}) are still returned, with empty source lists.
 *
 * <p>Rollback SQL is resolved separately so rules can evaluate forward and
 * rollback contexts. A rollback that references another changeset
 * ({@code <rollback changeSetId="..."/>}) sets {@link #rollbackDefined()} but
 * has no {@link #rollbackSources()}.
 *
 * @param id               the changeset id
 * @param author           the changeset author, or an empty string
 * @param changelogFile    the changelog file that declares the changeset
 * @param runInTransaction whether Liquibase wraps the changeset in a transaction
 * @param runOnChange      whether Liquibase re-runs the changeset when it changes
 * @param dbms             the changeset dbms restriction, or null
 * @param context          the changeset context restriction, or null
 * @param labels           the changeset label restriction, or null
 * @param sqlSources        the forward SQL sources
 * @param rollbackDefined   whether the changeset declares a rollback
 * @param rollbackSources   the rollback SQL sources
 * @param normalisation     the properties and {@code <modifySql>} transformations
 *                          that turn the raw SQL into what Liquibase runs
 */
public record ChangeSet(String id, String author, Path changelogFile, boolean runInTransaction, boolean runOnChange,
                        String dbms, String context, String labels, List<SqlSource> sqlSources,
                        boolean rollbackDefined, List<SqlSource> rollbackSources, Normalisation normalisation) {

    /**
     * Validates the changeset's required components and copies its lists.
     */
    public ChangeSet {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(changelogFile, "changelogFile");
        Objects.requireNonNull(normalisation, "normalisation");
        sqlSources = List.copyOf(sqlSources);
        rollbackSources = List.copyOf(rollbackSources);
    }

    /**
     * Creates a changeset with no SQL normalisation.
     *
     * @param id               the changeset id
     * @param author           the changeset author, or an empty string
     * @param changelogFile    the changelog file that declares the changeset
     * @param runInTransaction whether Liquibase wraps the changeset in a transaction
     * @param runOnChange      whether Liquibase re-runs the changeset when it changes
     * @param dbms             the changeset dbms restriction, or null
     * @param context          the changeset context restriction, or null
     * @param labels           the changeset label restriction, or null
     * @param sqlSources       the forward SQL sources
     * @param rollbackDefined  whether the changeset declares a rollback
     * @param rollbackSources  the rollback SQL sources
     */
    public ChangeSet(String id, String author, Path changelogFile, boolean runInTransaction, boolean runOnChange,
                     String dbms, String context, String labels, List<SqlSource> sqlSources,
                     boolean rollbackDefined, List<SqlSource> rollbackSources) {
        this(id, author, changelogFile, runInTransaction, runOnChange, dbms, context, labels, sqlSources,
                rollbackDefined, rollbackSources, Normalisation.none());
    }
}
