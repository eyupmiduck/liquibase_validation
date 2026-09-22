package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenType;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Blocks a raw {@code ALTER TABLE} statement in a changeset and points the
 * developer at the lock-aware {@code ddl_utils} wrapper for the operation.
 *
 * <p>A hand-written {@code ALTER TABLE} takes {@code ACCESS EXCLUSIVE} (or
 * {@code SHARE ROW EXCLUSIVE} for a foreign key) without the bounded
 * {@code lock_timeout} and lock settings the {@code ddl_utils} wrappers resolve
 * through {@code get_lock_settings}. Calling the wrapper keeps the lock
 * behaviour consistent and gives the operator a single place to configure it.
 *
 * <p>The rule only sees literal SQL: a structured change type
 * ({@code <addColumn>}, {@code <modifyDataType>}, ...) generates SQL elsewhere
 * and is out of scope, and a stored-routine body is the implementation of a
 * wrapper rather than a caller's raw statement, so it is exempt. Forward and
 * rollback SQL are both checked, because a raw rollback {@code ALTER TABLE}
 * takes the same lock.
 *
 * <p>The rule is an error and on by default. Each finding names the table and
 * the suggested wrapper, so the fix is obvious without reading this source.
 */
public final class BlockRawAlterTableRule implements Rule {

    /**
     * The rule id.
     */
    public static final String ID = "block-raw-alter-table";

    private static boolean isAlterTable(List<Token> tokens) {
        return tokens.size() >= 2
                && tokens.get(0).matchesKeyword("ALTER")
                && tokens.get(1).matchesKeyword("TABLE");
    }

    /**
     * Suggests the wrapper for the first recognized clause, or a generic pointer.
     * An {@code ADD CONSTRAINT <name>} clause is matched by its trailing keyword,
     * because the constraint name sits between {@code CONSTRAINT} and the kind.
     */
    private static String wrapperHint(List<Token> tokens) {
        if (hasClause(tokens, "RENAME", "COLUMN")) {
            return "use ddl_utils.rename_column";
        }
        if (hasClause(tokens, "RENAME", "CONSTRAINT")) {
            return "use ddl_utils.rename_table";
        }
        if (hasClause(tokens, "RENAME", "TO")) {
            return "use ddl_utils.rename_table";
        }
        if (hasClause(tokens, "DROP", "NOT", "NULL")) {
            return "use ddl_utils.drop_not_null";
        }
        if (hasClause(tokens, "SET", "NOT", "NULL")) {
            return "use ddl_utils.ensure_not_null";
        }
        if (hasClause(tokens, "FOREIGN", "KEY") || hasClause(tokens, "ADD", "FOREIGN")) {
            return "use ddl_utils.add_foreign_key or ddl_utils.ensure_foreign_key";
        }
        if (hasClause(tokens, "CHECK")) {
            return "use ddl_utils.add_check_constraint or ddl_utils.ensure_check_constraint";
        }
        if (hasClause(tokens, "DROP", "COLUMN")) {
            return "use ddl_utils.drop_column or ddl_utils.drop_columns";
        }
        if (hasClause(tokens, "DROP", "CONSTRAINT")) {
            return "use ddl_utils.drop_constraint";
        }
        if (hasClause(tokens, "ADD", "COLUMN") || hasClause(tokens, "ADD")) {
            return "use ddl_utils.add_column or ddl_utils.add_columns";
        }
        if (hasClause(tokens, "ALTER", "COLUMN")) {
            return "use the matching ddl_utils column helper (for example ddl_utils.set_column_default)";
        }
        return "use the matching ddl_utils function/procedure (see the ddl_utils schema)";
    }

    private static boolean hasClause(List<Token> words, String... keywords) {
        for (int i = 0; i + keywords.length <= words.size(); i++) {
            boolean match = true;
            for (int j = 0; j < keywords.length; j++) {
                if (!words.get(i + j).matchesKeyword(keywords[j])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    private static String qualifiedName(List<Token> tokens, int start) {
        if (start >= tokens.size()) {
            return null;
        }
        StringBuilder name = new StringBuilder();
        for (int i = start; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.type() == TokenType.WORD || token.type() == TokenType.QUOTED_IDENTIFIER) {
                name.append(identifier(token));
            } else if (token.type() == TokenType.PUNCTUATION && token.text().equals(".")) {
                name.append('.');
            } else {
                break;
            }
        }
        return name.isEmpty() ? null : name.toString();
    }

    private static String identifier(Token token) {
        if (token.type() == TokenType.QUOTED_IDENTIFIER) {
            return token.text().substring(1, token.text().length() - 1).replace("\"\"", "\"");
        }
        return token.text().toLowerCase(Locale.ROOT);
    }

    private static Token firstToken(SqlUnit unit, SqlStatement statement) {
        for (Token token : unit.tokens()) {
            if (token.startOffset() >= statement.startOffset()) {
                return token;
            }
        }
        throw new IllegalStateException("no token for statement at offset " + statement.startOffset());
    }

    private static List<Token> nonTriviaWithin(List<Token> tokens, SqlStatement statement) {
        return tokens.stream()
                .filter(token -> !token.isTrivia())
                .filter(token -> token.startOffset() >= statement.startOffset()
                        && token.endOffset() <= statement.endOffset())
                .toList();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public List<Violation> check(RuleContext context) {
        List<Violation> violations = new ArrayList<>();
        collect(context.forward(), violations);
        collect(context.rollback(), violations);
        return List.copyOf(violations);
    }

    private void collect(List<SqlUnit> units, List<Violation> violations) {
        for (SqlUnit unit : units) {
            if (unit.source().kind() == SqlSource.Kind.ROUTINE_BODY) {
                continue;
            }
            for (SqlStatement statement : unit.statements()) {
                List<Token> tokens = nonTriviaWithin(unit.tokens(), statement);
                if (!isAlterTable(tokens)) {
                    continue;
                }
                Token token = firstToken(unit, statement);
                String table = qualifiedName(tokens, 2);
                String hint = wrapperHint(tokens);
                violations.add(new Violation(
                        "ALTER TABLE",
                        "raw ALTER TABLE" + (table == null ? "" : " on " + table)
                                + " bypasses the ddl_utils lock-aware wrappers; " + hint,
                        "Call the matching ddl_utils function/procedure instead of a hand-written ALTER TABLE.",
                        unit.file(), token.line(), token.column()));
            }
        }
    }
}
