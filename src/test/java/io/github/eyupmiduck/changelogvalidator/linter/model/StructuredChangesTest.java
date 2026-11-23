package io.github.eyupmiduck.changelogvalidator.linter.model;

import io.github.eyupmiduck.changelogvalidator.ChangelogTestSupport;
import io.github.eyupmiduck.changelogvalidator.linter.Finding;
import io.github.eyupmiduck.changelogvalidator.linter.Linter;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfig;
import io.github.eyupmiduck.changelogvalidator.linter.rules.BlockRawAlterTableRule;
import io.github.eyupmiduck.changelogvalidator.linter.rules.RequireNotValidConstraintRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies that {@link ChangelogModel} renders the structured Liquibase change
 * types the rules need into valid PostgreSQL SQL, and that a change missing a
 * required attribute contributes no source instead of invalid SQL.
 */
class StructuredChangesTest {

    @TempDir
    Path root;

    private ChangeSet changeSet(String body) throws IOException {
        Path master = root.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <changeSet id="001" author="a">
                %s
                </changeSet>
                """.formatted(body)));
        return ChangelogModel.changesets(root, master).getFirst();
    }

    private String rendered(String change) throws IOException {
        List<SqlSource> sources = changeSet(change).sqlSources();
        return sources.isEmpty() ? null : sources.getFirst().text();
    }

    @Test
    void rendersAddColumn() throws IOException {
        assertEquals("ALTER TABLE t ADD COLUMN c int;",
                rendered("<addColumn tableName=\"t\"><column name=\"c\" type=\"int\"/></addColumn>"));
        assertEquals("ALTER TABLE s.t ADD COLUMN a int, ADD COLUMN b text;",
                rendered("<addColumn schemaName=\"s\" tableName=\"t\">"
                        + "<column name=\"a\" type=\"int\"/><column name=\"b\" type=\"text\"/></addColumn>"));
        assertEquals("ALTER TABLE t ADD COLUMN c int NOT NULL;",
                rendered("<addColumn tableName=\"t\"><column name=\"c\" type=\"int\">"
                        + "<constraints nullable=\"false\"/></column></addColumn>"));
        assertNull(rendered("<addColumn tableName=\"t\"><column name=\"c\"/></addColumn>"));
    }

    @Test
    void rendersDropColumn() throws IOException {
        assertEquals("ALTER TABLE t DROP COLUMN c;",
                rendered("<dropColumn tableName=\"t\" columnName=\"c\"/>"));
        assertEquals("ALTER TABLE t DROP COLUMN a, DROP COLUMN b;",
                rendered("<dropColumn tableName=\"t\">"
                        + "<column name=\"a\"/><column name=\"b\"/></dropColumn>"));
        assertNull(rendered("<dropColumn tableName=\"t\"/>"));
    }

    @Test
    void rendersRenameColumn() throws IOException {
        assertEquals("ALTER TABLE t RENAME COLUMN a TO b;",
                rendered("<renameColumn tableName=\"t\" oldColumnName=\"a\" newColumnName=\"b\"/>"));
        assertNull(rendered("<renameColumn tableName=\"t\" oldColumnName=\"a\"/>"));
    }

    @Test
    void rendersModifyDataType() throws IOException {
        assertEquals("ALTER TABLE t ALTER COLUMN c TYPE bigint;",
                rendered("<modifyDataType tableName=\"t\" columnName=\"c\" newDataType=\"bigint\"/>"));
        assertNull(rendered("<modifyDataType tableName=\"t\" columnName=\"c\"/>"));
    }

    @Test
    void rendersNotNullConstraints() throws IOException {
        assertEquals("ALTER TABLE t ALTER COLUMN c SET NOT NULL;",
                rendered("<addNotNullConstraint tableName=\"t\" columnName=\"c\"/>"));
        assertEquals("ALTER TABLE t ALTER COLUMN c DROP NOT NULL;",
                rendered("<dropNotNullConstraint tableName=\"t\" columnName=\"c\"/>"));
        assertNull(rendered("<addNotNullConstraint tableName=\"t\"/>"));
    }

    @Test
    void rendersDefaultValues() throws IOException {
        assertEquals("ALTER TABLE t ALTER COLUMN c SET DEFAULT 'x';",
                rendered("<addDefaultValue tableName=\"t\" columnName=\"c\" defaultValue=\"x\"/>"));
        assertEquals("ALTER TABLE t ALTER COLUMN c SET DEFAULT 1;",
                rendered("<addDefaultValue tableName=\"t\" columnName=\"c\" defaultValueNumeric=\"1\"/>"));
        assertEquals("ALTER TABLE t ALTER COLUMN c SET DEFAULT TRUE;",
                rendered("<addDefaultValue tableName=\"t\" columnName=\"c\" defaultValueBoolean=\"true\"/>"));
        assertEquals("ALTER TABLE t ALTER COLUMN c SET DEFAULT now();",
                rendered("<addDefaultValue tableName=\"t\" columnName=\"c\" defaultValueComputed=\"now()\"/>"));
        assertEquals("ALTER TABLE t ALTER COLUMN c SET DEFAULT nextval('s.q'::regclass);",
                rendered("<addDefaultValue tableName=\"t\" columnName=\"c\""
                        + " defaultValueSequenceNext=\"s.q\"/>"));
        assertEquals("ALTER TABLE t ALTER COLUMN c DROP DEFAULT;",
                rendered("<dropDefaultValue tableName=\"t\" columnName=\"c\"/>"));
        assertNull(rendered("<addDefaultValue tableName=\"t\" columnName=\"c\"/>"));
    }

    @Test
    void rendersForeignKey() throws IOException {
        assertEquals("ALTER TABLE s.t ADD CONSTRAINT fk FOREIGN KEY (a, b) REFERENCES r.u (x, y)"
                        + " ON DELETE CASCADE ON UPDATE SET NULL DEFERRABLE INITIALLY DEFERRED NOT VALID;",
                rendered("<addForeignKeyConstraint baseTableSchemaName=\"s\" baseTableName=\"t\""
                        + " baseColumnNames=\"a, b\" referencedTableSchemaName=\"r\""
                        + " referencedTableName=\"u\" referencedColumnNames=\"x, y\""
                        + " constraintName=\"fk\" onDelete=\"CASCADE\" onUpdate=\"SET NULL\""
                        + " deferrable=\"true\" initiallyDeferred=\"true\" validate=\"false\"/>"));
        assertNull(rendered("<addForeignKeyConstraint baseTableName=\"t\" baseColumnNames=\"a\""
                + " referencedTableName=\"u\"/>"));
    }

    @Test
    void rendersUniqueAndCheckConstraints() throws IOException {
        assertEquals("ALTER TABLE t ADD CONSTRAINT uq UNIQUE (a, b);",
                rendered("<addUniqueConstraint tableName=\"t\" columnNames=\"a, b\""
                        + " constraintName=\"uq\" validate=\"false\"/>"));
        assertEquals("ALTER TABLE t ADD CONSTRAINT ck CHECK (c > 0);",
                rendered("<addCheckConstraint tableName=\"t\" constraintName=\"ck\""
                        + " constraintBody=\"c > 0\"/>"));
        assertEquals("ALTER TABLE t ADD CHECK (c > 0) NOT VALID;",
                rendered("<addCheckConstraint tableName=\"t\" constraintBody=\"c > 0\""
                        + " validate=\"false\"/>"));
        assertNull(rendered("<addCheckConstraint tableName=\"t\"/>"));
    }

    @Test
    void rendersPrimaryKeyAndDrop() throws IOException {
        assertEquals("ALTER TABLE t ADD CONSTRAINT pk PRIMARY KEY (a, b);",
                rendered("<addPrimaryKey tableName=\"t\" columnNames=\"a, b\" constraintName=\"pk\"/>"));
        assertEquals("ALTER TABLE t ADD CONSTRAINT pk PRIMARY KEY USING INDEX t_pkey_idx;",
                rendered("<addPrimaryKey tableName=\"t\" forIndexName=\"t_pkey_idx\""
                        + " constraintName=\"pk\"/>"));
        assertNull(rendered("<addPrimaryKey tableName=\"t\" forIndexName=\"i\""
                + " forIndexSchemaName=\"other\"/>"));
        assertEquals("ALTER TABLE t DROP CONSTRAINT pk;",
                rendered("<dropPrimaryKey tableName=\"t\" constraintName=\"pk\"/>"));
        assertNull(rendered("<dropPrimaryKey tableName=\"t\"/>"));
    }

    @Test
    void rendersSequences() throws IOException {
        assertEquals("CREATE SEQUENCE s.q AS bigint INCREMENT BY 2 MINVALUE 1 MAXVALUE 100"
                        + " START WITH 5 CACHE 10 CYCLE ORDER;",
                rendered("<createSequence schemaName=\"s\" sequenceName=\"q\" dataType=\"bigint\""
                        + " incrementBy=\"2\" minValue=\"1\" maxValue=\"100\" startValue=\"5\""
                        + " cacheSize=\"10\" cycle=\"true\" ordered=\"true\"/>"));
        assertEquals("CREATE SEQUENCE q;",
                rendered("<createSequence sequenceName=\"q\"/>"));
        assertEquals("DROP SEQUENCE s.q;",
                rendered("<dropSequence schemaName=\"s\" sequenceName=\"q\"/>"));
        assertNull(rendered("<createSequence/>"));
    }

    @Test
    void rendersAStructuredRollback() throws IOException {
        ChangeSet changeSet = changeSet("<sql>SELECT 1;</sql>"
                + "<rollback><dropColumn tableName=\"t\" columnName=\"c\"/></rollback>");

        assertEquals("ALTER TABLE t DROP COLUMN c;", changeSet.rollbackSources().getFirst().text());
    }

    @Test
    void structuredAddColumnIsVisibleToRules() throws Exception {
        ChangeSet changeSet = changeSet(
                "<addColumn tableName=\"t\"><column name=\"c\" type=\"int\"/></addColumn>");

        List<Finding> findings = Linter.withRules(List.of(new BlockRawAlterTableRule()))
                .lint(List.of(changeSet));

        assertEquals(1, findings.size());
        assertEquals(BlockRawAlterTableRule.ID, findings.getFirst().ruleId());
    }

    @Test
    void structuredCheckConstraintIsVisibleToTheNotValidRule() throws Exception {
        ChangeSet changeSet = changeSet(
                "<addCheckConstraint tableName=\"t\" constraintBody=\"c > 0\"/>");
        LinterConfig config = new LinterConfig(null, Severity.ERROR, List.of(),
                List.of(RequireNotValidConstraintRule.ID), Map.of());

        List<Finding> findings = new Linter(List.of(new RequireNotValidConstraintRule()), config)
                .lint(List.of(changeSet));

        assertEquals(1, findings.size());
        assertEquals(RequireNotValidConstraintRule.ID, findings.getFirst().ruleId());
    }

    @Test
    void skipsChangesWithMissingRequiredAttributes() throws IOException {
        for (String change : List.of(
                "<addColumn tableName=\"t\"/>",
                "<dropColumn tableName=\"t\"/>",
                "<renameColumn tableName=\"t\" newColumnName=\"b\"/>",
                "<modifyDataType tableName=\"t\" columnName=\"c\"/>",
                "<addNotNullConstraint tableName=\"t\"/>",
                "<addDefaultValue tableName=\"t\" columnName=\"c\"/>",
                "<dropDefaultValue tableName=\"t\"/>",
                "<addForeignKeyConstraint baseTableName=\"t\" baseColumnNames=\"a\"/>",
                "<addUniqueConstraint tableName=\"t\"/>",
                "<addCheckConstraint tableName=\"t\"/>",
                "<addPrimaryKey tableName=\"t\"/>",
                "<dropPrimaryKey tableName=\"t\"/>",
                "<createSequence/>",
                "<dropSequence/>")) {
            assertNull(rendered(change), change);
        }
    }
}
