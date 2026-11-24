package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenType;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenWords;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatement;

import java.util.ArrayList;
import java.util.List;

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
 * <p>The rule sees literal SQL and the structured change types the model renders
 * (for example {@code <addColumn>}, {@code <dropColumn>} and
 * {@code <modifyDataType>}), so a structured change cannot hide an
 * {@code ALTER TABLE}. A stored-routine body is the implementation of a wrapper
 * rather than a caller's raw statement, so it is exempt. Forward and rollback
 * SQL are both checked, because a raw rollback {@code ALTER TABLE} takes the
 * same lock.
 *
 * <p>The rule is an error and on by default. Each finding names the table and
 * the suggested wrapper, so the fix is obvious without reading this source.
 */
public final class BlockRawAlterTableRule implements Rule {

    /**
     * The rule id.
     */
    public static final String ID = "block-raw-alter-table";

    /**
     * Returns the index of the first {@code ALTER TABLE} phrase, or {@code -1}.
     * A statement whose SQL was not split into individual commands can contain
     * an {@code ALTER TABLE} after another command, so the phrase is searched for
     * at every token position rather than only at the start.
     */
    private static int alterTableAt(List<Token> tokens, int from) {
        for (int i = from; i + 1 < tokens.size(); i++) {
            if (tokens.get(i).matchesKeyword("ALTER") && tokens.get(i + 1).matchesKeyword("TABLE")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the end of the command that starts at {@code alter}: the next
     * top-level {@code ;}, the next top-level {@code ALTER TABLE}, or the end of
     * the tokens. The bound keeps a hint from reading a later command's clause in
     * an unsplit statement.
     */
    private static int commandEnd(List<Token> tokens, int alter) {
        int depth = 0;
        for (int i = alter + 2; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.type() == TokenType.PUNCTUATION) {
                if (token.text().equals("(")) {
                    depth++;
                    continue;
                }
                if (token.text().equals(")")) {
                    if (depth > 0) {
                        depth--;
                    }
                    continue;
                }
                if (depth == 0 && token.text().equals(";")) {
                    return i;
                }
            }
            if (depth == 0 && token.matchesKeyword("ALTER") && TokenWords.at(tokens, i + 1, "TABLE")) {
                return i;
            }
        }
        return tokens.size();
    }

    /**
     * Returns the index of the table identifier, skipping the optional
     * {@code IF EXISTS} and {@code ONLY} prefixes.
     */
    private static int tableNameStart(List<Token> tokens) {
        int start = 2;
        if (start + 1 < tokens.size()
                && tokens.get(start).matchesKeyword("IF")
                && tokens.get(start + 1).matchesKeyword("EXISTS")) {
            start += 2;
        }
        if (start < tokens.size() && tokens.get(start).matchesKeyword("ONLY")) {
            start++;
        }
        return start;
    }

    /**
     * Suggests the wrapper for the first recognized clause, or a generic pointer.
     * An {@code ADD CONSTRAINT <name>} clause is matched by its trailing keyword,
     * because the constraint name sits between {@code CONSTRAINT} and the kind.
     */
    private static String wrapperHint(List<Token> tokens) {
        int rename = TokenWords.indexOf(tokens, "RENAME", 2);
        if (rename >= 0 && rename + 1 < tokens.size()) {
            Token next = tokens.get(rename + 1);
            if (next.matchesKeyword("TO")) {
                return "use ddl_utils.rename_table";
            }
            if (next.matchesKeyword("CONSTRAINT")) {
                return "use ddl_utils.rename_constraint";
            }
            if (next.matchesKeyword("COLUMN")
                    || (rename + 2 < tokens.size() && tokens.get(rename + 2).matchesKeyword("TO"))) {
                return "use ddl_utils.rename_column";
            }
            // RENAME is unreserved in PostgreSQL, so it can be an identifier
            // (for example a constraint named rename). Fall through to the
            // clause checks below.
        }
        if (hasClause(tokens, "DROP", "NOT", "NULL")) {
            return "use ddl_utils.drop_not_null";
        }
        if (hasClause(tokens, "SET", "NOT", "NULL")) {
            return "use ddl_utils.ensure_not_null";
        }
        if (hasClause(tokens, "DROP", "COLUMN")) {
            return "use ddl_utils.drop_column or ddl_utils.drop_columns";
        }
        if (hasClause(tokens, "DROP", "CONSTRAINT")) {
            return "use ddl_utils.drop_constraint";
        }
        if (hasClause(tokens, "ALTER", "COLUMN")) {
            return "use the matching ddl_utils column helper (for example ddl_utils.set_column_default)";
        }
        if (hasClause(tokens, "FOREIGN", "KEY") || hasClause(tokens, "ADD", "FOREIGN")) {
            return "use ddl_utils.add_foreign_key or ddl_utils.ensure_foreign_key";
        }
        if (hasClause(tokens, "ADD", "CONSTRAINT")) {
            if (hasClause(tokens, "CHECK")) {
                return "use ddl_utils.add_check_constraint or ddl_utils.ensure_check_constraint";
            }
            if (hasClause(tokens, "PRIMARY", "KEY")) {
                return "use ddl_utils.add_primary_key_using_index";
            }
            if (hasClause(tokens, "UNIQUE")) {
                return "use ddl_utils.add_unique_constraint_using_index";
            }
            return "use the matching ddl_utils constraint helper";
        }
        if (hasClause(tokens, "ADD", "CHECK")) {
            return "use ddl_utils.add_check_constraint or ddl_utils.ensure_check_constraint";
        }
        if (hasClause(tokens, "ADD", "PRIMARY", "KEY")) {
            return "use ddl_utils.add_primary_key_using_index";
        }
        if (hasClause(tokens, "ADD", "UNIQUE")) {
            return "use ddl_utils.add_unique_constraint_using_index";
        }
        if (hasClause(tokens, "ADD")) {
            return "use ddl_utils.add_column or ddl_utils.add_columns";
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
        if (!RuleSupport.appliesToPostgres(context.changeSet())) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        collect(context.forward(), violations);
        collect(context.rollback(), violations);
        return List.copyOf(violations);
    }

    private void collect(List<SqlUnit> units, List<Violation> violations) {
        for (SqlUnit unit : units) {
            if (unit.source().kind() == SqlSource.Kind.ROUTINE_BODY || !RuleSupport.appliesToPostgres(unit)) {
                continue;
            }
            for (SqlStatement statement : unit.commands()) {
                List<Token> tokens = RuleSupport.nonTriviaWithin(unit.tokens(), statement);
                int from = 0;
                while (from < tokens.size()) {
                    int alter = alterTableAt(tokens, from);
                    if (alter < 0) {
                        break;
                    }
                    int end = commandEnd(tokens, alter);
                    List<Token> command = tokens.subList(alter, end);
                    Token token = tokens.get(alter);
                    String table = RuleSupport.qualifiedName(command, tableNameStart(command));
                    String hint = wrapperHint(command);
                    violations.add(new Violation(
                            "ALTER TABLE",
                            "raw ALTER TABLE" + (table == null ? "" : " on " + table)
                                    + " bypasses the ddl_utils lock-aware wrappers; " + hint,
                            "Call the matching ddl_utils function/procedure instead of a hand-written ALTER TABLE.",
                            unit.file(), token.line(), token.column()));
                    from = Math.max(end, alter + 1);
                }
            }
        }
    }

    @Override
    public String description() {
        return "a raw `ALTER TABLE` in changelog SQL";
    }

    @Override
    public String help() {
        return "Use the `ddl_utils` helper or a structured change type instead of raw `ALTER TABLE`.";
    }
}
