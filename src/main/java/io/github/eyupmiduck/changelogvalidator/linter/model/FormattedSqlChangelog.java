package io.github.eyupmiduck.changelogvalidator.linter.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Parses a Liquibase formatted SQL changelog into the same {@link ChangeSet}
 * model the XML reader produces.
 *
 * <p>The file must begin (after blank lines) with
 * {@code --liquibase formatted sql}. Each changeset starts with
 * {@code --changeset author:id attribute:value ...}; the SQL that follows is the
 * forward source, and {@code --rollback} lines form the rollback source.
 *
 * <p>Supported changeset attributes are {@code splitStatements},
 * {@code endDelimiter}, {@code stripComments}, {@code runInTransaction},
 * {@code runOnChange}, {@code dbms}, {@code context}, {@code labels},
 * {@code rollbackEndDelimiter}, {@code rollbackSplitStatements},
 * {@code rollbackStripComments} and {@code ignore}. {@code runAlways},
 * {@code failOnError}, {@code logicalFilePath} and {@code runWithSpoolFile} are
 * accepted and do not affect the SQL the linter analyses. {@code runWith} is
 * accepted only for {@code jdbc}; a native executor changes splitting semantics
 * and is rejected. An unknown attribute is rejected so a new semantic cannot be
 * ignored silently.
 *
 * <p>Supported directives are {@code --liquibase}, {@code --changeset},
 * {@code --rollback}, {@code --comment}, {@code --preconditions},
 * {@code --precondition-sql-check}, {@code --property} and
 * {@code --validCheckSum}. {@code --ignoreLines}, {@code --include},
 * {@code --includeAll} and {@code --rollbackSqlFile} are rejected with a clear
 * diagnostic because they change the SQL text, the changelog graph or the
 * rollback file set. Any other {@code --} line is an ordinary SQL comment.
 * {@code --property} is accepted but does not contribute to SQL substitution;
 * only XML {@code <property>} values are captured (see ADR 0003).
 *
 * <p>This parser is for a SQL file reached through {@code <include>} or used as
 * the master. A SQL file found by {@code <includeAll>} is one raw change in
 * Liquibase, not a formatted SQL changelog, so the model does not call this
 * parser for it.
 *
 * <p>Directive lines are replaced by blank lines in the forward SQL, so a
 * diagnostic's line number still matches the changelog file.
 */
public final class FormattedSqlChangelog {

    private static final String MARKER = "--liquibase formatted sql";
    private static final String CHANGESET = "--changeset";
    private static final String ROLLBACK = "--rollback";
    private static final String ROLLBACK_SQL_FILE = "--rollbackSqlFile";
    private static final Set<String> IGNORED_ATTRIBUTES = Set.of(
            "runAlways", "failOnError", "logicalFilePath", "runWithSpoolFile");
    private static final Set<String> SUPPORTED_ATTRIBUTES = Set.of(
            "splitStatements", "endDelimiter", "stripComments", "runInTransaction", "runOnChange",
            "dbms", "context", "labels", "rollbackEndDelimiter", "rollbackSplitStatements",
            "rollbackStripComments", "ignore", "runWith");

    private FormattedSqlChangelog() {
    }

    /**
     * Parses a formatted SQL changelog.
     *
     * @param file       the formatted SQL file
     * @param properties the changelog properties visible to the file
     * @return the changesets, in file order
     * @throws IOException if the file is not a formatted SQL changelog, or a
     *                     directive or attribute is unsupported or malformed
     */
    public static List<ChangeSet> parse(Path file, Map<String, String> properties) throws IOException {
        List<String> lines = readLines(file);
        int marker = firstNonBlank(lines);
        if (marker < 0 || !lines.get(marker).strip().equalsIgnoreCase(MARKER)) {
            throw new IOException("not a Liquibase formatted SQL changelog (missing '" + MARKER + "'): " + file);
        }
        List<ChangeSet> changesets = new ArrayList<>();
        Builder current = null;
        boolean seenChangeset = false;
        for (int index = marker + 1; index < lines.size(); index++) {
            String line = lines.get(index);
            int lineNumber = index + 1;
            String trimmed = line.strip();
            if (startsWith(trimmed, CHANGESET)) {
                seenChangeset = true;
                if (current != null) {
                    current.addTo(changesets, file, properties);
                }
                current = new Builder(lineNumber, changesetHeader(trimmed, file, lineNumber));
                continue;
            }
            if (current == null) {
                if (startsWith(trimmed, ROLLBACK_SQL_FILE) || startsWith(trimmed, ROLLBACK)
                        || startsWith(trimmed, "--ignoreLines") || startsWith(trimmed, "--include")) {
                    throw new IOException("formatted SQL directive before the first --changeset at "
                            + file + ":" + lineNumber + ": " + trimmed);
                }
                if (trimmed.startsWith("--")) {
                    continue;
                }
                if (!trimmed.isEmpty()) {
                    throw new IOException("SQL before the first --changeset at "
                            + file + ":" + lineNumber);
                }
                continue;
            }
            if (startsWith(trimmed, ROLLBACK_SQL_FILE)) {
                throw new IOException("--rollbackSqlFile is not supported by the linter at "
                        + file + ":" + lineNumber);
            }
            if (startsWith(trimmed, ROLLBACK)) {
                current.rollback(lineNumber, trimmed);
                continue;
            }
            if (startsWith(trimmed, "--ignoreLines")
                    || startsWith(trimmed, "--includeAll")
                    || startsWith(trimmed, "--include")) {
                throw new IOException("formatted SQL directive is not supported by the linter at "
                        + file + ":" + lineNumber + ": " + trimmed);
            }
            if (isIgnorableDirective(trimmed)) {
                current.blankForwardLine(lineNumber);
                continue;
            }
            current.forwardLine(lineNumber, line);
        }
        if (current != null) {
            current.addTo(changesets, file, properties);
        }
        if (!seenChangeset) {
            throw new IOException("formatted SQL changelog contains no --changeset: " + file);
        }
        return List.copyOf(changesets);
    }

    private static boolean isIgnorableDirective(String trimmed) {
        return startsWith(trimmed, "--liquibase")
                || startsWith(trimmed, "--comment")
                || startsWith(trimmed, "--preconditions")
                || startsWith(trimmed, "--precondition-sql-check")
                || startsWith(trimmed, "--property")
                || startsWith(trimmed, "--validCheckSum");
    }

    private static boolean startsWith(String text, String directive) {
        if (!text.regionMatches(true, 0, directive, 0, directive.length())) {
            return false;
        }
        return text.length() == directive.length()
                || Character.isWhitespace(text.charAt(directive.length()))
                || text.charAt(directive.length()) == ':';
    }

    private static List<String> readLines(Path file) throws IOException {
        String text = Files.readString(file);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        return List.of(text.split("\\R", -1));
    }

    private static int firstNonBlank(List<String> lines) {
        for (int index = 0; index < lines.size(); index++) {
            if (!lines.get(index).isBlank()) {
                return index;
            }
        }
        return -1;
    }

    private static Header changesetHeader(String trimmed, Path file, int line) throws IOException {
        String rest = trimmed.substring(CHANGESET.length()).strip();
        if (rest.isEmpty()) {
            throw new IOException("--changeset without author:id at " + file + ":" + line);
        }
        List<String> tokens = splitAttributes(rest, file, line);
        String authorAndId = tokens.getFirst();
        int colon = authorAndId.indexOf(':');
        if (colon <= 0 || colon == authorAndId.length() - 1) {
            throw new IOException("--changeset must be author:id at " + file + ":" + line);
        }
        String author = authorAndId.substring(0, colon);
        String id = authorAndId.substring(colon + 1);
        Map<String, String> attributes = new LinkedHashMap<>();
        for (int index = 1; index < tokens.size(); index++) {
            String token = tokens.get(index);
            int separator = attributeSeparator(token);
            if (separator <= 0 || separator == token.length() - 1) {
                throw new IOException("malformed changeset attribute '" + token + "' at "
                        + file + ":" + line);
            }
            String name = token.substring(0, separator);
            String value = unquote(token.substring(separator + 1));
            if (attributes.putIfAbsent(name, value) != null) {
                throw new IOException("duplicate changeset attribute '" + name + "' at "
                        + file + ":" + line);
            }
        }
        return new Header(id, author, attributes);
    }

    private static int attributeSeparator(String token) {
        int colon = token.indexOf(':');
        int equals = token.indexOf('=');
        if (colon < 0) {
            return equals;
        }
        if (equals < 0) {
            return colon;
        }
        return Math.min(colon, equals);
    }

    private static List<String> splitAttributes(String text, Path file, int line) throws IOException {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (quote != 0) {
                current.append(character);
                if (character == quote) {
                    quote = 0;
                }
            } else if (character == '\'' || character == '"') {
                quote = character;
                current.append(character);
            } else if (Character.isWhitespace(character)) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }
        if (quote != 0) {
            throw new IOException("unterminated quote in --changeset at " + file + ":" + line);
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                && value.charAt(value.length() - 1) == value.charAt(0)) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private record Header(String id, String author, Map<String, String> attributes) {
    }

    private static final class Builder {

        private final int line;
        private final Header header;
        private final StringBuilder forward = new StringBuilder();
        private final StringBuilder rollback = new StringBuilder();
        private int forwardLine = -1;
        private int rollbackLine = -1;
        private boolean rollbackDefined;

        private Builder(int line, Header header) {
            this.line = line;
            this.header = header;
        }

        private void forwardLine(int lineNumber, String text) {
            if (forwardLine < 0) {
                forwardLine = lineNumber;
            }
            forward.append(text).append('\n');
        }

        private void blankForwardLine(int lineNumber) {
            if (forwardLine < 0) {
                forwardLine = lineNumber;
            }
            forward.append('\n');
        }

        private void rollback(int lineNumber, String trimmed) {
            rollbackDefined = true;
            if (rollbackLine < 0) {
                rollbackLine = lineNumber;
            }
            String sql = trimmed.substring(ROLLBACK.length()).strip();
            rollback.append(sql).append('\n');
            blankForwardLine(lineNumber);
        }

        private void addTo(List<ChangeSet> changesets, Path file, Map<String, String> properties)
                throws IOException {
            Map<String, String> attributes = header.attributes();
            for (String name : attributes.keySet()) {
                if (!SUPPORTED_ATTRIBUTES.contains(name) && !IGNORED_ATTRIBUTES.contains(name)) {
                    throw new IOException("unsupported changeset attribute '" + name + "' at "
                            + file + ":" + line);
                }
            }
            String runWith = attributes.get("runWith");
            if (runWith != null && !runWith.equalsIgnoreCase("jdbc")) {
                throw new IOException("changeset runWith:" + runWith
                        + " is not supported by the linter at " + file + ":" + line);
            }
            if (booleanAttribute(attributes, "ignore", false)) {
                return;
            }
            List<SqlSource> forwardSources = forward.toString().isBlank()
                    ? List.of()
                    : List.of(new SqlSource(SqlSource.Kind.INLINE_SQL, null, forward.toString(),
                    booleanAttribute(attributes, "splitStatements", true),
                    stringAttribute(attributes, "endDelimiter", ";"),
                    booleanAttribute(attributes, "stripComments", false),
                    null, Math.max(forwardLine, 1)));
            List<SqlSource> rollbackSources = !rollbackDefined || rollback.toString().isBlank()
                    ? List.of()
                    : List.of(new SqlSource(SqlSource.Kind.INLINE_SQL, null, rollback.toString(),
                    booleanAttribute(attributes, "rollbackSplitStatements", true),
                    stringAttribute(attributes, "rollbackEndDelimiter", ";"),
                    booleanAttribute(attributes, "rollbackStripComments", false),
                    null, Math.max(rollbackLine, 1)));
            changesets.add(new ChangeSet(
                    header.id(),
                    header.author(),
                    file,
                    booleanAttribute(attributes, "runInTransaction", true),
                    booleanAttribute(attributes, "runOnChange", false),
                    nullableAttribute(attributes, "dbms"),
                    nullableAttribute(attributes, "context"),
                    nullableAttribute(attributes, "labels"),
                    forwardSources,
                    rollbackDefined,
                    rollbackSources,
                    new Normalisation(properties, List.of()),
                    line));
        }

        private static boolean booleanAttribute(Map<String, String> attributes, String name,
                                                boolean defaultValue) {
            String value = attributes.get(name);
            return value == null ? defaultValue : Boolean.parseBoolean(value);
        }

        private static String stringAttribute(Map<String, String> attributes, String name,
                                              String defaultValue) {
            String value = attributes.get(name);
            return value == null ? defaultValue : value;
        }

        private static String nullableAttribute(Map<String, String> attributes, String name) {
            String value = attributes.get(name);
            return value == null || value.isBlank() ? null : value;
        }
    }
}
