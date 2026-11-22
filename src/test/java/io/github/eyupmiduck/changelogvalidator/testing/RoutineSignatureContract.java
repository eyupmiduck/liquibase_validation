package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract for a consuming project's routine-signature test: every routine name
 * has one signature unless it is explicitly allowed, and every allowed overload
 * still exists.
 *
 * <p>A consumer extends this class and returns its one {@link ProjectProfile}
 * from {@link #projectProfile()}; {@link #routineSchemas()} defaults to the
 * profile's routine schemas. Add a deliberately overloaded {@code schema.name}
 * to {@link #allowedOverloads()}; an entry that no longer matches a real overload
 * is stale and fails, so a removed exception cannot hide a new overload.
 *
 * <p>The contract is non-vacuous: a routine-schema set with no routine fails.
 */
public abstract class RoutineSignatureContract extends PostgresTestBase {

    /**
     * The schemas whose routines are checked, from the profile's routine
     * schemas.
     *
     * @return the routine schemas
     */
    protected Collection<String> routineSchemas() {
        return projectProfile().routineSchemas();
    }

    /**
     * The routines that are intentionally overloaded, as {@code schema.name}.
     *
     * @return the allowed overloads, empty by default
     */
    protected Set<String> allowedOverloads() {
        return Set.of();
    }

    /**
     * No routine is overloaded outside the allow-list, every allow-list entry is
     * a real overload, and at least one routine was inspected.
     *
     * @throws Exception if the catalog cannot be read
     */
    @Test
    void everyRoutineHasOneSignatureUnlessAllowed() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            Report report = inspect(owner, routineSchemas(), allowedOverloads());
            assertTrue(report.checkedRoutines() > 0,
                    () -> "no routines found in " + routineSchemas());
            assertEquals(List.of(), report.unexpectedOverloads(),
                    () -> "unexpected routine overloads (add a deliberate overload to allowedOverloads()): "
                            + report.unexpectedOverloads());
            assertEquals(List.of(), report.staleAllowListEntries(),
                    () -> "allowedOverloads() entries without an actual overload: "
                            + report.staleAllowListEntries());
        }
    }

    static Report inspect(Connection connection, Collection<String> schemas, Set<String> allowedOverloads)
            throws SQLException {
        Objects.requireNonNull(allowedOverloads, "allowedOverloads");
        List<RoutineCatalog.Routine> routines = RoutineCatalog.routines(connection, schemas);
        Map<String, List<String>> signaturesByName = new LinkedHashMap<>();
        for (RoutineCatalog.Routine routine : routines) {
            signaturesByName.computeIfAbsent(routine.qualifiedName(), name -> new ArrayList<>())
                    .add(routine.identityArguments());
        }

        List<String> unexpected = new ArrayList<>();
        Set<String> overloaded = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : signaturesByName.entrySet()) {
            if (entry.getValue().size() > 1) {
                overloaded.add(entry.getKey());
                if (!allowedOverloads.contains(entry.getKey())) {
                    unexpected.add(entry.getKey() + "(" + String.join(" | ", entry.getValue()) + ")");
                }
            }
        }
        List<String> stale = new ArrayList<>();
        for (String allowed : allowedOverloads) {
            if (!overloaded.contains(allowed)) {
                stale.add(allowed);
            }
        }
        unexpected.sort(String::compareTo);
        stale.sort(String::compareTo);
        return new Report(unexpected, stale, signaturesByName.size());
    }

    /**
     * The signature-check result.
     *
     * @param unexpectedOverloads    overloaded routines not in the allow-list
     * @param staleAllowListEntries  allow-list entries without a real overload
     * @param checkedRoutines        the number of distinct routine names inspected
     */
    record Report(List<String> unexpectedOverloads, List<String> staleAllowListEntries, int checkedRoutines) {
    }
}
