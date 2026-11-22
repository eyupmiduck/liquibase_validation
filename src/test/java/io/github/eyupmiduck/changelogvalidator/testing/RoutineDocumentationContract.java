package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract for a consuming project's routine-documentation test: every function
 * and procedure in the configured schemas has a README section, no README
 * section names a routine that no longer exists, and a section that spells out
 * its arguments matches the catalog's input argument names and arity.
 *
 * <p>The default format is a Markdown heading of the form
 * <code>### `schema.name(i_arg, ...)`</code>; an argument list containing
 * {@code ...} is treated as an abbreviation and its argument names and arity are
 * not checked. Override {@link #documentedRoutines(String, String)} for a
 * different format, and {@link #readmeResources()} for different files.
 *
 * <p>A consumer extends this class and returns its one {@link ProjectProfile}
 * from {@link #projectProfile()}; {@link #documentedSchemas()} defaults to the
 * profile's routine schemas and {@link #readmeResources()} defaults to the
 * functions and procedures READMEs under the profile's changelog root. The
 * contract is non-vacuous: a routine-schema set with no routine fails.
 */
public abstract class RoutineDocumentationContract extends PostgresTestBase {

    private static final Pattern HEADING = Pattern.compile(
            "^###\\s+`([A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*)\\((.*)\\)`\\s*$",
            Pattern.MULTILINE);

    /**
     * The schemas whose routines must be documented, from the profile's routine
     * schemas.
     *
     * @return the documented schemas
     */
    protected Collection<String> documentedSchemas() {
        return projectProfile().routineSchemas();
    }

    /**
     * The README classpath resources. The default includes the functions and
     * procedures READMEs under the profile's changelog root when they exist.
     *
     * @return the README resources
     */
    protected Collection<String> readmeResources() {
        String root = projectProfile().changelogRootResource();
        return Stream.of(
                        root + "/changes/functions/README.md",
                        root + "/changes/procedures/README.md")
                .filter(TestResources::exists)
                .toList();
    }

    /**
     * Parses the routine headings of one README. The default reads Markdown
     * headings of the form <code>### `schema.name(args)`</code>.
     *
     * @param resource the README classpath resource
     * @param content  the README contents
     * @return the documented routines
     */
    protected List<DocumentedRoutine> documentedRoutines(String resource, String content) {
        List<DocumentedRoutine> routines = new ArrayList<>();
        Matcher matcher = HEADING.matcher(content);
        while (matcher.find()) {
            int line = 1 + (int) content.substring(0, matcher.start()).chars()
                    .filter(character -> character == '\n')
                    .count();
            routines.add(new DocumentedRoutine(
                    matcher.group(1), matcher.group(2).trim(), resource, line));
        }
        return List.copyOf(routines);
    }

    /**
     * Every routine is documented, no documented routine is stale, every
     * documented argument list agrees with the catalog, and at least one routine
     * was inspected.
     *
     * @throws Exception if the README or catalog cannot be read
     */
    @Test
    void everyRoutineIsDocumented() throws Exception {
        Collection<String> resources = readmeResources();
        assertFalse(resources.isEmpty(),
                () -> "no routine README found; expected one of the functions/procedures READMEs under "
                        + projectProfile().changelogRootResource() + "/changes");
        List<DocumentedRoutine> documented = new ArrayList<>();
        for (String resource : resources) {
            documented.addAll(documentedRoutines(resource, TestResources.read(resource)));
        }
        try (Connection owner = openOwnerConnection()) {
            Report report = inspect(owner, documentedSchemas(), documented);
            assertTrue(report.checkedRoutines() > 0,
                    () -> "no routines found in " + documentedSchemas());
            assertEquals(List.of(), report.missing(),
                    () -> "routines with no README section: " + report.missing());
            assertEquals(List.of(), report.stale(),
                    () -> "README sections for routines that no longer exist: " + report.stale());
            assertEquals(List.of(), report.mismatched(),
                    () -> "README sections whose arguments do not match the catalog: " + report.mismatched());
        }
    }

    static Report inspect(Connection connection, Collection<String> schemas,
                          Collection<DocumentedRoutine> documented) throws SQLException {
        Objects.requireNonNull(documented, "documented");
        List<RoutineCatalog.Routine> routines = RoutineCatalog.routines(connection, schemas);
        Map<String, List<RoutineCatalog.Routine>> byName = new LinkedHashMap<>();
        for (RoutineCatalog.Routine routine : routines) {
            byName.computeIfAbsent(routine.qualifiedName(), name -> new ArrayList<>()).add(routine);
        }

        Map<String, DocumentedRoutine> documentedByName = new LinkedHashMap<>();
        List<String> mismatched = new ArrayList<>();
        for (DocumentedRoutine entry : documented) {
            DocumentedRoutine previous = documentedByName.putIfAbsent(entry.name(), entry);
            if (previous != null) {
                mismatched.add(entry.name() + " is documented more than once ("
                        + previous.resource() + ":" + previous.line() + ", "
                        + entry.resource() + ":" + entry.line() + ")");
            }
        }

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, List<RoutineCatalog.Routine>> entry : byName.entrySet()) {
            DocumentedRoutine documentation = documentedByName.get(entry.getKey());
            if (documentation == null) {
                missing.add(entry.getKey());
                continue;
            }
            if (entry.getValue().size() > 1) {
                // An overloaded name is one section; the signature contract owns
                // the per-overload check.
                continue;
            }
            List<String> documentedArguments = parseArguments(documentation.arguments());
            if (documentedArguments == null) {
                continue;
            }
            RoutineCatalog.Routine routine = entry.getValue().getFirst();
            if (documentedArguments.size() != routine.inputCount()) {
                mismatched.add(entry.getKey() + ": README has (" + documentation.arguments()
                        + ") but the catalog has (" + routine.identityArguments() + ")");
            } else if (!routine.inputNames().isEmpty() && !documentedArguments.equals(routine.inputNames())) {
                mismatched.add(entry.getKey() + ": README arguments " + documentedArguments
                        + " do not match the catalog names " + routine.inputNames());
            }
        }

        List<String> stale = new ArrayList<>();
        for (DocumentedRoutine entry : documentedByName.values()) {
            if (!byName.containsKey(entry.name())) {
                stale.add(entry.name() + " (" + entry.resource() + ":" + entry.line() + ")");
            }
        }
        missing.sort(String::compareTo);
        stale.sort(String::compareTo);
        mismatched.sort(String::compareTo);
        return new Report(missing, stale, mismatched, byName.size());
    }

    /**
     * Parses a heading argument list. A blank list is empty; a list containing
     * {@code ...} is an abbreviation and returns {@code null}; otherwise optional
     * brackets are stripped and the names are returned in order.
     *
     * @param arguments the raw argument list
     * @return the argument names, empty for no arguments, or {@code null} for an
     * abbreviated list
     */
    static List<String> parseArguments(String arguments) {
        String trimmed = arguments.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        if (trimmed.contains("...")) {
            return null;
        }
        List<String> names = new ArrayList<>();
        String withoutBrackets = trimmed.replace("[", "").replace("]", "");
        for (String part : withoutBrackets.split(",", -1)) {
            String name = part.trim();
            if (name.isEmpty()) {
                return null;
            }
            names.add(name);
        }
        return List.copyOf(names);
    }

    /**
     * One documented routine heading.
     *
     * @param name      the {@code schema.name} in the heading
     * @param arguments the raw argument list inside the parentheses
     * @param resource  the README classpath resource
     * @param line      the one-based heading line
     */
    public record DocumentedRoutine(String name, String arguments, String resource, int line) {

        /**
         * Validates the required components.
         */
        public DocumentedRoutine {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(arguments, "arguments");
            Objects.requireNonNull(resource, "resource");
            if (line < 1) {
                throw new IllegalArgumentException("line must be one-based, was " + line);
            }
        }
    }

    /**
     * The documentation-check result.
     *
     * @param missing         routines with no README section
     * @param stale           README sections for routines that no longer exist
     * @param mismatched      sections whose arguments do not match the catalog
     * @param checkedRoutines the number of distinct routines inspected
     */
    record Report(List<String> missing, List<String> stale, List<String> mismatched, int checkedRoutines) {
    }
}
