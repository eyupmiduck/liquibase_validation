package io.github.eyupmiduck.changelogvalidator.linter.model;

import io.github.eyupmiduck.changelogvalidator.ChangelogTestSupport;
import io.github.eyupmiduck.changelogvalidator.linter.Finding;
import io.github.eyupmiduck.changelogvalidator.linter.Linter;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfig;
import io.github.eyupmiduck.changelogvalidator.linter.rules.RequireConcurrentIndexCreationRule;
import io.github.eyupmiduck.changelogvalidator.linter.rules.Rules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the normalisation the {@link ChangelogModel} captures: changelog
 * properties (first value wins), a changeset's {@code <modifySql>}
 * transformations, the SQL rendering of structured change types, and that the
 * {@link Linter} rules see the normalised SQL.
 */
class ChangelogModelNormalisationTest {

    @TempDir
    Path changelogRoot;

    private Path master;



    @BeforeEach
    void createChangelog() throws IOException {
        write("db.changelog-master.xml", ChangelogTestSupport.changelog("""
                <include file="changes.xml" relativeToChangelogFile="true"/>
                """));
        write("changes.xml", ChangelogTestSupport.changelog("""
                <property name="table_name" value="t"/>
                <property name="table_name" value="ignored"/>
                <property name="idx_name" value="idx_t"/>
                <changeSet id="010-create-index" author="a" runInTransaction="false">
                    <createIndex indexName="${idx_name}" tableName="${table_name}">
                        <column name="c"/>
                    </createIndex>
                </changeSet>
                <changeSet id="011-modify" author="a">
                    <sql>SELECT 1;</sql>
                    <modifySql applyToRollback="true" dbms="postgresql">
                        <append value=" --appended"/>
                        <replace replace="SELECT" with="SELECT DISTINCT"/>
                    </modifySql>
                </changeSet>
                <changeSet id="012-fresh-schema" author="a" runInTransaction="false">
                    <createTable tableName="fresh">
                        <column name="c" type="int"/>
                    </createTable>
                    <createIndex indexName="idx_fresh" tableName="fresh">
                        <column name="c"/>
                    </createIndex>
                </changeSet>
                <changeSet id="013-drop-index" author="a">
                    <dropIndex indexName="idx_t"/>
                </changeSet>
                """));
        master = changelogRoot.resolve("db.changelog-master.xml");
    }

    /**
     * Changelog properties are collected with Liquibase's first-value-wins rule
     * and attached to every changeset.
     */
    @Test
    void readsPropertiesFirstValueWins() throws IOException {
        ChangeSet modify = ChangelogTestSupport.byId(ChangelogModel.changesets(changelogRoot, master), "011-modify");

        assertEquals("t", modify.normalisation().properties().get("table_name"));
        assertEquals("idx_t", modify.normalisation().properties().get("idx_name"));
        assertEquals(Map.of("table_name", "t", "idx_name", "idx_t"), modify.normalisation().properties());
    }

    /**
     * A {@code <modifySql>} element is parsed into its transformations, carrying
     * the rollback and dbms attributes.
     */
    @Test
    void parsesModifySql() throws IOException {
        ChangeSet modify = ChangelogTestSupport.byId(ChangelogModel.changesets(changelogRoot, master), "011-modify");

        List<SqlModification> modifications = modify.normalisation().modifySql();
        assertEquals(2, modifications.size());
        assertEquals(SqlModification.Kind.APPEND, modifications.get(0).kind());
        assertEquals(" --appended", modifications.get(0).value());
        assertEquals(SqlModification.Kind.REPLACE, modifications.get(1).kind());
        assertEquals("SELECT", modifications.get(1).value());
        assertEquals("SELECT DISTINCT", modifications.get(1).with());
        assertTrue(modifications.get(0).applyToRollback());
        assertEquals("postgresql", modifications.get(0).dbms());
    }

    /**
     * Structured {@code createTable}, {@code createIndex} and {@code dropIndex}
     * changes render SQL sources, while a changeset with only another structured
     * type still contributes none.
     */
    @Test
    void rendersStructuredChangeTypes() throws IOException {
        List<ChangeSet> changesets = ChangelogModel.changesets(changelogRoot, master);

        ChangeSet createIndex = ChangelogTestSupport.byId(changesets, "010-create-index");
        assertEquals(1, createIndex.sqlSources().size());
        assertTrue(createIndex.sqlSources().get(0).isInline());
        assertEquals("CREATE INDEX ${idx_name} ON ${table_name} (c);", createIndex.sqlSources().get(0).text());

        assertEquals(1, ChangelogTestSupport.byId(changesets, "013-drop-index").sqlSources().size());
        assertEquals("DROP INDEX idx_t;", ChangelogTestSupport.byId(changesets, "013-drop-index").sqlSources().get(0).text());

        ChangeSet fresh = ChangelogTestSupport.byId(changesets, "012-fresh-schema");
        assertEquals(2, fresh.sqlSources().size());
        assertEquals("CREATE TABLE fresh (c int);", fresh.sqlSources().get(0).text());
        assertEquals("CREATE INDEX idx_fresh ON fresh (c);", fresh.sqlSources().get(1).text());
    }

    /**
     * The linter sees the normalised structured SQL: the property-substituted
     * {@code createIndex} is reported, while the index whose table the same
     * changeset creates is exempt.
     */
    @Test
    void rulesSeeNormalisedStructuredSql() throws IOException {
        List<ChangeSet> changesets = ChangelogModel.changesets(changelogRoot, master);
        LinterConfig config = new LinterConfig(null, Severity.ERROR, List.of(),
                List.of(RequireConcurrentIndexCreationRule.ID), Map.of());
        Linter linter = new Linter(Rules.all(17), config);

        List<Finding> findings = linter.lint(changesets);

        assertEquals(1, findings.size(), () -> "unexpected findings: " + findings);
        assertEquals("010-create-index", findings.get(0).changeSetId());
        assertTrue(findings.get(0).message().contains("blocks writes on t"),
                () -> "expected the substituted table, got: " + findings.get(0).message());
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = changelogRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
