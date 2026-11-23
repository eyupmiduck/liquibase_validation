package io.github.eyupmiduck.changelogvalidator.linter.model;

import io.github.eyupmiduck.changelogvalidator.ChangelogTestSupport;

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


    @BeforeEach
    void createChangelog() throws IOException {
        write("db.changelog-master.xml", ChangelogTestSupport.changelog("""
                <include file="changes.xml" relativeToChangelogFile="true"/>
                <include file="functions.xml" relativeToChangelogFile="true"/>
                """));
        write("changes.xml", ChangelogTestSupport.changelog("""
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
                """));
        write("functions.xml", ChangelogTestSupport.changelog("""
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

        assertEquals(8, changesets.size());
        assertEquals("001-create", ChangelogTestSupport.byId(changesets, "001-create").id());
        assertEquals("function-ddl_utils.bar", ChangelogTestSupport.byId(changesets, "function-ddl_utils.bar").id());
    }

    /**
     * A prefixed Liquibase namespace is resolved by local element name rather
     * than silently producing zero changesets.
     */
    @Test
    void readsPrefixedNamespaceElements() throws IOException {
        String prefix = """
                <lb:databaseChangeLog xmlns:lb="http://www.liquibase.org/xml/ns/dbchangelog">
                %s
                </lb:databaseChangeLog>
                """;
        write("db.changelog-master.xml", prefix.formatted("""
                <lb:include file="changes.xml" relativeToChangelogFile="true"/>
                """));
        write("changes.xml", prefix.formatted("""
                <lb:changeSet id="001-prefixed" author="a">
                    <lb:sql>SELECT 1;</lb:sql>
                </lb:changeSet>
                """));

        List<ChangeSet> changesets = ChangelogModel.changesets(
                changelogRoot, changelogRoot.resolve("db.changelog-master.xml"));

        assertEquals(1, changesets.size());
        assertEquals("001-prefixed", changesets.get(0).id());
        assertEquals(1, changesets.get(0).sqlSources().size());
    }

    /**
     * A diamond include is visited through both branches, matching Liquibase,
     * rather than being dropped after the first encounter.
     */
    @Test
    void visitsARepeatedIncludeInEveryBranch() throws IOException {
        write("db.changelog-master.xml", ChangelogTestSupport.changelog("""
                <include file="a.xml" relativeToChangelogFile="true"/>
                <include file="b.xml" relativeToChangelogFile="true"/>
                """));
        write("a.xml", ChangelogTestSupport.changelog("""
                <include file="common.xml" relativeToChangelogFile="true"/>
                """));
        write("b.xml", ChangelogTestSupport.changelog("""
                <include file="common.xml" relativeToChangelogFile="true"/>
                """));
        write("common.xml", ChangelogTestSupport.changelog("""
                <changeSet id="shared" author="a">
                    <sql>SELECT 1;</sql>
                </changeSet>
                """));

        List<ChangeSet> changesets = ChangelogModel.changesets(
                changelogRoot, changelogRoot.resolve("db.changelog-master.xml"));

        assertEquals(2, changesets.size());
        assertTrue(changesets.stream().allMatch(changeSet -> changeSet.id().equals("shared")));
    }

    /**
     * A real include cycle is rejected instead of silently truncating the graph.
     */
    @Test
    void rejectsACircularInclude() throws IOException {
        write("db.changelog-master.xml", ChangelogTestSupport.changelog("""
                <include file="a.xml" relativeToChangelogFile="true"/>
                """));
        write("a.xml", ChangelogTestSupport.changelog("""
                <include file="db.changelog-master.xml" relativeToChangelogFile="true"/>
                """));

        IOException failure = assertThrows(IOException.class, () -> ChangelogModel.changesets(
                changelogRoot, changelogRoot.resolve("db.changelog-master.xml")));
        assertTrue(failure.getMessage().contains("circular"));
    }

    /**
     * A changeset and its inline/structured source carry the line of their XML
     * element.
     */
    @Test
    void recordsElementLines() throws IOException {
        write("changes.xml", ChangelogTestSupport.changelog("""
                <changeSet id="001-index" author="a">
                    <createIndex indexName="idx" tableName="t">
                        <column name="c"/>
                    </createIndex>
                </changeSet>
                """));

        ChangeSet changeSet = ChangelogTestSupport.byId(
                ChangelogModel.changesets(changelogRoot, master), "001-index");

        assertEquals(lineOf("changes.xml", "<changeSet"), changeSet.line());
        assertEquals(lineOf("changes.xml", "<createIndex"), changeSet.sqlSources().get(0).line());
    }

    private int lineOf(String file, String marker) throws IOException {
        List<String> lines = Files.readAllLines(changelogRoot.resolve(file));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(marker)) {
                return i + 1;
            }
        }
        throw new AssertionError("no " + marker + " in " + file);
    }

    /**
     * A {@code <sqlFile>} source and its rollback resolve to absolute paths.
     */
    @Test
    void resolvesSqlFileAndRollback() throws IOException {
        ChangeSet create = ChangelogTestSupport.byId(ChangelogModel.changesets(changelogRoot, master), "001-create");

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
        ChangeSet index = ChangelogTestSupport.byId(ChangelogModel.changesets(changelogRoot, master), "002-index");

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
        ChangeSet defaults = ChangelogTestSupport.byId(ChangelogModel.changesets(changelogRoot, master), "003-defaults");

        SqlSource inline = defaults.sqlSources().get(0);
        assertTrue(inline.splitStatements());
        assertEquals(";", inline.endDelimiter());
        assertFalse(inline.stripComments());
        assertFalse(defaults.rollbackDefined());
        assertTrue(defaults.rollbackSources().isEmpty());
    }

    /**
     * A structured addColumn is rendered into the ALTER TABLE the rules inspect.
     */
    @Test
    void rendersStructuredChanges() throws IOException {
        ChangeSet structured = ChangelogTestSupport.byId(ChangelogModel.changesets(changelogRoot, master), "004-structured");

        assertEquals(1, structured.sqlSources().size());
        assertEquals("ALTER TABLE t ADD COLUMN c int;", structured.sqlSources().getFirst().text());
        assertFalse(structured.rollbackDefined());
    }

    /**
     * Without {@code relativeToChangelogFile}, a path resolves against the
     * changelog root and an explicit empty delimiter is preserved.
     */
    @Test
    void resolvesPathsAndDelimiterEdges() throws IOException {
        List<ChangeSet> changesets = ChangelogModel.changesets(changelogRoot, master);

        assertEquals(changelogRoot.resolve("sql_changes/005-root.sql").normalize(),
                ChangelogTestSupport.byId(changesets, "005-root-relative").sqlSources().get(0).path());
        assertEquals("", ChangelogTestSupport.byId(changesets, "006-empty-delimiter").sqlSources().get(0).endDelimiter());
    }

    /**
     * A {@code <createProcedure>} body is a {@link SqlSource.Kind#ROUTINE_BODY}
     * and a {@code <createFunction>} body is resolved the same way.
     */
    @Test
    void readsRoutineBodies() throws IOException {
        List<ChangeSet> changesets = ChangelogModel.changesets(changelogRoot, master);

        ChangeSet procedure = ChangelogTestSupport.byId(changesets, "function-ddl_utils.foo");
        assertTrue(procedure.runOnChange());
        assertEquals(SqlSource.Kind.ROUTINE_BODY, procedure.sqlSources().get(0).kind());
        assertEquals(changelogRoot.resolve("functions/ddl_utils/foo.sql").normalize(),
                procedure.sqlSources().get(0).path());
        assertTrue(procedure.rollbackDefined());
        assertEquals(changelogRoot.resolve("functions-rollback/ddl_utils/foo-rollback.sql").normalize(),
                procedure.rollbackSources().get(0).path());

        assertEquals(SqlSource.Kind.ROUTINE_BODY,
                ChangelogTestSupport.byId(changesets, "function-ddl_utils.bar").sqlSources().get(0).kind());
    }

    /**
     * A DOCTYPE that declares an external entity is rejected instead of being
     * resolved, so a crafted changelog cannot be used to read a local file
     * through the linter.
     */
    @Test
    void rejectsDoctypeWithExternalEntity() throws IOException {
        Path secret = changelogRoot.resolve("secret.txt");
        Files.writeString(secret, "TOP SECRET");
        Path doctypeMaster = changelogRoot.resolve("doctype.xml");
        Files.writeString(doctypeMaster, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE databaseChangeLog [
                  <!ENTITY xxe SYSTEM "%s">
                ]>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                  <changeSet id="001-probe" author="test">
                    <sql>&xxe;</sql>
                  </changeSet>
                </databaseChangeLog>
                """.formatted(secret.toUri()));

        IOException failure = assertThrows(IOException.class,
                () -> ChangelogModel.changesets(changelogRoot, doctypeMaster));
        assertTrue(failure.getMessage().contains("doctype.xml"),
                () -> "the failure should name the changelog: " + failure.getMessage());
    }

    /**
     * {@code <includeAll>} includes XML changelogs in alphabetical path order,
     * recursing into subdirectories.
     */
    @Test
    void includesAllXmlChangelogsInAlphabeticalOrder() throws IOException {
        write("db.changelog-master.xml", ChangelogTestSupport.changelog("""
                <includeAll path="included/" relativeToChangelogFile="true"/>
                """));
        write("included/002-second.xml", ChangelogTestSupport.changelog("""
                <changeSet id="002-second" author="a"><sql>SELECT 2;</sql></changeSet>
                """));
        write("included/001-first.xml", ChangelogTestSupport.changelog("""
                <changeSet id="001-first" author="a"><sql>SELECT 1;</sql></changeSet>
                """));
        write("included/sub/003-third.xml", ChangelogTestSupport.changelog("""
                <changeSet id="003-third" author="a"><sql>SELECT 3;</sql></changeSet>
                """));

        List<ChangeSet> changesets = ChangelogModel.changesets(
                changelogRoot, changelogRoot.resolve("db.changelog-master.xml"));

        assertEquals(List.of("001-first", "002-second", "003-third"),
                changesets.stream().map(ChangeSet::id).toList());
    }

    /**
     * An unformatted SQL file found by {@code <includeAll>} is one raw change,
     * matching Liquibase.
     */
    @Test
    void includesAllUnformattedSqlFilesAsIndividualChanges() throws IOException {
        write("db.changelog-master.xml", ChangelogTestSupport.changelog("""
                <includeAll path="included/" relativeToChangelogFile="true"/>
                """));
        write("included/001-raw.sql", "SELECT 1;\n");

        List<ChangeSet> changesets = ChangelogModel.changesets(
                changelogRoot, changelogRoot.resolve("db.changelog-master.xml"));

        assertEquals(1, changesets.size());
        ChangeSet changeSet = changesets.getFirst();
        assertEquals("included/001-raw.sql", changeSet.id());
        assertEquals("includeAll", changeSet.author());
        assertEquals(SqlSource.Kind.SQL_FILE, changeSet.sqlSources().getFirst().kind());
        assertEquals(changelogRoot.resolve("included/001-raw.sql").toAbsolutePath().normalize(),
                changeSet.sqlSources().getFirst().path());
    }

    /**
     * A formatted SQL changelog found by {@code <includeAll>} is parsed by
     * content, matching Liquibase's parser selection.
     */
    @Test
    void includesFormattedSqlChangelogThroughIncludeAll() throws IOException {
        write("db.changelog-master.xml", ChangelogTestSupport.changelog("""
                <includeAll path="included/" relativeToChangelogFile="true"/>
                """));
        write("included/changelog.sql", """
                --liquibase formatted sql
                --changeset alice:001-create
                CREATE TABLE t (id int);
                --changeset alice:002-index
                CREATE INDEX idx ON t (id);
                """);

        List<ChangeSet> changesets = ChangelogModel.changesets(
                changelogRoot, changelogRoot.resolve("db.changelog-master.xml"));

        assertEquals(List.of("001-create", "002-index"),
                changesets.stream().map(ChangeSet::id).toList());
        assertEquals("alice", changesets.getFirst().author());
        assertTrue(changesets.getFirst().sqlSources().getFirst().text().contains("CREATE TABLE t"));
    }

    /**
     * {@code endsWithFilter} and {@code maxDepth} restrict the included files.
     */
    @Test
    void honoursIncludeAllFilterAndDepth() throws IOException {
        write("db.changelog-master.xml", ChangelogTestSupport.changelog("""
                <includeAll path="included/" relativeToChangelogFile="true"
                            endsWithFilter=".xml" minDepth="1" maxDepth="1"/>
                """));
        write("included/001-first.xml", ChangelogTestSupport.changelog("""
                <changeSet id="001-first" author="a"><sql>SELECT 1;</sql></changeSet>
                """));
        write("included/002-skip.txt", "not a changelog\n");
        write("included/sub/003-nested.xml", ChangelogTestSupport.changelog("""
                <changeSet id="003-nested" author="a"><sql>SELECT 3;</sql></changeSet>
                """));

        List<ChangeSet> changesets = ChangelogModel.changesets(
                changelogRoot, changelogRoot.resolve("db.changelog-master.xml"));

        assertEquals(List.of("001-first"), changesets.stream().map(ChangeSet::id).toList());
    }

    /**
     * A missing includeAll directory fails when it is required.
     */
    @Test
    void rejectsMissingIncludeAllDirectoryWhenRequired() throws IOException {
        write("db.changelog-master.xml", ChangelogTestSupport.changelog("""
                <includeAll path="missing/" relativeToChangelogFile="true"/>
                """));

        IOException failure = assertThrows(IOException.class,
                () -> ChangelogModel.changesets(changelogRoot, changelogRoot.resolve("db.changelog-master.xml")));
        assertTrue(failure.getMessage().contains("includeAll directory not found"), failure.getMessage());
    }

    /**
     * A missing includeAll directory is allowed when the element says so.
     */
    @Test
    void acceptsMissingIncludeAllDirectoryWhenNotRequired() throws IOException {
        write("db.changelog-master.xml", ChangelogTestSupport.changelog("""
                <includeAll path="missing/" relativeToChangelogFile="true"
                            errorIfMissingOrEmpty="false"/>
                """));

        assertEquals(List.of(), ChangelogModel.changesets(
                changelogRoot, changelogRoot.resolve("db.changelog-master.xml")));
    }

    /**
     * A YAML changelog found by includeAll fails closed instead of being skipped.
     */
    @Test
    void rejectsUnsupportedIncludeAllChangelogFormat() throws IOException {
        write("db.changelog-master.xml", ChangelogTestSupport.changelog("""
                <includeAll path="included/" relativeToChangelogFile="true"/>
                """));
        write("included/001-first.yaml", "databaseChangeLog: []\n");

        IOException failure = assertThrows(IOException.class,
                () -> ChangelogModel.changesets(changelogRoot, changelogRoot.resolve("db.changelog-master.xml")));
        assertTrue(failure.getMessage().contains("unsupported changelog format"), failure.getMessage());
    }

    /**
     * An includeAll attribute that needs a caller-supplied class fails closed.
     */
    @Test
    void rejectsUnsupportedIncludeAllAttribute() throws IOException {
        write("db.changelog-master.xml", ChangelogTestSupport.changelog("""
                <includeAll path="included/" relativeToChangelogFile="true"
                            filter="com.example.Filter"/>
                """));

        IOException failure = assertThrows(IOException.class,
                () -> ChangelogModel.changesets(changelogRoot, changelogRoot.resolve("db.changelog-master.xml")));
        assertTrue(failure.getMessage().contains("filter"), failure.getMessage());
    }

    /**
     * An includeAll path that escapes the changelog root is rejected.
     */
    @Test
    void rejectsIncludeAllEscapingTheRoot() throws IOException {
        write("db.changelog-master.xml", ChangelogTestSupport.changelog("""
                <includeAll path="../outside/" relativeToChangelogFile="true"/>
                """));

        IOException failure = assertThrows(IOException.class,
                () -> ChangelogModel.changesets(changelogRoot, changelogRoot.resolve("db.changelog-master.xml")));
        assertTrue(failure.getMessage().contains("escapes the changelog root"), failure.getMessage());
    }

    /**
     * A formatted SQL changelog included through {@code <include>} is parsed into
     * its {@code --changeset} entries, including rollbacks and attributes.
     */
    @Test
    void includesFormattedSqlChangelogThroughInclude() throws IOException {
        write("db.changelog-master.xml", ChangelogTestSupport.changelog("""
                <include file="included/changelog.sql" relativeToChangelogFile="true"/>
                """));
        write("included/changelog.sql", """
                --liquibase formatted sql
                --changeset alice:001-create
                CREATE TABLE t (id int);
                --rollback DROP TABLE t;
                --changeset alice:002-index runInTransaction:false endDelimiter:$$
                CREATE INDEX CONCURRENTLY idx ON t (id)
                $$
                --rollback DROP INDEX CONCURRENTLY idx;
                """);

        List<ChangeSet> changesets = ChangelogModel.changesets(
                changelogRoot, changelogRoot.resolve("db.changelog-master.xml"));

        assertEquals(2, changesets.size());
        ChangeSet create = changesets.get(0);
        assertEquals("001-create", create.id());
        assertEquals("alice", create.author());
        assertTrue(create.sqlSources().getFirst().text().contains("CREATE TABLE t"));
        assertTrue(create.rollbackDefined());
        assertTrue(create.rollbackSources().getFirst().text().contains("DROP TABLE t"));
        ChangeSet index = changesets.get(1);
        assertEquals("002-index", index.id());
        assertFalse(index.runInTransaction());
        assertEquals("$$", index.sqlSources().getFirst().endDelimiter());
        assertTrue(index.rollbackDefined());
    }

    /**
     * A structured change with no usable column list renders no source, matching
     * ADR 0003, instead of malformed SQL.
     */
    @Test
    void rendersNoSourceForStructuredChangesWithoutColumns() throws IOException {
        write("changes.xml", ChangelogTestSupport.changelog("""
                <changeSet id="001-empty-table" author="a">
                    <createTable tableName="t"/>
                </changeSet>
                <changeSet id="002-empty-index" author="a">
                    <createIndex indexName="i" tableName="t"/>
                </changeSet>
                """));

        List<ChangeSet> changesets = ChangelogModel.changesets(changelogRoot, master);

        assertTrue(ChangelogTestSupport.byId(changesets, "001-empty-table").sqlSources().isEmpty());
        assertTrue(ChangelogTestSupport.byId(changesets, "002-empty-index").sqlSources().isEmpty());
    }

    /**
     * A SQL reference that escapes the changelog root is rejected, matching the
     * validator's containment policy instead of reading an arbitrary file.
     */
    @Test
    void rejectsSqlReferenceEscapingTheChangelogRoot() throws IOException {
        write("changes.xml", ChangelogTestSupport.changelog("""
                <changeSet id="001-create" author="a">
                    <sqlFile path="../outside.sql" relativeToChangelogFile="true"/>
                </changeSet>
                """));

        IOException failure = assertThrows(IOException.class,
                () -> ChangelogModel.changesets(changelogRoot, master));
        assertTrue(failure.getMessage().contains("escapes the changelog root"));
    }

    /**
     * An {@code <include>} or {@code <sqlFile>} without its required path
     * attribute is rejected instead of silently contributing nothing, matching
     * the validator's policy.
     */
    @Test
    void rejectsIncludeAndSqlFileWithoutPath() throws IOException {
        write("db.changelog-master.xml", ChangelogTestSupport.changelog("""
                <include/>
                """));

        IOException includeFailure = assertThrows(IOException.class,
                () -> ChangelogModel.changesets(changelogRoot, master));
        assertTrue(includeFailure.getMessage().contains("include without a file attribute"));

        write("db.changelog-master.xml", ChangelogTestSupport.changelog("""
                <include file="changes.xml" relativeToChangelogFile="true"/>
                """));
        write("changes.xml", ChangelogTestSupport.changelog("""
                <changeSet id="001-create" author="a">
                    <sqlFile/>
                </changeSet>
                """));

        IOException sqlFailure = assertThrows(IOException.class,
                () -> ChangelogModel.changesets(changelogRoot, master));
        assertTrue(sqlFailure.getMessage().contains("sqlFile without a path attribute"));
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
