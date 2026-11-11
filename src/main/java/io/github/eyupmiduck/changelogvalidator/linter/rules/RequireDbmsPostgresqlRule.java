package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenType;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenWords;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Requires a changeset whose SQL uses PostgreSQL-only syntax to declare
 * {@code dbms="postgresql"}, so the changelog fails loudly instead of at deploy
 * on a non-PostgreSQL target.
 *
 * <p>The rule recognizes constructs that are specific to PostgreSQL and have no
 * portable equivalent: {@code CONCURRENTLY} (index and partition operations),
 * {@code CREATE DOMAIN}, {@code CREATE EXTENSION}, {@code LANGUAGE plpgsql},
 * {@code USING} index methods such as {@code gin}/{@code gist}/{@code brin}, and
 * PostgreSQL casts and operators ({@code ::}, {@code @>}, {@code #>},
 * {@code ?|}, ...) that are common in {@code <sql>} bodies.
 *
 * <p>A changeset is accepted when it gates execution to PostgreSQL only with
 * {@code dbms="postgresql"} (case-insensitive), or when a SQL source does the
 * same (Liquibase applies a source-level {@code dbms} too). Negated entries do
 * not disqualify a positive PostgreSQL gate ({@code postgresql,!mysql} still
 * runs only on PostgreSQL), but a list that positively names another database
 * does, because the PostgreSQL-only SQL would still run on that target. The rule is an error and
 * on by default: a PostgreSQL-only changelog without the gate is a deployment
 * hazard, not a style nit.
 *
 * <p>It is a token-level heuristic: a construct inside a dollar-quoted routine
 * body is examined through the body's own tokens, and a mention in a string or
 * comment does not count. An unlisted PostgreSQL-only construct is a false
 * negative, which is acceptable (the rule is a guard, not a parser).
 */
public final class RequireDbmsPostgresqlRule implements Rule {

    /**
     * The rule id.
     */
    public static final String ID = "require-dbms-postgresql";

    /**
     * PostgreSQL index access methods that have no portable equivalent.
     */
    private static final Set<String> POSTGRES_INDEX_METHODS = Set.of("gin", "gist", "brin", "spgist");

    // Operators and punctuation that only PostgreSQL (among common targets)
    // accepts in these positions. Matching is token-based, so a keyword inside
    // a string or comment cannot trigger it.
    private static final Pattern POSTGRES_OPERATORS =
            Pattern.compile("::|#>>|#>|@>|<@|\\?\\||\\?&");

    private static boolean gatedOnPostgres(ChangeSet changeSet) {
        return postgresOnly(changeSet.dbms());
    }

    /**
     * Returns whether {@code dbms} restricts execution to PostgreSQL. A plain
     * PostgreSQL name (or a repeated one) qualifies; {@code all}, {@code none},
     * negated entries, and any other database name do not, because the SQL would
     * still run on a target that cannot execute it.
     */
    private static boolean postgresOnly(String dbms) {
        if (dbms == null || dbms.isBlank()) {
            return false;
        }
        boolean hasPositive = false;
        boolean onlyPostgres = true;
        for (String candidate : dbms.split(",")) {
            String name = candidate.strip();
            if (name.isEmpty()) {
                continue;
            }
            if (name.startsWith("!")) {
                String excluded = name.substring(1).strip();
                if (excluded.equalsIgnoreCase("postgresql") || excluded.equalsIgnoreCase("all")) {
                    return false;
                }
                continue;
            }
            if (name.equalsIgnoreCase("none")) {
                return false;
            }
            hasPositive = true;
            if (!name.equalsIgnoreCase("postgresql")) {
                onlyPostgres = false;
            }
        }
        return hasPositive && onlyPostgres;
    }

    private static LocatedConstruct firstPostgresConstruct(SqlUnit unit) {
        // Filter the unit's tokens once, then walk the ordered, non-overlapping
        // statements with a two-pointer scan instead of re-streaming per
        // statement.
        List<Token> words = unit.tokens().stream()
                .filter(token -> !token.isTrivia())
                .toList();
        int from = 0;
        for (SqlStatement statement : unit.commands()) {
            while (from < words.size() && words.get(from).endOffset() <= statement.startOffset()) {
                from++;
            }
            int to = from;
            while (to < words.size() && words.get(to).endOffset() <= statement.endOffset()) {
                to++;
            }
            String construct = statementConstruct(words.subList(from, to));
            if (construct != null) {
                return new LocatedConstruct(construct, words.get(from));
            }
        }
        for (Token token : words) {
            if (token.type() == TokenType.OPERATOR
                    && POSTGRES_OPERATORS.matcher(token.text()).matches()) {
                return new LocatedConstruct("the " + token.text() + " operator", token);
            }
        }
        return null;
    }

    private static String statementConstruct(List<Token> words) {
        if (TokenWords.startsWith(words, "CREATE", "DOMAIN")) {
            return "CREATE DOMAIN";
        }
        if (TokenWords.startsWith(words, "CREATE", "EXTENSION")) {
            return "CREATE EXTENSION";
        }
        // CONCURRENTLY only counts in a PostgreSQL-only position, so an ordinary
        // identifier named "concurrently" does not trigger the rule.
        if (createIndexConcurrently(words)) {
            return "CONCURRENTLY";
        }
        if (TokenWords.startsWith(words, "DROP", "INDEX")
                && TokenWords.adjacent(words, "INDEX", "CONCURRENTLY")) {
            return "CONCURRENTLY";
        }
        if (TokenWords.startsWith(words, "REINDEX") && TokenWords.contains(words, "CONCURRENTLY")) {
            return "CONCURRENTLY";
        }
        if (TokenWords.startsWith(words, "ALTER", "TABLE")
                && TokenWords.adjacent(words, "DETACH", "PARTITION")
                && TokenWords.contains(words, "CONCURRENTLY")) {
            return "ALTER TABLE ... DETACH PARTITION CONCURRENTLY";
        }
        if (isRoutineDeclaration(words)
                && TokenWords.contains(words, "LANGUAGE")
                && TokenWords.adjacent(words, "LANGUAGE", "PLPGSQL")) {
            return "LANGUAGE plpgsql";
        }
        if (usesPostgresIndexMethod(words)) {
            return "a PostgreSQL index method";
        }
        return null;
    }

    /**
     * Returns whether the statement is {@code CREATE [UNIQUE] INDEX ...
     * CONCURRENTLY}, not merely a statement containing those words.
     */
    private static boolean createIndexConcurrently(List<Token> words) {
        if (!TokenWords.startsWith(words, "CREATE")) {
            return false;
        }
        int index = 1;
        if (index < words.size() && words.get(index).matchesKeyword("UNIQUE")) {
            index++;
        }
        return index + 1 < words.size()
                && words.get(index).matchesKeyword("INDEX")
                && words.get(index + 1).matchesKeyword("CONCURRENTLY");
    }

    /**
     * Returns whether the statement declares or alters a function/procedure,
     * where {@code LANGUAGE plpgsql} is meaningful.
     */
    private static boolean isRoutineDeclaration(List<Token> words) {
        return TokenWords.startsWith(words, "CREATE", "FUNCTION")
                || TokenWords.startsWith(words, "CREATE", "PROCEDURE")
                || TokenWords.startsWith(words, "CREATE", "OR", "REPLACE", "FUNCTION")
                || TokenWords.startsWith(words, "CREATE", "OR", "REPLACE", "PROCEDURE")
                || TokenWords.startsWith(words, "ALTER", "FUNCTION")
                || TokenWords.startsWith(words, "ALTER", "PROCEDURE");
    }

    /**
     * Returns whether the statement is a {@code CREATE INDEX} whose access method
     * is one of PostgreSQL's non-portable index methods. The method must be the
     * token immediately after {@code USING}, so an identifier named {@code gin}
     * elsewhere in the statement (or a portable {@code USING btree (gin)} index)
     * does not trigger the rule.
     */
    private static boolean usesPostgresIndexMethod(List<Token> words) {
        if (!TokenWords.startsWith(words, "CREATE")) {
            return false;
        }
        int index = 1;
        if (index < words.size() && words.get(index).matchesKeyword("UNIQUE")) {
            index++;
        }
        if (index >= words.size() || !words.get(index).matchesKeyword("INDEX")) {
            return false;
        }
        int using = TokenWords.indexOf(words, "USING", index + 1);
        if (using < 0 || using + 1 >= words.size()) {
            return false;
        }
        String method = RuleSupport.identifier(words.get(using + 1));
        return method != null && POSTGRES_INDEX_METHODS.contains(method);
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
        if (!RuleSupport.appliesToPostgres(context.changeSet()) || gatedOnPostgres(context.changeSet())) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        collect(context.forward(), violations);
        collect(context.rollback(), violations);
        return List.copyOf(violations);
    }

    private void collect(List<SqlUnit> units, List<Violation> violations) {
        for (SqlUnit unit : units) {
            if (postgresOnly(unit.source().dbms()) || !RuleSupport.appliesToPostgres(unit)) {
                continue;
            }
            LocatedConstruct construct = firstPostgresConstruct(unit);
            if (construct == null) {
                continue;
            }
            violations.add(new Violation(
                    construct.label(),
                    "changeset uses PostgreSQL-only syntax (" + construct.label()
                            + ") without dbms=\"postgresql\"; on another database it fails at deploy",
                    "Add dbms=\"postgresql\" to the changeset, or make the SQL portable.",
                    unit.file(), construct.token().line(), construct.token().column()));
        }
    }

    /**
     * A construct label and the first token of the offending statement.
     */
    private record LocatedConstruct(String label, Token token) {
    }
}
