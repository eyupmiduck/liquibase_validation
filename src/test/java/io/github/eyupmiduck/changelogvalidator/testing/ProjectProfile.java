package io.github.eyupmiduck.changelogvalidator.testing;

import java.nio.file.Path;
import java.util.*;

/**
 * The project values shared by the test-support contracts: the container and
 * changelog {@link PostgresTestBase.Configuration configuration}, the schemas a
 * project manages, the schemas that hold its routines, the application role its
 * grants target, the routine/trigger rollback directories, and the classpath
 * locations of the changelog and the {@code plpgsql_check} allow-list.
 *
 * <p>A consumer normally declares one constant of this type next to its
 * {@code PostgresTestBase} and returns it from {@code projectProfile()}. Its
 * test base and every contract subclass then read the same values, so a schema,
 * role or directory rename cannot drift between tests. The individual
 * {@code protected} hooks on {@link PostgresTestBase} and the contracts remain
 * overridable for a one-off case.
 *
 * <p>The lists are copied and may not contain {@code null}. A project with no
 * routines may leave {@code routineSchemas} and the rollback directories empty;
 * a contract that needs them asserts that they are populated, so a
 * misconfigured profile fails instead of passing vacuously.
 *
 * @param configuration              the container, role, image, changelog and
 *                                   tracking-table configuration
 * @param managedSchemas             the application schemas the changelog
 *                                   manages, used by catalog and audit checks
 * @param applicationSchemas         the schemas that must not hold Liquibase's
 *                                   generic tracking tables, normally
 *                                   {@link PostgresTestBase#PUBLIC_SCHEMA} plus
 *                                   the managed schemas
 * @param routineSchemas             the schemas whose functions and procedures
 *                                   the routine contracts inspect
 * @param callerRole                 the application role the grant changeset
 *                                   targets, or {@code null} when the project
 *                                   has no caller grant contract
 * @param routineRollbackDirectories the classpath directories holding routine
 *                                   rollback SQL
 * @param triggerRollbackDirectories the classpath directories holding trigger
 *                                   rollback SQL
 * @param changelogRootResource      the changelog root classpath resource, for
 *                                   example {@code db/changelog}
 * @param changesRootResource        the changes directory classpath resource,
 *                                   for example {@code db/changelog/changes}
 * @param plpgsqlWhitelistResource   the classpath resource holding the
 *                                   {@code plpgsql_check} allow-list
 * @param roundTripSchemas           the schemas the round-trip contract captures
 *                                   before, during and after the changelog;
 *                                   defaults to {@code managedSchemas} when empty
 * @param baselineChangelogs         extra classpath changelogs applied before
 *                                   the main changelog and deliberately left
 *                                   outside its rollback (for example an
 *                                   installed dependency), in order; empty when
 *                                   there are none
 */
public record ProjectProfile(
        PostgresTestBase.Configuration configuration,
        List<String> managedSchemas,
        List<String> applicationSchemas,
        List<String> routineSchemas,
        String callerRole,
        List<String> routineRollbackDirectories,
        List<String> triggerRollbackDirectories,
        String changelogRootResource,
        String changesRootResource,
        String plpgsqlWhitelistResource,
        List<String> roundTripSchemas,
        List<String> baselineChangelogs) {

    /**
     * The library's own defaults, used by its contract tests: the stock image,
     * the conventional changelog fixture, {@code public} as the only schema, no
     * caller role and no rollback directories.
     */
    public static final ProjectProfile DEFAULT = builder(PostgresTestBase.Configuration.DEFAULT)
            .managedSchemas(PostgresTestBase.PUBLIC_SCHEMA)
            .routineSchemas(PostgresTestBase.PUBLIC_SCHEMA)
            .build();

    /**
     * Validates the required values and copies the lists.
     */
    public ProjectProfile {
        Objects.requireNonNull(configuration, "configuration");
        managedSchemas = copyStrings(managedSchemas, "managedSchemas");
        applicationSchemas = copyStrings(applicationSchemas, "applicationSchemas");
        routineSchemas = copyStrings(routineSchemas, "routineSchemas");
        routineRollbackDirectories = copyStrings(routineRollbackDirectories, "routineRollbackDirectories");
        triggerRollbackDirectories = copyStrings(triggerRollbackDirectories, "triggerRollbackDirectories");
        changelogRootResource = requireNonBlank(changelogRootResource, "changelogRootResource");
        changesRootResource = requireNonBlank(changesRootResource, "changesRootResource");
        plpgsqlWhitelistResource = requireNonBlank(plpgsqlWhitelistResource, "plpgsqlWhitelistResource");
        roundTripSchemas = roundTripSchemas == null || roundTripSchemas.isEmpty()
                ? managedSchemas : copyStrings(roundTripSchemas, "roundTripSchemas");
        baselineChangelogs = copyStrings(baselineChangelogs, "baselineChangelogs");
        if (managedSchemas.isEmpty()) {
            throw new IllegalArgumentException("managedSchemas must not be empty");
        }
        if (applicationSchemas.isEmpty()) {
            throw new IllegalArgumentException("applicationSchemas must not be empty");
        }
        if (callerRole != null && callerRole.isBlank()) {
            throw new IllegalArgumentException("callerRole must be null or non-blank");
        }
    }

    /**
     * Starts a profile builder with the conventional changelog locations and an
     * application-schema list derived from the managed schemas.
     *
     * @param configuration the container and changelog configuration
     * @return the builder
     */
    public static Builder builder(PostgresTestBase.Configuration configuration) {
        return new Builder(configuration);
    }

    private static List<String> copyStrings(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        List<String> copy = List.copyOf(values);
        for (String value : copy) {
            requireNonBlank(value, name + " element");
        }
        return copy;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /**
     * The changelog root directory, resolved from the test classpath.
     *
     * @return the changelog root
     */
    public Path changelogRoot() {
        return ChangelogTestSupport.changelogRoot(changelogRootResource);
    }

    /**
     * The changes directory holding the changelog XML and SQL files, resolved
     * from the test classpath.
     *
     * @return the changes directory
     */
    public Path changesRoot() {
        return ChangelogTestSupport.changelogRoot(changesRootResource);
    }

    /**
     * The master changelog file. Its file name comes from
     * {@link PostgresTestBase.Configuration#masterResource()}, so the classpath
     * resource and the filesystem tree cannot name different masters.
     *
     * @return the master changelog file
     */
    public Path master() {
        return changelogRoot().resolve(Path.of(configuration.masterResource()).getFileName());
    }

    /**
     * Builds a profile with conventional defaults, so a consumer states only the
     * values that differ from the library's layout.
     */
    public static final class Builder {

        private final PostgresTestBase.Configuration configuration;
        private List<String> managedSchemas = List.of();
        private List<String> applicationSchemas;
        private List<String> routineSchemas = List.of();
        private String callerRole;
        private List<String> routineRollbackDirectories = List.of();
        private List<String> triggerRollbackDirectories = List.of();
        private String changelogRootResource = "db/changelog";
        private String changesRootResource = "db/changelog/changes";
        private String plpgsqlWhitelistResource = "plpgsql-check-whitelist.yml";
        private List<String> roundTripSchemas = List.of();
        private List<String> baselineChangelogs = List.of();

        private Builder(PostgresTestBase.Configuration configuration) {
            this.configuration = Objects.requireNonNull(configuration, "configuration");
        }

        /**
         * Sets the schemas the changelog manages.
         *
         * @param schemas the managed schemas
         * @return this builder
         */
        public Builder managedSchemas(String... schemas) {
            return managedSchemas(List.of(schemas));
        }

        /**
         * Sets the schemas the changelog manages.
         *
         * @param schemas the managed schemas
         * @return this builder
         */
        public Builder managedSchemas(Collection<String> schemas) {
            this.managedSchemas = List.copyOf(schemas);
            return this;
        }

        /**
         * Sets the schemas that must not hold the generic tracking tables.
         * Defaults to {@code public} plus the managed schemas.
         *
         * @param schemas the application schemas
         * @return this builder
         */
        public Builder applicationSchemas(String... schemas) {
            return applicationSchemas(List.of(schemas));
        }

        /**
         * Sets the schemas that must not hold the generic tracking tables.
         *
         * @param schemas the application schemas
         * @return this builder
         */
        public Builder applicationSchemas(Collection<String> schemas) {
            this.applicationSchemas = List.copyOf(schemas);
            return this;
        }

        /**
         * Sets the schemas whose routines the routine contracts inspect.
         *
         * @param schemas the routine schemas
         * @return this builder
         */
        public Builder routineSchemas(String... schemas) {
            return routineSchemas(List.of(schemas));
        }

        /**
         * Sets the schemas whose routines the routine contracts inspect.
         *
         * @param schemas the routine schemas
         * @return this builder
         */
        public Builder routineSchemas(Collection<String> schemas) {
            this.routineSchemas = List.copyOf(schemas);
            return this;
        }

        /**
         * Sets the application role the grant changeset targets.
         *
         * @param callerRole the caller role, or {@code null}
         * @return this builder
         */
        public Builder callerRole(String callerRole) {
            this.callerRole = callerRole;
            return this;
        }

        /**
         * Sets the classpath directories holding routine rollback SQL.
         *
         * @param directories the rollback directories
         * @return this builder
         */
        public Builder routineRollbackDirectories(String... directories) {
            return routineRollbackDirectories(List.of(directories));
        }

        /**
         * Sets the classpath directories holding routine rollback SQL.
         *
         * @param directories the rollback directories
         * @return this builder
         */
        public Builder routineRollbackDirectories(Collection<String> directories) {
            this.routineRollbackDirectories = List.copyOf(directories);
            return this;
        }

        /**
         * Sets the classpath directories holding trigger rollback SQL.
         *
         * @param directories the rollback directories
         * @return this builder
         */
        public Builder triggerRollbackDirectories(String... directories) {
            return triggerRollbackDirectories(List.of(directories));
        }

        /**
         * Sets the classpath directories holding trigger rollback SQL.
         *
         * @param directories the rollback directories
         * @return this builder
         */
        public Builder triggerRollbackDirectories(Collection<String> directories) {
            this.triggerRollbackDirectories = List.copyOf(directories);
            return this;
        }

        /**
         * Sets the changelog root classpath resource.
         *
         * @param resource the classpath resource
         * @return this builder
         */
        public Builder changelogRootResource(String resource) {
            this.changelogRootResource = resource;
            return this;
        }

        /**
         * Sets the changes directory classpath resource.
         *
         * @param resource the classpath resource
         * @return this builder
         */
        public Builder changesRootResource(String resource) {
            this.changesRootResource = resource;
            return this;
        }

        /**
         * Sets the {@code plpgsql_check} allow-list classpath resource.
         *
         * @param resource the classpath resource
         * @return this builder
         */
        public Builder plpgsqlWhitelistResource(String resource) {
            this.plpgsqlWhitelistResource = resource;
            return this;
        }

        /**
         * Sets the schemas the round-trip contract captures. Defaults to the
         * managed schemas when unset, and may add a fixture or baseline schema
         * the changelog's rollback must also restore.
         *
         * @param schemas the round-trip schemas
         * @return this builder
         */
        public Builder roundTripSchemas(String... schemas) {
            return roundTripSchemas(List.of(schemas));
        }

        /**
         * Sets the schemas the round-trip contract captures.
         *
         * @param schemas the round-trip schemas
         * @return this builder
         */
        public Builder roundTripSchemas(Collection<String> schemas) {
            this.roundTripSchemas = List.copyOf(schemas);
            return this;
        }

        /**
         * Sets extra classpath changelogs applied before the main changelog and
         * left outside its rollback, for example an installed dependency.
         *
         * @param resources the changelog resources, in application order
         * @return this builder
         */
        public Builder baselineChangelogs(String... resources) {
            return baselineChangelogs(List.of(resources));
        }

        /**
         * Sets extra classpath changelogs applied before the main changelog.
         *
         * @param resources the changelog resources, in application order
         * @return this builder
         */
        public Builder baselineChangelogs(Collection<String> resources) {
            this.baselineChangelogs = List.copyOf(resources);
            return this;
        }

        /**
         * Builds the profile, deriving the application schemas when they were
         * not set explicitly.
         *
         * @return the profile
         */
        public ProjectProfile build() {
            List<String> effectiveApplicationSchemas = applicationSchemas;
            if (effectiveApplicationSchemas == null) {
                Set<String> schemas = new LinkedHashSet<>();
                schemas.add(PostgresTestBase.PUBLIC_SCHEMA);
                schemas.addAll(managedSchemas);
                effectiveApplicationSchemas = List.copyOf(schemas);
            }
            return new ProjectProfile(configuration, managedSchemas, effectiveApplicationSchemas,
                    routineSchemas, callerRole, routineRollbackDirectories, triggerRollbackDirectories,
                    changelogRootResource, changesRootResource, plpgsqlWhitelistResource,
                    roundTripSchemas, baselineChangelogs);
        }
    }
}
