package io.github.eyupmiduck.changelogvalidator;

import io.github.eyupmiduck.changelogvalidator.linter.lexer.SqlLexer;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenType;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangelogModel;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that a changelog keeps the in-place file layout its changeset ids
 * promise: a numbered changeset's SQL file is named after the changeset, a
 * stored-object changeset's file lives in the matching routine or trigger
 * directory, every file-based changeset has the matching rollback file, and a
 * routine or trigger file declares the object its path names.
 *
 * <p>The check is stricter than {@link ChangelogValidator}: that class validates
 * names and references, while this one joins a changeset id to its forward and
 * rollback files and, for a routine or trigger, to the declaration inside the
 * file. It is meant for a project whose changelog is a living definition, where
 * a file rename or a routine moved to a different schema must be reflected in
 * the changeset id too.
 *
 * <p>The conventions are:
 * <ul>
 *   <li>a {@code NNN-name} changeset uses
 *       {@code sql_changes/NNN-name.sql} and
 *       {@code rollback/NNN-name-rollback.sql};</li>
 *   <li>a {@code function-<schema>.<name>} changeset uses
 *       {@code functions/<schema>/<name>.sql} and
 *       {@code functions-rollback/<schema>/<name>-rollback.sql};</li>
 *   <li>a {@code procedure-<schema>.<name>} changeset uses
 *       {@code procedures/<schema>/<name>.sql} and
 *       {@code procedures-rollback/<schema>/<name>-rollback.sql};</li>
 *   <li>a {@code trigger-<schema>.<name>} changeset uses
 *       {@code triggers/<schema>/<name>.sql} and
 *       {@code triggers-rollback/<schema>/<name>-rollback.sql};</li>
 *   <li>a routine or trigger file declares exactly one object, with the
 *       qualified name (or, for a trigger, the trigger name and on-table
 *       schema) the path names.</li>
 * </ul>
 *
 * <p>Paths are matched by suffix, so a project may put the {@code changes/}
 * directory under any changelog root. A changeset id that matches neither shape
 * is left to {@link ChangelogValidator}'s naming check. A structured change or
 * an inline SQL body has no file to join, so it is skipped.
 */
public final class ChangelogLayoutCheck {

    private static final Pattern OBJECT_ID =
            Pattern.compile("^(function|procedure|trigger)-([A-Za-z_][A-Za-z0-9_]*)\\."
                    + "([A-Za-z_][A-Za-z0-9_]*)$");

    private ChangelogLayoutCheck() {
    }

    /**
     * Finds layout violations, requiring a rollback file for every file-based
     * changeset.
     *
     * @param changelogRoot   the changelog root directory
     * @param masterChangelog the master changelog file
     * @return the violations, sorted deterministically
     * @throws IOException if the changelog graph or a SQL file cannot be read
     */
    public static List<Violation> findViolations(Path changelogRoot, Path masterChangelog) throws IOException {
        return findViolations(changelogRoot, masterChangelog, true);
    }

    /**
     * Finds layout violations.
     *
     * @param changelogRoot    the changelog root directory
     * @param masterChangelog  the master changelog file
     * @param rollbackRequired whether a file-based changeset must have a rollback
     *                         file; a rollback file that is present is still
     *                         checked when this is {@code false}
     * @return the violations, sorted deterministically
     * @throws IOException if the changelog graph or a SQL file cannot be read
     */
    public static List<Violation> findViolations(Path changelogRoot, Path masterChangelog,
                                                 boolean rollbackRequired) throws IOException {
        Path root = changelogRoot.toAbsolutePath().normalize();
        List<ChangeSet> changeSets = ChangelogModel.changesets(root, masterChangelog);
        List<Violation> violations = new ArrayList<>();
        Map<Path, String> firstReference = new LinkedHashMap<>();

        for (ChangeSet changeSet : changeSets) {
            String id = changeSet.id();
            Matcher objectId = OBJECT_ID.matcher(id);
            boolean numbered = ChangelogNaming.isNumbered(id);
            if (!numbered && !objectId.matches()) {
                // The naming check owns an unrecognised id.
                continue;
            }
            List<SqlSource> forwardFiles = fileSources(changeSet.sqlSources());
            List<SqlSource> rollbackFiles = fileSources(changeSet.rollbackSources());

            for (SqlSource source : forwardFiles) {
                checkDuplicate(root, changeSet, source, firstReference, violations);
                if (numbered) {
                    checkNumberedForward(root, changeSet, source, violations);
                } else {
                    checkObjectForward(root, changeSet, objectId, source, violations);
                }
            }
            for (SqlSource source : rollbackFiles) {
                checkDuplicate(root, changeSet, source, firstReference, violations);
                if (numbered) {
                    checkNumberedRollback(root, changeSet, source, violations);
                } else {
                    checkObjectRollback(root, changeSet, objectId, source, violations);
                }
            }

            if (rollbackRequired && !forwardFiles.isEmpty() && rollbackFiles.isEmpty()) {
                violations.add(violation(root, changeSet, null, changeSet.line(),
                        "file-based changeset has no matching rollback SQL file"));
            }
            if (forwardFiles.isEmpty() && !rollbackFiles.isEmpty()) {
                violations.add(violation(root, changeSet, null, changeSet.line(),
                        "rollback SQL file has no forward SQL file"));
            }
        }

        violations.sort(Comparator.comparing(Violation::changelogFile)
                .thenComparing(Violation::changeSetId)
                .thenComparing(violation -> violation.sqlFile() == null ? "" : violation.sqlFile().toString())
                .thenComparingInt(Violation::line)
                .thenComparing(Violation::message));
        return List.copyOf(violations);
    }

    private static List<SqlSource> fileSources(List<SqlSource> sources) {
        return sources.stream().filter(source -> source.path() != null).toList();
    }

    private static void checkDuplicate(Path root, ChangeSet changeSet, SqlSource source,
                                       Map<Path, String> firstReference, List<Violation> violations) {
        Path file = source.path().toAbsolutePath().normalize();
        String reference = changeSet.id() + " (" + relative(root, changeSet.changelogFile())
                + ":" + changeSet.line() + ")";
        String first = firstReference.putIfAbsent(file, reference);
        if (first != null) {
            violations.add(violation(root, changeSet, file, source.line(),
                    "SQL file is referenced more than once; first by " + first));
        }
    }

    private static void checkNumberedForward(Path root, ChangeSet changeSet, SqlSource source,
                                             List<Violation> violations) {
        String expected = ChangelogNaming.numberedForwardPath(changeSet.id());
        checkSuffix(root, changeSet, source, expected, "forward SQL file", violations);
    }

    private static void checkNumberedRollback(Path root, ChangeSet changeSet, SqlSource source,
                                              List<Violation> violations) {
        String expected = ChangelogNaming.numberedRollbackPath(changeSet.id());
        checkSuffix(root, changeSet, source, expected, "rollback SQL file", violations);
    }

    private static void checkObjectForward(Path root, ChangeSet changeSet, Matcher objectId,
                                           SqlSource source, List<Violation> violations) {
        String directory = objectDirectory(objectId.group(1));
        String expected = directory + "/" + objectId.group(2) + "/" + objectId.group(3) + ".sql";
        if (checkSuffix(root, changeSet, source, expected, "forward SQL file", violations)) {
            checkDeclaration(source.path(), objectId.group(1), objectId.group(2), objectId.group(3),
                    changeSet, root, violations);
        }
    }

    private static void checkObjectRollback(Path root, ChangeSet changeSet, Matcher objectId,
                                            SqlSource source, List<Violation> violations) {
        String directory = objectDirectory(objectId.group(1)) + "-rollback";
        String expected = directory + "/" + objectId.group(2) + "/" + objectId.group(3) + "-rollback.sql";
        checkSuffix(root, changeSet, source, expected, "rollback SQL file", violations);
    }

    private static String objectDirectory(String objectKind) {
        return switch (objectKind) {
            case "function" -> "functions";
            case "procedure" -> "procedures";
            case "trigger" -> "triggers";
            default -> throw new IllegalArgumentException("unsupported object kind: " + objectKind);
        };
    }

    private static boolean checkSuffix(Path root, ChangeSet changeSet, SqlSource source,
                                       String expectedSuffix, String description,
                                       List<Violation> violations) {
        String relative = relative(root, source.path());
        String actual = relative.toLowerCase(Locale.ROOT);
        String expected = expectedSuffix.toLowerCase(Locale.ROOT);
        // Anchor the suffix at a directory boundary: a sibling directory whose
        // name merely ends with the conventional one must not pass.
        if (!actual.equals(expected) && !actual.endsWith("/" + expected)) {
            violations.add(violation(root, changeSet, source.path(), source.line(),
                    description + " path " + relative + " does not match " + expectedSuffix));
            return false;
        }
        return true;
    }

    private static void checkDeclaration(Path file, String objectKind, String expectedSchema,
                                         String expectedName, ChangeSet changeSet, Path root,
                                         List<Violation> violations) {
        List<Declaration> declarations;
        try {
            declarations = declarations(file);
        } catch (IOException e) {
            violations.add(violation(root, changeSet, file, 1,
                    "cannot analyse the SQL file: " + e.getMessage()));
            return;
        }
        if (declarations.isEmpty()) {
            violations.add(violation(root, changeSet, file, 1,
                    "file does not declare a " + objectKind));
            return;
        }
        if (declarations.size() > 1) {
            violations.add(violation(root, changeSet, file, declarations.get(1).line(),
                    "file declares " + declarations.size() + " stored objects; expected exactly one"));
            return;
        }
        Declaration declaration = declarations.getFirst();
        if (!declaration.kind().equals(objectKind)) {
            violations.add(violation(root, changeSet, file, declaration.line(),
                    "file declares a " + declaration.kind() + " but the path expects a " + objectKind));
            return;
        }
        if (objectKind.equals("trigger")) {
            if (!declaration.name().equalsIgnoreCase(expectedName)) {
                violations.add(violation(root, changeSet, file, declaration.line(),
                        "file declares trigger " + declaration.name()
                                + " but the path expects " + expectedName));
            }
            if (declaration.tableSchema() == null) {
                violations.add(violation(root, changeSet, file, declaration.line(),
                        "trigger's table is not schema-qualified; expected " + expectedSchema));
            } else if (!declaration.tableSchema().equalsIgnoreCase(expectedSchema)) {
                violations.add(violation(root, changeSet, file, declaration.line(),
                        "trigger is on schema " + declaration.tableSchema()
                                + " but the path expects " + expectedSchema));
            }
        } else {
            if (declaration.schema() == null) {
                violations.add(violation(root, changeSet, file, declaration.line(),
                        "routine is not schema-qualified; expected "
                                + expectedSchema + "." + expectedName));
            } else if (!declaration.schema().equalsIgnoreCase(expectedSchema)
                    || !declaration.name().equalsIgnoreCase(expectedName)) {
                violations.add(violation(root, changeSet, file, declaration.line(),
                        "file declares " + declaration.schema() + "." + declaration.name()
                                + " but the path expects " + expectedSchema + "." + expectedName));
            }
        }
    }

    static List<Declaration> declarations(Path file) throws IOException {
        List<Token> tokens = SqlLexer.tokenize(Files.readString(file)).stream()
                .filter(token -> !token.isTrivia())
                .toList();
        if (tokens.stream().anyMatch(token -> token.type().isError())) {
            throw new IOException("SQL contains input the lexer cannot classify");
        }
        List<Declaration> declarations = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (!tokens.get(i).matchesKeyword("create")) {
                continue;
            }
            int cursor = i + 1;
            if (word(tokens, cursor, "or") && word(tokens, cursor + 1, "replace")) {
                cursor += 2;
            }
            if (word(tokens, cursor, "constraint") && word(tokens, cursor + 1, "trigger")) {
                cursor++;
            }
            String kind = word(tokens, cursor, "function") ? "function"
                    : word(tokens, cursor, "procedure") ? "procedure"
                    : word(tokens, cursor, "trigger") ? "trigger" : null;
            if (kind == null) {
                continue;
            }
            ParsedName name = qualifiedName(tokens, cursor + 1);
            if (name == null) {
                continue;
            }
            String tableSchema = null;
            if (kind.equals("trigger")) {
                int on = findWord(tokens, name.next(), "on");
                if (on >= 0) {
                    ParsedName table = qualifiedName(tokens, on + 1);
                    if (table != null) {
                        tableSchema = table.schema();
                    }
                }
            }
            declarations.add(new Declaration(kind, name.schema(), name.name(), tableSchema,
                    tokens.get(i).line()));
            i = name.next() - 1;
        }
        return declarations;
    }

    private static ParsedName qualifiedName(List<Token> tokens, int start) {
        if (!isIdentifier(tokens, start)) {
            return null;
        }
        String first = identifier(tokens.get(start));
        int next = start + 1;
        if (isPunctuation(tokens, next, ".") && isIdentifier(tokens, next + 1)) {
            return new ParsedName(first, identifier(tokens.get(next + 1)), next + 2);
        }
        return new ParsedName(null, first, next);
    }

    private static boolean isIdentifier(List<Token> tokens, int index) {
        return index < tokens.size()
                && (tokens.get(index).type() == TokenType.WORD
                || tokens.get(index).type() == TokenType.QUOTED_IDENTIFIER);
    }

    private static String identifier(Token token) {
        if (token.type() == TokenType.QUOTED_IDENTIFIER) {
            return token.identifier();
        }
        return token.text();
    }

    private static boolean word(List<Token> tokens, int index, String keyword) {
        return index < tokens.size() && tokens.get(index).matchesKeyword(keyword);
    }

    private static boolean isPunctuation(List<Token> tokens, int index, String punctuation) {
        return index < tokens.size() && tokens.get(index).type() == TokenType.PUNCTUATION
                && punctuation.equals(tokens.get(index).text());
    }

    private static int findWord(List<Token> tokens, int start, String keyword) {
        for (int i = start; i < tokens.size(); i++) {
            if (tokens.get(i).matchesKeyword(keyword)) {
                return i;
            }
        }
        return -1;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString()
                .replace(File.separatorChar, '/');
    }

    private static Violation violation(Path root, ChangeSet changeSet, Path sqlFile, int line, String message) {
        return new Violation(relativePath(root, changeSet.changelogFile()),
                changeSet.id(), sqlFile == null ? null : relativePath(root, sqlFile), line, message);
    }

    private static Path relativePath(Path root, Path file) {
        return Path.of(relative(root, file));
    }

    /**
     * A layout violation.
     *
     * @param changelogFile the changelog file, relative to the changelog root
     * @param changeSetId   the changeset id
     * @param sqlFile       the offending SQL file, relative to the changelog
     *                      root, or {@code null} when no file is involved
     * @param line          the one-based line in the changelog or SQL file
     * @param message       what is wrong
     */
    public record Violation(Path changelogFile, String changeSetId, Path sqlFile, int line, String message) {

        /**
         * Validates the required components.
         */
        public Violation {
            Objects.requireNonNull(changelogFile, "changelogFile");
            Objects.requireNonNull(changeSetId, "changeSetId");
            Objects.requireNonNull(message, "message");
            if (line < 1) {
                throw new IllegalArgumentException("line must be one-based, was " + line);
            }
        }

        /**
         * Formats the violation for a test failure.
         *
         * @return a one-line description
         */
        public String describe() {
            String location = sqlFile == null ? changelogFile.toString() : sqlFile.toString();
            return location + ":" + line + " [" + changeSetId + "]: " + message;
        }
    }

    private record ParsedName(String schema, String name, int next) {
    }

    record Declaration(String kind, String schema, String name, String tableSchema, int line) {
    }
}
