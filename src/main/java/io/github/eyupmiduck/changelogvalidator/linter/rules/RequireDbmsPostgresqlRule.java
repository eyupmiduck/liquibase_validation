package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenType;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatement;

import java.util.ArrayList;
import java.util.List;
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
 * PostgreSQL casts and operators ({@code ::}, {@code ->}, {@code ?}, {@code @>},
 * ...) that are common in {@code <sql>} bodies.
 *
 * <p>A changeset is accepted when it sets {@code dbms} to a list that includes
 * {@code postgresql} (case-insensitive, comma-separated), or when every SQL
 * source sets the same (Liquibase applies a source-level {@code dbms} too). The
 * rule is an error and on by default: a PostgreSQL-only changelog without the
 * gate is a deployment hazard, not a style nit.
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

    // Operators and punctuation that only PostgreSQL (among common targets)
    // accepts in these positions. Matching is token-based, so a keyword inside
    // a string or comment cannot trigger it.
    private static final Pattern POSTGRES_OPERATORS = Pattern.compile("::|->>|->|#>>|#>|@>|<@|\\?\\||\\?&|\\?");

    private static boolean gatedOnPostgres(ChangeSet changeSet) {
        return listsPostgres(changeSet.dbms());
    }

    private static boolean listsPostgres(String dbms) {
        if (dbms == null || dbms.isBlank()) {
            return false;
        }
        for (String candidate : dbms.split(",")) {
            if (candidate.strip().equalsIgnoreCase("postgresql")) {
                return true;
            }
        }
        return false;
    }

    private static String firstPostgresConstruct(SqlUnit unit) {
        // Filter the unit's tokens once, then walk the ordered, non-overlapping
        // statements with a two-pointer scan instead of re-streaming per
        // statement.
        List<Token> words = unit.tokens().stream()
                .filter(token -> !token.isTrivia())
                .toList();
        int from = 0;
        for (SqlStatement statement : unit.statements()) {
            while (from < words.size() && words.get(from).endOffset() <= statement.startOffset()) {
                from++;
            }
            int to = from;
            while (to < words.size() && words.get(to).endOffset() <= statement.endOffset()) {
                to++;
            }
            String construct = statementConstruct(words.subList(from, to));
            if (construct != null) {
                return construct;
            }
        }
        for (Token token : words) {
            if (token.type() == TokenType.OPERATOR
                    && POSTGRES_OPERATORS.matcher(token.text()).matches()) {
                return "the " + token.text() + " operator";
            }
        }
        return null;
    }

    private static String statementConstruct(List<Token> words) {
        if (startsWith(words, "CREATE", "DOMAIN")) {
            return "CREATE DOMAIN";
        }
        if (startsWith(words, "CREATE", "EXTENSION")) {
            return "CREATE EXTENSION";
        }
        // CONCURRENTLY only counts in a PostgreSQL-only position, so an ordinary
        // identifier named "concurrently" does not trigger the rule.
        if (startsWith(words, "CREATE") && contains(words, "INDEX") && nextWordIs(words, "INDEX", "CONCURRENTLY")) {
            return "CONCURRENTLY";
        }
        if (startsWith(words, "DROP", "INDEX") && nextWordIs(words, "INDEX", "CONCURRENTLY")) {
            return "CONCURRENTLY";
        }
        if (startsWith(words, "REINDEX") && contains(words, "CONCURRENTLY")) {
            return "CONCURRENTLY";
        }
        if (contains(words, "LANGUAGE") && nextWordIs(words, "LANGUAGE", "PLPGSQL")) {
            return "LANGUAGE plpgsql";
        }
        if (contains(words, "USING") && containsAny(words, "GIN", "GIST", "BRIN", "SPGIST")) {
            return "a PostgreSQL index method";
        }
        return null;
    }

    private static boolean startsWith(List<Token> words, String... keywords) {
        if (words.size() < keywords.length) {
            return false;
        }
        for (int i = 0; i < keywords.length; i++) {
            if (!words.get(i).matchesKeyword(keywords[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(List<Token> words, String keyword) {
        return words.stream().anyMatch(token -> token.matchesKeyword(keyword));
    }

    private static boolean containsAny(List<Token> words, String... keywords) {
        for (String keyword : keywords) {
            if (contains(words, keyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean nextWordIs(List<Token> words, String keyword, String next) {
        for (int i = 0; i + 1 < words.size(); i++) {
            if (words.get(i).matchesKeyword(keyword) && words.get(i + 1).matchesKeyword(next)) {
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
        if (gatedOnPostgres(context.changeSet())) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        collect(context.forward(), violations);
        collect(context.rollback(), violations);
        return List.copyOf(violations);
    }

    private void collect(List<SqlUnit> units, List<Violation> violations) {
        for (SqlUnit unit : units) {
            if (listsPostgres(unit.source().dbms())) {
                continue;
            }
            String construct = firstPostgresConstruct(unit);
            if (construct == null) {
                continue;
            }
            violations.add(new Violation(
                    construct,
                    "changeset uses PostgreSQL-only syntax (" + construct
                            + ") without dbms=\"postgresql\"; on another database it fails at deploy",
                    "Add dbms=\"postgresql\" to the changeset, or make the SQL portable.",
                    unit.file(), 1, 1));
        }
    }
}
