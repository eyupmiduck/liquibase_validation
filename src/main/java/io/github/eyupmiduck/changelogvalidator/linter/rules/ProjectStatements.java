package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.ProjectAnalysisRule;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatement;

import java.util.ArrayList;
import java.util.List;

/**
 * The forward SQL statements of a project, in execution order, as the token
 * sequence each project-analysis rule inspects. Routine bodies and sources
 * filtered to another database are skipped.
 */
final class ProjectStatements {

    private ProjectStatements() {
    }

    /**
     * One forward statement.
     *
     * @param changeSet the changeset that declared it
     * @param unit      the SQL unit
     * @param tokens    the non-trivia tokens in the statement
     * @param order     the global statement order
     */
    record Statement(ChangeSet changeSet, SqlUnit unit, List<Token> tokens, int order) {

        /**
         * Validates the required components.
         */
        Statement {
            java.util.Objects.requireNonNull(changeSet, "changeSet");
            java.util.Objects.requireNonNull(unit, "unit");
            java.util.Objects.requireNonNull(tokens, "tokens");
            tokens = List.copyOf(tokens);
        }
    }

    /**
     * Returns every forward statement in execution order.
     *
     * @param context the project context
     * @return the statements
     */
    static List<Statement> forward(ProjectAnalysisRule.ProjectContext context) {
        List<Statement> statements = new ArrayList<>();
        int order = 0;
        for (ProjectAnalysisRule.AnalyzedChangeSet analyzed : context.changesets()) {
            if (!RuleSupport.appliesToPostgres(analyzed.changeSet())) {
                continue;
            }
            for (SqlUnit unit : RuleSupport.applicableToPostgres(analyzed.context().forward())) {
                if (unit.source().kind() == SqlSource.Kind.ROUTINE_BODY) {
                    continue;
                }
                for (SqlStatement statement : unit.commands()) {
                    List<Token> tokens = RuleSupport.nonTriviaWithin(unit.tokens(), statement);
                    if (!tokens.isEmpty()) {
                        statements.add(new Statement(analyzed.changeSet(), unit, tokens, order++));
                    }
                }
            }
        }
        return List.copyOf(statements);
    }
}
