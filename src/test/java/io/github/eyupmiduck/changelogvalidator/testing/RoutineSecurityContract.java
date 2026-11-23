package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract for a consuming project's routine-security test: {@code SECURITY
 * DEFINER} is allowed only for listed routines, every definer pins a safe
 * {@code search_path}, configured catalog-inspecting routines pin
 * {@code pg_catalog}, no routine uses a caller-controlled {@code search_path}
 * ({@code $user} or {@code DEFAULT}), and a declared procedure really is a
 * procedure.
 *
 * <p>A consumer extends this class and returns its one {@link ProjectProfile}
 * from {@link #projectProfile()}; {@link #routineSchemas()} defaults to the
 * profile's routine schemas. Add each deliberate definer to
 * {@link #allowedSecurityDefiners()}, each catalog-inspecting routine to
 * {@link #catalogRoutines()}, and each routine that must be a procedure to
 * {@link #expectedProcedures()}. A stale entry in any list fails, so an
 * exception cannot outlive the object it described.
 */
public abstract class RoutineSecurityContract extends PostgresTestBase {

    static Report inspect(Connection connection, Collection<String> schemas,
                          Set<String> allowedSecurityDefiners,
                          Set<String> catalogRoutines,
                          Set<String> expectedProcedures) throws SQLException {
        Objects.requireNonNull(allowedSecurityDefiners, "allowedSecurityDefiners");
        Objects.requireNonNull(catalogRoutines, "catalogRoutines");
        Objects.requireNonNull(expectedProcedures, "expectedProcedures");

        List<RoutineCatalog.Routine> routines = RoutineCatalog.routines(connection, schemas);
        Map<String, List<RoutineCatalog.Routine>> byName = new LinkedHashMap<>();
        for (RoutineCatalog.Routine routine : routines) {
            byName.computeIfAbsent(routine.qualifiedName(), name -> new ArrayList<>()).add(routine);
        }

        Set<String> definers = new TreeSet<>();
        Set<String> unexpectedDefiners = new TreeSet<>();
        Set<String> unsafeSearchPaths = new TreeSet<>();
        for (RoutineCatalog.Routine routine : routines) {
            String name = routine.qualifiedName();
            if (routine.securityDefiner()) {
                definers.add(name);
                if (!allowedSecurityDefiners.contains(name)) {
                    unexpectedDefiners.add(name);
                }
            }
            checkSearchPath(routine, unsafeSearchPaths);
        }

        Set<String> staleDefiners = new TreeSet<>(allowedSecurityDefiners);
        staleDefiners.removeAll(definers);

        Set<String> missingCatalogPins = new TreeSet<>();
        for (String name : catalogRoutines) {
            List<RoutineCatalog.Routine> entries = byName.get(name);
            if (entries == null || entries.isEmpty()) {
                missingCatalogPins.add(name + ": routine not found");
                continue;
            }
            for (RoutineCatalog.Routine routine : entries) {
                if (!pinsPgCatalog(routine.searchPath())) {
                    missingCatalogPins.add(name + ": expected search_path=pg_catalog, got "
                            + routine.searchPath());
                }
            }
        }

        Set<String> procedureKindMismatches = new TreeSet<>();
        for (String name : expectedProcedures) {
            List<RoutineCatalog.Routine> entries = byName.get(name);
            if (entries == null || entries.isEmpty()) {
                procedureKindMismatches.add(name + ": routine not found");
                continue;
            }
            for (RoutineCatalog.Routine routine : entries) {
                if (!routine.procedure()) {
                    procedureKindMismatches.add(name + ": expected a procedure, got a function");
                }
            }
        }

        return new Report(
                List.copyOf(unexpectedDefiners),
                List.copyOf(staleDefiners),
                List.copyOf(unsafeSearchPaths),
                List.copyOf(missingCatalogPins),
                List.copyOf(procedureKindMismatches),
                byName.size());
    }

    private static void checkSearchPath(RoutineCatalog.Routine routine, Set<String> unsafeSearchPaths) {
        String searchPath = routine.searchPath();
        String name = routine.qualifiedName();
        if (searchPath == null) {
            if (routine.securityDefiner()) {
                unsafeSearchPaths.add(name + ": SECURITY DEFINER has no pinned search_path");
            }
            return;
        }
        String normalized = searchPath.toLowerCase(Locale.ROOT);
        if (normalized.contains("$user") || normalized.contains("default")) {
            unsafeSearchPaths.add(name + ": caller-controlled search_path=" + searchPath);
        }
        if (normalized.contains("public")) {
            unsafeSearchPaths.add(name
                    + ": search_path must not include public (search_path=" + searchPath + ")");
        }
        if (routine.securityDefiner() && !normalized.contains("pg_catalog")) {
            unsafeSearchPaths.add(name
                    + ": SECURITY DEFINER must pin pg_catalog (search_path=" + searchPath + ")");
        }
    }

    private static boolean pinsPgCatalog(String searchPath) {
        return searchPath != null && searchPath.toLowerCase(Locale.ROOT).contains("pg_catalog");
    }

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
     * The deliberate {@code SECURITY DEFINER} routines, as {@code schema.name}.
     *
     * @return the allowed definers, empty by default
     */
    protected Set<String> allowedSecurityDefiners() {
        return Set.of();
    }

    /**
     * The catalog-inspecting routines that must pin {@code pg_catalog}, as
     * {@code schema.name}.
     *
     * @return the catalog routines, empty by default
     */
    protected Set<String> catalogRoutines() {
        return Set.of();
    }

    /**
     * The routines that must be procedures, as {@code schema.name}.
     *
     * @return the expected procedures, empty by default
     */
    protected Set<String> expectedProcedures() {
        return Set.of();
    }

    /**
     * Every security convention holds, and at least one routine was inspected.
     *
     * @throws Exception if the catalog cannot be read
     */
    @Test
    void routinesFollowTheSecurityConvention() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            Report report = inspect(owner, routineSchemas(), allowedSecurityDefiners(),
                    catalogRoutines(), expectedProcedures());

            assertTrue(report.checkedRoutines() > 0,
                    () -> "no routines found in " + routineSchemas());
            assertEquals(List.of(), report.unexpectedSecurityDefiners(),
                    () -> "SECURITY DEFINER routines not in allowedSecurityDefiners(): "
                            + report.unexpectedSecurityDefiners());
            assertEquals(List.of(), report.staleSecurityDefiners(),
                    () -> "allowedSecurityDefiners() entries without a definer: "
                            + report.staleSecurityDefiners());
            assertEquals(List.of(), report.unsafeSearchPaths(),
                    () -> "unsafe or missing search_path settings: " + report.unsafeSearchPaths());
            assertEquals(List.of(), report.missingCatalogPins(),
                    () -> "catalog routines without search_path=pg_catalog: "
                            + report.missingCatalogPins());
            assertEquals(List.of(), report.procedureKindMismatches(),
                    () -> "routines whose kind does not match expectedProcedures(): "
                            + report.procedureKindMismatches());
        }
    }

    /**
     * The security-check result.
     *
     * @param unexpectedSecurityDefiners definer routines not in the allow-list
     * @param staleSecurityDefiners      allow-list entries without a definer
     * @param unsafeSearchPaths          missing or unsafe {@code search_path} settings
     * @param missingCatalogPins         catalog routines without a {@code pg_catalog} pin
     * @param procedureKindMismatches    routines that are not the declared procedure
     * @param checkedRoutines            the number of distinct routine names inspected
     */
    record Report(List<String> unexpectedSecurityDefiners, List<String> staleSecurityDefiners,
                  List<String> unsafeSearchPaths, List<String> missingCatalogPins,
                  List<String> procedureKindMismatches, int checkedRoutines) {
    }
}
