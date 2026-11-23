package io.github.eyupmiduck.changelogvalidator;

import io.github.eyupmiduck.changelogvalidator.linter.lexer.SqlLexer;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Checks that every file in a routine or trigger directory declares exactly the
 * object its path names, and that the matching rollback file drops it.
 *
 * <p>This is the directory-oriented companion to {@link ChangelogLayoutCheck}:
 * that check joins a changeset id to its files, while this one walks the
 * {@code functions/}, {@code procedures/} and {@code triggers/} trees directly.
 * It therefore also catches a file that no changeset references, and a rollback
 * file that has no forward file, without trusting the changelog graph.
 *
 * <p>The conventions are:
 * <ul>
 *   <li>{@code functions/<schema>/<name>.sql} declares exactly one function
 *       {@code <schema>.<name>};</li>
 *   <li>{@code procedures/<schema>/<name>.sql} declares exactly one procedure
 *       {@code <schema>.<name>};</li>
 *   <li>{@code triggers/<schema>/<name>.sql} declares exactly one trigger
 *       {@code <name>} on a table in {@code <schema>};</li>
 *   <li>{@code <kind>-rollback/<schema>/<name>-rollback.sql} drops that object;
 *       a routine rollback may drop several overloads, but every drop must name
 *       the same object.</li>
 * </ul>
 *
 * <p>A {@code DO $$ ... $$} wrapper around a {@code DROP TRIGGER} is understood,
 * because a trigger rollback often guards the table first. Only {@code .sql}
 * files are inspected, so a directory README or a {@code .gitkeep} is ignored.
 */
public final class RoutineFileCheck {

    private static final List<Kind> KINDS = List.of(
            new Kind("functions", "functions-rollback", "function"),
            new Kind("procedures", "procedures-rollback", "procedure"),
            new Kind("triggers", "triggers-rollback", "trigger"));

    private static final String ROLLBACK_SUFFIX = "-rollback";

    private RoutineFileCheck() {
    }

    /**
     * Finds routine-file violations under a changes directory.
     *
     * @param changesRoot the changes directory holding the routine and trigger
     *                    trees
     * @return the violations, sorted deterministically
     * @throws IOException if a file cannot be read
     */
    public static List<Violation> findViolations(Path changesRoot) throws IOException {
        Objects.requireNonNull(changesRoot, "changesRoot");
        Path root = changesRoot.toAbsolutePath().normalize();
        List<Violation> violations = new ArrayList<>();
        for (Kind kind : KINDS) {
            Map<String, Path> forward = sqlFiles(root.resolve(kind.forwardDirectory()));
            Map<String, Path> rollback = sqlFiles(root.resolve(kind.rollbackDirectory()));
            for (Map.Entry<String, Path> entry : forward.entrySet()) {
                String relative = entry.getKey();
                Path file = entry.getValue();
                if (!checkPathShape(relative, file, kind, violations)) {
                    continue;
                }
                String[] parts = relative.split("/", 2);
                String schema = parts[0];
                String name = parts[1];
                checkForward(kind, schema, name, file, violations);
                Path rollbackFile = rollback.get(relative + ROLLBACK_SUFFIX);
                if (rollbackFile == null) {
                    violations.add(new Violation(file, 1,
                            "has no matching rollback file; expected "
                                    + kind.rollbackDirectory() + "/" + relative + ROLLBACK_SUFFIX + ".sql"));
                } else {
                    checkRollback(kind, schema, name, rollbackFile, violations);
                }
            }
            for (Map.Entry<String, Path> entry : rollback.entrySet()) {
                String relative = entry.getKey();
                if (!relative.endsWith(ROLLBACK_SUFFIX)) {
                    violations.add(new Violation(entry.getValue(), 1,
                            "rollback file name does not end with " + ROLLBACK_SUFFIX + ".sql"));
                    continue;
                }
                String forwardKey = relative.substring(0, relative.length() - ROLLBACK_SUFFIX.length());
                if (!forward.containsKey(forwardKey)) {
                    violations.add(new Violation(entry.getValue(), 1,
                            "rollback file has no matching forward file; expected "
                                    + kind.forwardDirectory() + "/" + forwardKey + ".sql"));
                }
            }
        }
        violations.sort(Comparator.comparing((Violation violation) -> violation.file().toString())
                .thenComparingInt(Violation::line)
                .thenComparing(Violation::message));
        return List.copyOf(violations);
    }

    private static boolean checkPathShape(String relative, Path file, Kind kind, List<Violation> violations) {
        if (relative.split("/").length != 2) {
            violations.add(new Violation(file, 1,
                    "must be directly under a schema directory, as "
                            + kind.forwardDirectory() + "/<schema>/<name>.sql"));
            return false;
        }
        return true;
    }

    private static void checkForward(Kind kind, String schema, String name, Path file,
                                     List<Violation> violations) {
        List<ChangelogLayoutCheck.Declaration> declarations;
        try {
            declarations = ChangelogLayoutCheck.declarations(file);
        } catch (IOException e) {
            violations.add(new Violation(file, 1, "cannot analyse the SQL file: " + e.getMessage()));
            return;
        }
        if (declarations.isEmpty()) {
            violations.add(new Violation(file, 1, "does not declare a " + kind.objectKind()));
            return;
        }
        if (declarations.size() > 1) {
            violations.add(new Violation(file, declarations.get(1).line(),
                    "declares " + declarations.size() + " stored objects; expected exactly one"));
            return;
        }
        ChangelogLayoutCheck.Declaration declaration = declarations.getFirst();
        if (!declaration.kind().equals(kind.objectKind())) {
            violations.add(new Violation(file, declaration.line(),
                    "declares a " + declaration.kind() + " but the directory expects a "
                            + kind.objectKind()));
            return;
        }
        if (kind.objectKind().equals("trigger")) {
            if (!declaration.name().equalsIgnoreCase(name)) {
                violations.add(new Violation(file, declaration.line(),
                        "declares trigger " + declaration.name() + " but the path expects " + name));
            }
            if (declaration.tableSchema() == null) {
                violations.add(new Violation(file, declaration.line(),
                        "trigger's table is not schema-qualified; expected " + schema));
            } else if (!declaration.tableSchema().equalsIgnoreCase(schema)) {
                violations.add(new Violation(file, declaration.line(),
                        "trigger is on schema " + declaration.tableSchema()
                                + " but the path expects " + schema));
            }
        } else if (declaration.schema() == null) {
            violations.add(new Violation(file, declaration.line(),
                    "routine is not schema-qualified; expected " + schema + "." + name));
        } else if (!declaration.schema().equalsIgnoreCase(schema)
                || !declaration.name().equalsIgnoreCase(name)) {
            violations.add(new Violation(file, declaration.line(),
                    "declares " + declaration.schema() + "." + declaration.name()
                            + " but the path expects " + schema + "." + name));
        }
    }

    private static void checkRollback(Kind kind, String schema, String name, Path file,
                                      List<Violation> violations) {
        List<Drop> drops;
        try {
            drops = drops(Files.readString(file));
        } catch (IOException e) {
            violations.add(new Violation(file, 1, "cannot analyse the SQL file: " + e.getMessage()));
            return;
        }
        if (drops.isEmpty()) {
            violations.add(new Violation(file, 1, "does not drop a " + kind.objectKind()));
            return;
        }
        for (Drop drop : drops) {
            if (drop.name() == null) {
                violations.add(new Violation(file, drop.line(),
                        "DROP " + drop.kind() + " does not name an object"));
                continue;
            }
            if (!drop.kind().equals(kind.objectKind())) {
                violations.add(new Violation(file, drop.line(),
                        "drops a " + drop.kind() + " but the directory expects a " + kind.objectKind()));
                continue;
            }
            if (kind.objectKind().equals("trigger")) {
                if (!drop.name().equalsIgnoreCase(name)) {
                    violations.add(new Violation(file, drop.line(),
                            "drops trigger " + drop.name() + " but the path expects " + name));
                }
                if (drop.tableSchema() == null) {
                    violations.add(new Violation(file, drop.line(),
                            "DROP TRIGGER's table is not schema-qualified; expected " + schema));
                } else if (!drop.tableSchema().equalsIgnoreCase(schema)) {
                    violations.add(new Violation(file, drop.line(),
                            "drops trigger on schema " + drop.tableSchema()
                                    + " but the path expects " + schema));
                }
            } else if (drop.schema() == null) {
                violations.add(new Violation(file, drop.line(),
                        "DROP " + drop.kind() + " is not schema-qualified; expected "
                                + schema + "." + name));
            } else if (!drop.schema().equalsIgnoreCase(schema)
                    || !drop.name().equalsIgnoreCase(name)) {
                violations.add(new Violation(file, drop.line(),
                        "drops " + drop.schema() + "." + drop.name()
                                + " but the path expects " + schema + "." + name));
            }
        }
    }

    private static Map<String, Path> sqlFiles(Path directory) throws IOException {
        Map<String, Path> files = new TreeMap<>();
        if (!Files.isDirectory(directory)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .toList()) {
                String relative = directory.relativize(file).toString().replace(File.separatorChar, '/');
                files.put(relative.substring(0, relative.length() - ".sql".length()), file);
            }
        }
        return files;
    }

    /**
     * Returns the {@code DROP} statements in a rollback file. A dollar-quoted
     * body is expanded so a guarded {@code DO $$ ... DROP TRIGGER ... $$} is
     * seen, while an ordinary string literal stays opaque.
     */
    private static List<Drop> drops(String sql) {
        List<Drop> drops = new ArrayList<>();
        List<Token> tokens = flattenDollarStrings(SqlLexer.tokenize(sql), 0).stream()
                .filter(token -> !token.isTrivia())
                .toList();
        for (int i = 0; i < tokens.size(); i++) {
            if (!tokens.get(i).matchesKeyword("drop")) {
                continue;
            }
            int cursor = i + 1;
            String kind = word(tokens, cursor, "function") ? "function"
                    : word(tokens, cursor, "procedure") ? "procedure"
                    : word(tokens, cursor, "trigger") ? "trigger" : null;
            if (kind == null) {
                continue;
            }
            cursor++;
            if (word(tokens, cursor, "if") && word(tokens, cursor + 1, "exists")) {
                cursor += 2;
            }
            ParsedName name = qualifiedName(tokens, cursor);
            int line = tokens.get(i).line();
            if (name == null) {
                drops.add(new Drop(kind, null, null, null, line));
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
            drops.add(new Drop(kind, name.schema(), name.name(), tableSchema, line));
        }
        return drops;
    }

    private static List<Token> flattenDollarStrings(List<Token> tokens, int depth) {
        if (depth > 4) {
            return tokens;
        }
        List<Token> flat = new ArrayList<>();
        for (Token token : tokens) {
            if (token.type() != TokenType.DOLLAR_STRING) {
                flat.add(token);
                continue;
            }
            String text = token.text();
            int delimiter = text.indexOf('$', 1) + 1;
            if (delimiter < 2 || delimiter >= text.length()) {
                flat.add(token);
                continue;
            }
            String body = text.substring(delimiter, text.length() - delimiter);
            flat.addAll(flattenDollarStrings(
                    SqlLexer.tokenize(body, token.startOffset() + delimiter,
                            token.line(), token.column() + delimiter),
                    depth + 1));
        }
        return flat;
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

    private record Kind(String forwardDirectory, String rollbackDirectory, String objectKind) {
    }

    private record ParsedName(String schema, String name, int next) {
    }

    private record Drop(String kind, String schema, String name, String tableSchema, int line) {
    }

    /**
     * A routine-file violation.
     *
     * @param file    the offending SQL file
     * @param line    the one-based line in the file
     * @param message what is wrong
     */
    public record Violation(Path file, int line, String message) {

        /**
         * Validates the required components.
         */
        public Violation {
            Objects.requireNonNull(file, "file");
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
            return file + ":" + line + ": " + message;
        }
    }
}
