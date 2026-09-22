package io.github.eyupmiduck.changelogvalidator.linter.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link ChangelogModel} against a changelog graph laid out like
 * {@code ddl_utils}: a master that includes an XML changes file and a routines
 * file, with {@code <sqlFile>}, inline {@code <sql>},
 * {@code <createProcedure>}/{@code <createFunction>} bodies and rollbacks.
 */
class ChangelogModelTest {

    @TempDir
    Path changelogRoot;

    private Path master;

    private static ChangeSet byId(List<ChangeSet> changesets, String id) {
        return changesets.stream()
                .filter(changeset -> changeset.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no changeset " + id));
    }

    private static String changelog(String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog \
                https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                %s
                </databaseChangeLog>
                """.formatted(body);
    }

    @BeforeEach
    void createChangelog() throws IOException {
        write("db.changelog-master.xml", changelog("""
                <include file="changes.xml" relativeToChangelogFile="true"/>
                <include file="functions.xml" relativeToChangelogFile="true"/>
                """));
        write("changes.xml", changelog("""
                <changeSet id="001-create" author="a">
                    <sqlFile path="sql_changes/001-create.sql" relativeToChangelogFile="true"/>
                    <rollback>
                        <sqlFile path="rollback/001-create-rollback.sql" relativeToChangelogFile="true"/>
                    </rollback>
                </changeSet>
                <changeSet id="002-index" author="b" runInTransaction="false" dbms="postgresql"
                           context="dev" labels="core">
                    <sql splitStatements="true" endDelimiter=";" stripComments="true">CREATE INDEX CONCURRENTLY idx ON t (c);</sql>
                    <rollback changeSetId="001-create" changeSetAuthor="a"/>
                </changeSet>
                <changeSet id="003-defaults" author="a">
                    <sql>SELECT 1;</sql>
                </changeSet>
                <changeSet id="004-structured" author="a">
                    <addColumn tableName="t">
                        <column name="c" type="int"/>
                    </addColumn>
                </changeSet>
                <changeSet id="005-root-relative" author="a">
                    <sqlFile path="sql_changes/005-root.sql"/>
                </changeSet>
                <changeSet id="006-empty-delimiter" author="a">
                    <sql endDelimiter="">SELECT 1</sql>
                </changeSet>
                <changeSet id="007-blank-path" author="a">
                    <sqlFile path=""/>
                </changeSet>
                """));
        write("functions.xml", changelog("""
                <changeSet id="function-ddl_utils.foo" author="a" runOnChange="true">
                    <createProcedure path="functions/ddl_utils/foo.sql" relativeToChangelogFile="true"/>
                    <rollback>
                        <sqlFile path="functions-rollback/ddl_utils/foo-rollback.sql" relativeToChangelogFile="true"/>
                    </rollback>
                </changeSet>
                <changeSet id="function-ddl_utils.bar" author="a" runOnChange="true">
                    <createFunction path="functions/ddl_utils/bar.sql" relativeToChangelogFile="true"/>
                </changeSet>
                """));
        write("sql_changes/001-create.sql", "CREATE SCHEMA ddl_utils;");
        write("rollback/001-create-rollback.sql", "DROP SCHEMA ddl_utils;");
        write("sql_changes/005-root.sql", "SELECT 1;");
        write("functions/ddl_utils/foo.sql", "CREATE FUNCTION foo() RETURNS void AS $$ BEGIN END; $$ LANGUAGE plpgsql;");
        write("functions/ddl_utils/bar.sql", "CREATE FUNCTION bar() RETURNS void AS $$ BEGIN END; $$ LANGUAGE plpgsql;");
        master = changelogRoot.resolve("db.changelog-master.xml");
    }

    /**
     * The model returns every changeset in the graph, including structured
     * changesets that carry no SQL source.
     */
    @Test
    void readsEveryChangeSet() throws IOException {
        List<ChangeSet> changesets = ChangelogModel.changesets(changelogRoot, master);

        assertEquals(9, changesets.size());
        assertEquals("001-create", byId(changesets, "001-create").id());
        assertEquals("function-ddl_utils.bar", byId(changesets, "function-ddl_utils.bar").id());
    }

    /**
     * A {@code <sqlFile>} source and its rollback resolve to absolute paths.
     */
    @Test
    void resolvesSqlFileAndRollback() throws IOException {
        ChangeSet create = byId(ChangelogModel.changesets(changelogRoot, master), "001-create");

        assertTrue(create.runInTransaction());
        assertFalse(create.runOnChange());
        assertNull(create.dbms());
        assertEquals(1, create.sqlSources().size());
        SqlSource forward = create.sqlSources().get(0);
        assertEquals(SqlSource.Kind.SQL_FILE, forward.kind());
        assertEquals(changelogRoot.resolve("sql_changes/001-create.sql").normalize(), forward.path());
        assertTrue(create.rollbackDefined());
        assertEquals(1, create.rollbackSources().size());
        assertEquals(changelogRoot.resolve("rollback/001-create-rollback.sql").normalize(),
                create.rollbackSources().get(0).path());
    }

    /**
     * Changeset and inline {@code <sql>} attributes are exposed, and a rollback
     * that references another changeset has no sources.
     */
    @Test
    void readsInlineSqlAttributesAndReferenceRollback() throws IOException {
        ChangeSet index = byId(ChangelogModel.changesets(changelogRoot, master), "002-index");

        assertFalse(index.runInTransaction());
        assertEquals("postgresql", index.dbms());
        assertEquals("dev", index.context());
        assertEquals("core", index.labels());
        SqlSource inline = index.sqlSources().get(0);
        assertTrue(inline.isInline());
        assertNull(inline.path());
        assertTrue(inline.text().contains("CREATE INDEX CONCURRENTLY"));
        assertTrue(inline.splitStatements());
        assertEquals(";", inline.endDelimiter());
        assertTrue(inline.stripComments());
        assertTrue(index.rollbackDefined());
        assertTrue(index.rollbackSources().isEmpty());
    }

    /**
     * A plain {@code <sql>} uses Liquibase's defaults and a changeset without a
     * rollback reports none.
     */
    @Test
    void appliesLiquibaseDefaults() throws IOException {
        ChangeSet defaults = byId(ChangelogModel.changesets(changelogRoot, master), "003-defaults");

        SqlSource inline = defaults.sqlSources().get(0);
        assertTrue(inline.splitStatements());
        assertEquals(";", inline.endDelimiter());
        assertFalse(inline.stripComments());
        assertFalse(defaults.rollbackDefined());
        assertTrue(defaults.rollbackSources().isEmpty());
    }

    /**
     * A changeset that only uses a structured change type has no SQL sources.
     */
    @Test
    void handlesStructuredChanges() throws IOException {
        ChangeSet structured = byId(ChangelogModel.changesets(changelogRoot, master), "004-structured");

        assertTrue(structured.sqlSources().isEmpty());
        assertFalse(structured.rollbackDefined());
    }

    /**
     * Without {@code relativeToChangelogFile}, a path resolves against the
     * changelog root, an explicit empty delimiter is preserved, and a blank path
     * produces no source.
     */
    @Test
    void resolvesPathsAndDelimiterEdges() throws IOException {
        List<ChangeSet> changesets = ChangelogModel.changesets(changelogRoot, master);

        assertEquals(changelogRoot.resolve("sql_changes/005-root.sql").normalize(),
                byId(changesets, "005-root-relative").sqlSources().get(0).path());
        assertEquals("", byId(changesets, "006-empty-delimiter").sqlSources().get(0).endDelimiter());
        assertTrue(byId(changesets, "007-blank-path").sqlSources().isEmpty());
    }

    /**
     * A {@code <createProcedure>} body is a {@link SqlSource.Kind#ROUTINE_BODY}
     * and a {@code <createFunction>} body is resolved the same way.
     */
    @Test
    void readsRoutineBodies() throws IOException {
        List<ChangeSet> changesets = ChangelogModel.changesets(changelogRoot, master);

        ChangeSet procedure = byId(changesets, "function-ddl_utils.foo");
        assertTrue(procedure.runOnChange());
        assertEquals(SqlSource.Kind.ROUTINE_BODY, procedure.sqlSources().get(0).kind());
        assertEquals(changelogRoot.resolve("functions/ddl_utils/foo.sql").normalize(),
                procedure.sqlSources().get(0).path());
        assertTrue(procedure.rollbackDefined());
        assertEquals(changelogRoot.resolve("functions-rollback/ddl_utils/foo-rollback.sql").normalize(),
                procedure.rollbackSources().get(0).path());

        assertEquals(SqlSource.Kind.ROUTINE_BODY,
                byId(changesets, "function-ddl_utils.bar").sqlSources().get(0).kind());
    }

    /**
     * The model and source records reject missing required components.
     */
    @Test
    void rejectsMissingComponents() {
        assertThrows(NullPointerException.class,
                () -> new SqlSource(null, null, "x", true, ";", false, null));
        assertThrows(NullPointerException.class,
                () -> new SqlSource(SqlSource.Kind.INLINE_SQL, null, "x", true, null, false, null));
        assertThrows(NullPointerException.class,
                () -> new ChangeSet(null, "a", master, true, false, null, null, null, List.of(), false, List.of()));
        assertThrows(NullPointerException.class,
                () -> new ChangeSet("id", null, master, true, false, null, null, null, List.of(), false, List.of()));
        assertThrows(NullPointerException.class,
                () -> new ChangeSet("id", "a", null, true, false, null, null, null, List.of(), false, List.of()));
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = changelogRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
