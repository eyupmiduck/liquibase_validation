package io.github.eyupmiduck.changelogvalidator.linter.model;

import io.github.eyupmiduck.changelogvalidator.ChangelogTestSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link ChangelogModel} captures rollback structured changes,
 * inline routine bodies, and a replace-with-nothing modification.
 */
class ChangelogModelRollbackAndRoutineTest {

    @TempDir
    Path changelogRoot;


    private ChangeSet load(String changesBody) throws IOException {
        Files.writeString(changelogRoot.resolve("db.changelog-master.xml"), ChangelogTestSupport.changelog(
                "<include file=\"changes.xml\" relativeToChangelogFile=\"true\"/>"));
        Files.writeString(changelogRoot.resolve("changes.xml"), ChangelogTestSupport.changelog(changesBody));
        return ChangelogModel.changesets(changelogRoot, changelogRoot.resolve("db.changelog-master.xml")).get(0);
    }

    /**
     * A structured change inside {@code <rollback>} is rendered, so a rule can
     * inspect it.
     */
    @Test
    void rendersStructuredRollbackChanges() throws IOException {
        ChangeSet changeSet = load("""
                <changeSet id="001" author="a">
                    <sql>CREATE INDEX idx_t ON t (c);</sql>
                    <rollback>
                        <dropIndex indexName="idx_t"/>
                    </rollback>
                </changeSet>
                """);

        assertEquals(1, changeSet.rollbackSources().size());
        SqlSource rollback = changeSet.rollbackSources().get(0);
        assertEquals(SqlSource.Kind.INLINE_SQL, rollback.kind());
        assertTrue(rollback.text().contains("DROP INDEX idx_t"));
    }

    /**
     * A routine body given as element text (no {@code path}) is captured as an
     * inline routine source.
     */
    @Test
    void capturesInlineRoutineBodies() throws IOException {
        ChangeSet changeSet = load("""
                <changeSet id="001" author="a">
                    <createProcedure>BEGIN PERFORM 1; END;</createProcedure>
                </changeSet>
                """);

        assertEquals(1, changeSet.sqlSources().size());
        SqlSource source = changeSet.sqlSources().get(0);
        assertEquals(SqlSource.Kind.ROUTINE_BODY, source.kind());
        assertTrue(source.isInline());
        assertTrue(source.text().contains("PERFORM 1"));
    }

    /**
     * A {@code <replace>} with an empty {@code with} is a replace-with-nothing, not
     * a malformed modification.
     */
    @Test
    void allowsAnEmptyReplaceWith() throws IOException {
        ChangeSet changeSet = load("""
                <changeSet id="001" author="a">
                    <sql>SELECT DISTINCT 1;</sql>
                    <modifySql>
                        <replace replace="DISTINCT " with=""/>
                    </modifySql>
                </changeSet>
                """);

        List<SqlModification> modifications = changeSet.normalisation().modifySql();
        assertEquals(1, modifications.size());
        assertEquals("", modifications.get(0).with());
    }
}
