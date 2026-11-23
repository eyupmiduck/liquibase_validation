package io.github.eyupmiduck.changelogvalidator.linter.cli;

import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fails when the checked-in README rule reference disagrees with the rule
 * implementations, so the table cannot drift from the code.
 *
 * <p>The comparison is semantic: it parses the table rows and compares the rule
 * id, severity, default state and description, ignoring blank lines, column
 * padding and other harmless formatting. A table can be reformatted by hand as
 * long as the content stays correct.
 */
class RuleDocumentationTest {

    private static final Path README = Path.of("README.md");

    private static final Pattern BACKTICK = Pattern.compile("`([^`]+)`");

    private static List<Row> readmeRows() throws IOException {
        String readme = Files.readString(README);
        int start = readme.indexOf(RuleDocumentation.START);
        int end = readme.indexOf(RuleDocumentation.END);
        assertTrue(start >= 0, () -> "README is missing " + RuleDocumentation.START);
        assertTrue(end > start, () -> "README is missing " + RuleDocumentation.END);
        String block = readme.substring(start + RuleDocumentation.START.length(), end);

        List<Row> rows = new ArrayList<>();
        boolean afterSeparator = false;
        for (String line : block.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!trimmed.startsWith("|")) {
                continue;
            }
            if (!afterSeparator) {
                afterSeparator = isSeparator(trimmed);
                continue;
            }
            rows.add(parseRow(trimmed));
        }
        assertTrue(afterSeparator, "README rule table has no header separator");
        return rows;
    }

    /**
     * Every registered rule has a per-rule section, so the table link does not
     * point at a missing heading.
     */
    @Test
    void everyRuleHasAPerRuleSection() throws IOException {
        String readme = Files.readString(README);
        for (RuleCatalog.Entry entry : RuleCatalog.entries(LinterConfig.defaults())) {
            assertTrue(readme.contains("#### " + entry.id() + "\n"),
                    () -> "README is missing a section for " + entry.id());
        }
    }

    private static boolean isSeparator(String line) {
        return line.replaceAll("[|: -]", "").isEmpty();
    }

    private static Row parseRow(String line) {
        // Split on unescaped pipes, then unescape a literal pipe in a cell.
        String[] cells = line.split("(?<!\\\\)\\|", -1);
        assertTrue(cells.length >= 5, () -> "malformed README rule row: " + line);
        String id = id(cells[1]);
        String severity = cells[2].strip().toLowerCase(Locale.ROOT);
        String defaultState = cells[3].replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
        String description = cells[4].replace("\\|", "|").replaceAll("\\s+", " ").strip();
        return new Row(id, severity, defaultState, description);
    }

    private static String id(String cell) {
        Matcher matcher = BACKTICK.matcher(cell);
        if (matcher.find()) {
            return matcher.group(1).strip();
        }
        String stripped = cell.strip();
        int close = stripped.indexOf(']');
        return close < 0 ? stripped : stripped.substring(0, close).replace("[", "").strip();
    }

    private static String severity(RuleCatalog.Entry entry) {
        return entry.severity().name().toLowerCase(Locale.ROOT);
    }

    /**
     * The table between the markers has the same rows as the rule metadata, in
     * registration order.
     */
    @Test
    void readmeRuleTableMatchesTheRuleMetadata() throws IOException {
        List<Row> expected = RuleCatalog.entries(LinterConfig.defaults()).stream()
                .map(entry -> new Row(entry.id(), severity(entry), entry.defaultState(), entry.description()))
                .toList();
        List<Row> actual = readmeRows();

        List<Row> missing = expected.stream().filter(row -> !actual.contains(row)).toList();
        List<Row> unexpected = actual.stream().filter(row -> !expected.contains(row)).toList();
        assertEquals(List.of(), missing,
                () -> "README rule table is missing or has stale rows: " + missing);
        assertEquals(List.of(), unexpected,
                () -> "README rule table has rows that do not match the rule metadata: " + unexpected);
        assertEquals(expected, actual,
                "README rule table rows are out of registration order");
    }

    /**
     * The generator produces a complete block for regenerating the README table.
     */
    @Test
    void generatedTableContainsEveryRule() {
        String block = RuleDocumentation.tableBlock(LinterConfig.defaults());

        assertTrue(block.startsWith(RuleDocumentation.START), block);
        assertTrue(block.endsWith(RuleDocumentation.END), block);
        for (RuleCatalog.Entry entry : RuleCatalog.entries(LinterConfig.defaults())) {
            assertTrue(block.contains("`" + entry.id() + "`"),
                    () -> "generated table is missing " + entry.id());
            assertTrue(block.contains(entry.description()),
                    () -> "generated table is missing the description for " + entry.id());
        }
    }

    private record Row(String id, String severity, String defaultState, String description) {
    }
}
