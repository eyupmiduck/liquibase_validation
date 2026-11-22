package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the project profile: its conventional defaults, the builder's
 * application-schema derivation, its validation, and that
 * {@link PostgresTestBase} reads its configuration and contract hooks from the
 * profile.
 */
class ProjectProfileTest {

    private static PostgresTestBase.Configuration configuration() {
        return new PostgresTestBase.Configuration(
                "profile_db", "profile_owner", "owner-secret", "profile_test", "test-secret",
                "postgres:17-alpine", "db/changelog/db.changelog-master.xml",
                "project_databasechangelog", "project_databasechangeloglock",
                "db/changelog-fixtures/db.changelog-fixtures.xml", null);
    }

    /**
     * The default profile uses the library's stock configuration, {@code public}
     * as the only schema and the conventional changelog locations.
     */
    @Test
    void defaultProfileUsesTheLibraryLayout() {
        ProjectProfile profile = ProjectProfile.DEFAULT;

        assertSame(PostgresTestBase.Configuration.DEFAULT, profile.configuration());
        assertEquals(List.of(PostgresTestBase.PUBLIC_SCHEMA), profile.managedSchemas());
        assertEquals(List.of(PostgresTestBase.PUBLIC_SCHEMA), profile.applicationSchemas());
        assertEquals(List.of(PostgresTestBase.PUBLIC_SCHEMA), profile.routineSchemas());
        assertNull(profile.callerRole());
        assertEquals(List.of(), profile.routineRollbackDirectories());
        assertEquals(List.of(), profile.triggerRollbackDirectories());
        assertEquals("db/changelog", profile.changelogRootResource());
        assertEquals("db/changelog/changes", profile.changesRootResource());
        assertEquals("plpgsql-check-whitelist.yml", profile.plpgsqlWhitelistResource());

        assertEquals(ChangelogTestSupport.changelogRoot(), profile.changelogRoot());
        assertEquals(ChangelogTestSupport.changesRoot(), profile.changesRoot());
        assertEquals(ChangelogTestSupport.master(), profile.master());
    }

    /**
     * The builder derives the application schemas as {@code public} plus the
     * managed schemas, removing duplicates and preserving order.
     */
    @Test
    void builderDerivesApplicationSchemasFromManagedSchemas() {
        ProjectProfile profile = ProjectProfile.builder(configuration())
                .managedSchemas("public", "app", "app_lib", "app")
                .routineSchemas("app", "app_lib")
                .callerRole("app_caller")
                .routineRollbackDirectories("db/changelog/changes/functions-rollback")
                .triggerRollbackDirectories("db/changelog/changes/triggers-rollback")
                .build();

        assertEquals(List.of("public", "app", "app_lib"), profile.applicationSchemas());
        assertEquals(List.of("app", "app_lib"), profile.routineSchemas());
        assertEquals("app_caller", profile.callerRole());
        assertEquals(List.of("db/changelog/changes/functions-rollback"),
                profile.routineRollbackDirectories());
        assertEquals(List.of("db/changelog/changes/triggers-rollback"),
                profile.triggerRollbackDirectories());
    }

    /**
     * An explicit application-schema list wins over the derived list.
     */
    @Test
    void builderKeepsExplicitApplicationSchemas() {
        ProjectProfile profile = ProjectProfile.builder(configuration())
                .managedSchemas("app")
                .applicationSchemas("app", "fixtures")
                .build();

        assertEquals(List.of("app", "fixtures"), profile.applicationSchemas());
    }

    /**
     * A profile with no managed schemas is rejected rather than silently
     * producing a contract that inspects nothing.
     */
    @Test
    void builderRejectsAnEmptyManagedSchemaList() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProjectProfile.builder(configuration()).build());
        assertTrue(error.getMessage().contains("managedSchemas"), error.getMessage());
    }

    /**
     * The record copies its lists and rejects blank scalars, so later mutation of
     * the caller's list or a typo cannot change or weaken the profile.
     */
    @Test
    void profileCopiesListsAndRejectsBlankValues() {
        List<String> managed = new ArrayList<>(List.of("app"));
        ProjectProfile profile = ProjectProfile.builder(configuration())
                .managedSchemas(managed)
                .routineSchemas("app")
                .build();
        managed.add("later");

        assertEquals(List.of("app"), profile.managedSchemas());

        assertThrows(IllegalArgumentException.class, () -> new ProjectProfile(
                configuration(), List.of("app"), List.of("app"), List.of("app"), " ",
                List.of(), List.of(), "db/changelog", "db/changelog/changes",
                "plpgsql-check-whitelist.yml"));
        assertThrows(IllegalArgumentException.class, () -> new ProjectProfile(
                configuration(), List.of("app"), List.of("app"), List.of("app"), null,
                List.of(), List.of(), " ", "db/changelog/changes",
                "plpgsql-check-whitelist.yml"));
    }

    /**
     * A profile's description does not print the owner or test passwords.
     */
    @Test
    void descriptionDoesNotContainPasswords() {
        String description = ProjectProfile.builder(configuration())
                .managedSchemas("app")
                .build()
                .toString();

        assertFalse(description.contains("owner-secret"), description);
        assertFalse(description.contains("test-secret"), description);
    }

    /**
     * {@link PostgresTestBase} derives its configuration and contract hooks from
     * the profile a subclass returns.
     */
    @Test
    void testBaseDerivesConfigurationAndHooksFromTheProfile() {
        PostgresTestBase.Configuration configuration = configuration();
        ProjectProfile profile = ProjectProfile.builder(configuration)
                .managedSchemas("app", "app_lib")
                .routineSchemas("app")
                .callerRole("app_caller")
                .routineRollbackDirectories("functions-rollback")
                .triggerRollbackDirectories("triggers-rollback")
                .build();
        Probe probe = new Probe(profile);

        assertSame(configuration, probe.configuration());
        assertEquals(List.of("app", "app_lib"), probe.managedSchemas());
        assertEquals(List.of("public", "app", "app_lib"), probe.applicationSchemas());
        assertEquals(List.of("app"), probe.routineSchemas());
        assertEquals("app_caller", probe.callerRole());
        assertEquals(List.of("functions-rollback"), probe.routineRollbackDirectories());
        assertEquals(List.of("triggers-rollback"), probe.triggerRollbackDirectories());
    }

    /**
     * A minimal subclass exposing the protected hooks; it has no test methods, so
     * JUnit does not run it as a test class.
     */
    private static final class Probe extends PostgresTestBase {

        private final ProjectProfile profile;

        private Probe(ProjectProfile profile) {
            this.profile = profile;
        }

        @Override
        protected ProjectProfile projectProfile() {
            return profile;
        }
    }
}
