package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.ProjectAnalysisRule;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenType;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenWords;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports an object commented more than once in the forward changelog graph,
 * either with the same text (a duplicate) or with different text (a conflict).
 *
 * <p>A later {@code COMMENT ON} replaces the earlier one, so two comments on the
 * same object are at best redundant and at worst a silent conflict. The rule
 * keys the comment by the kind and the written object (including a routine's
 * argument list), and treats {@code IS NULL} as a removal. It is an opt-in
 * warning; a comment in a routine body or rollback is not examined.
 */
public final class ConflictingCommentRule implements ProjectAnalysisRule {

    /**
     * The rule id.
     */
    public static final String ID = "project-conflicting-comment";

    private static int indexOf(List<Token> tokens, String keyword, int from) {
        int depth = 0;
        for (int i = from; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (isPunctuation(token, "(")) {
                depth++;
            } else if (isPunctuation(token, ")")) {
                depth--;
            } else if (depth == 0 && token.matchesKeyword(keyword)) {
                return i;
            }
        }
        return -1;
    }

    private static String key(List<Token> tokens, int is) {
        if (tokens.size() < 3 || tokens.get(2).type() != TokenType.WORD) {
            return null;
        }
        StringBuilder key = new StringBuilder(tokens.get(2).text());
        for (int i = 3; i < is; i++) {
            key.append(' ').append(tokens.get(i).text());
        }
        return key.toString();
    }

    private static boolean isPunctuation(Token token, String text) {
        return token.type() == TokenType.PUNCTUATION && token.text().equals(text);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.WARNING;
    }

    @Override
    public List<Violation> check(ProjectContext context) {
        Map<String, Comment> comments = new LinkedHashMap<>();
        List<Violation> violations = new ArrayList<>();
        for (ProjectStatements.Statement statement : ProjectStatements.forward(context)) {
            List<Token> tokens = statement.tokens();
            if (!TokenWords.startsWith(tokens, "COMMENT", "ON")) {
                continue;
            }
            int is = indexOf(tokens, "IS", 3);
            if (is < 0) {
                continue;
            }
            String key = key(tokens, is);
            if (key == null || is + 1 >= tokens.size()) {
                continue;
            }
            Token value = tokens.get(is + 1);
            if (value.matchesKeyword("NULL")) {
                comments.remove(key);
                continue;
            }
            if (!value.isStringLiteral()) {
                continue;
            }
            String text = value.text();
            Comment first = comments.get(key);
            if (first != null) {
                String message = first.text().equals(text)
                        ? "object is commented twice with the same text"
                        : "object is commented twice with different text";
                violations.add(new Violation(statement.changeSet(), statement.unit().file(),
                        value.line(), value.column(), "COMMENT ON",
                        message + "; first comment at " + first.file().getFileName() + ":" + first.line(),
                        "Keep one comment per object; update the existing COMMENT ON instead of adding another."));
            }
            comments.put(key, new Comment(statement.changeSet(), statement.unit().file(),
                    value.line(), value.column(), text));
        }
        return List.copyOf(violations);
    }

    @Override
    public String description() {
        return "an object commented twice, with the same or different text";
    }

    @Override
    public String help() {
        return "Keep one `COMMENT ON` for the object.";
    }

    private record Comment(ChangeSet changeSet, Path file, int line, int column, String text) {
    }
}
