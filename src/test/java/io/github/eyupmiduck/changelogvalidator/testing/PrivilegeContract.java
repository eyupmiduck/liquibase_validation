package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Contract for a consuming project's privilege test: no routine or application
 * type is usable by {@code PUBLIC}, the caller role can execute every callable
 * routine and has explicit {@code USAGE} on each configured type, a routine or
 * type created after the default-privilege changeset does not regain
 * {@code PUBLIC} access, and the grant changeset's rollback mirrors its forward
 * grants without stale entries.
 *
 * <p>A consumer extends this class and returns its one {@link ProjectProfile}
 * from {@link #projectProfile()}; the routine schemas, type schemas and caller
 * role come from it. Override {@link #applicationTypes()} with the types and
 * domains the caller must be able to use, and {@link #defaultPrivilegesExpected()}
 * when the project has no default-privilege changeset.
 */
public abstract class PrivilegeContract extends PostgresTestBase {

    private static String first(Collection<String> values, String what) {
        return values.stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("at least one " + what + " is required"));
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
     * The schemas used for the default-privilege type probe, from the profile's
     * managed schemas.
     *
     * @return the type schemas
     */
    protected Collection<String> typeSchemas() {
        return projectProfile().managedSchemas();
    }

    /**
     * The caller role that must be able to execute the routines and use the
     * types.
     *
     * @return the caller role
     */
    protected String callerRole() {
        String role = projectProfile().callerRole();
        if (role == null) {
            throw new IllegalStateException(
                    "ProjectProfile.callerRole is required for the privilege contract");
        }
        return role;
    }

    /**
     * The application types and domains the caller must have explicit
     * {@code USAGE} on and that must not be usable by {@code PUBLIC}.
     *
     * @return the application types, empty by default
     */
    protected Collection<TypeRef> applicationTypes() {
        return List.of();
    }

    /**
     * The forward grant script classpath resource.
     *
     * @return the forward grant script
     */
    protected String grantForwardResource() {
        return projectProfile().changesRootResource() + "/sql_changes/999-grant-privileges.sql";
    }

    /**
     * The grant rollback script classpath resource.
     *
     * @return the rollback grant script
     */
    protected String grantRollbackResource() {
        return projectProfile().changesRootResource() + "/rollback/999-grant-privileges-rollback.sql";
    }

    /**
     * Whether the project configures default privileges for future routines and
     * types. When {@code false}, the created-later probe is skipped.
     *
     * @return {@code true} by default
     */
    protected boolean defaultPrivilegesExpected() {
        return true;
    }

    /**
     * No routine in the configured schemas is executable by {@code PUBLIC}, and
     * at least one routine was inspected.
     *
     * @throws Exception if the catalog cannot be read
     */
    @Test
    void noRoutineIsExecutableByPublic() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            assertFalse(RoutineCatalog.routines(owner, routineSchemas()).isEmpty(),
                    () -> "no routines found in " + routineSchemas());
            GrantAssertions.assertNoRoutineExecutableByPublic(owner, routineSchemas());
        }
    }

    /**
     * The caller can execute every callable routine, and at least one routine was
     * inspected.
     *
     * @throws Exception if the catalog cannot be read
     */
    @Test
    void callerCanExecuteEveryCallableRoutine() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            assertFalse(RoutineCatalog.routines(owner, routineSchemas()).isEmpty(),
                    () -> "no routines found in " + routineSchemas());
            GrantAssertions.assertRoleCanExecuteEveryCallableRoutine(owner, routineSchemas(), callerRole());
        }
    }

    /**
     * Every configured application type is not usable by {@code PUBLIC} and the
     * caller has an explicit {@code USAGE} grant. The assertion is non-vacuous: an
     * empty type list fails.
     *
     * @throws Exception if the catalog cannot be read
     */
    @Test
    void applicationTypesAreNotUsableByPublicAndCallersCanUseThem() throws Exception {
        Collection<TypeRef> types = applicationTypes();
        assertFalse(types.isEmpty(), "at least one application type/domain is required");
        try (Connection owner = openOwnerConnection()) {
            for (TypeRef type : types) {
                GrantAssertions.assertTypeIsNotUsableByPublic(owner, type.schema(), type.name());
                GrantAssertions.assertRoleHasExplicitUsageOnType(
                        owner, type.schema(), type.name(), callerRole());
            }
        }
    }

    /**
     * A routine and a type created after the default-privilege changeset do not
     * regain {@code PUBLIC} access. Skipped when the project has no default
     * privileges.
     *
     * @throws Exception if the probe cannot run
     */
    @Test
    void routinesAndTypesCreatedLaterHaveNoPublicPrivilege() throws Exception {
        Assumptions.assumeTrue(defaultPrivilegesExpected(),
                "the project does not configure default privileges");
        String functionSchema = first(routineSchemas(), "routine schema");
        String typeSchema = first(typeSchemas(), "type schema");
        String function = "privilege_probe";
        String type = "privilege_probe_type";
        try (Connection owner = openOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("DROP FUNCTION IF EXISTS " + functionSchema + "." + function + "()");
            statement.execute("DROP TYPE IF EXISTS " + typeSchema + "." + type);
            statement.execute("CREATE FUNCTION " + functionSchema + "." + function
                    + "() RETURNS integer LANGUAGE sql AS 'SELECT 1'");
            statement.execute("CREATE TYPE " + typeSchema + "." + type + " AS ENUM ('a')");

            GrantAssertions.assertNoRoutineExecutableByPublic(owner, List.of(functionSchema));
            GrantAssertions.assertTypeIsNotUsableByPublic(owner, typeSchema, type);
        } finally {
            try (Connection owner = openOwnerConnection();
                 Statement statement = owner.createStatement()) {
                statement.execute("DROP FUNCTION IF EXISTS " + functionSchema + "." + function + "()");
                statement.execute("DROP TYPE IF EXISTS " + typeSchema + "." + type);
            }
        }
    }

    /**
     * The grant changeset's rollback mirrors its forward grants and has no stale
     * entries.
     *
     * @throws Exception if a resource cannot be read or the parity assertion fails
     */
    @Test
    void grantRollbackMirrorsTheForwardGrants() throws Exception {
        GrantAssertions.assertGrantRollbackMirrors(
                grantForwardResource(), callerRole(), grantRollbackResource());
    }

    /**
     * A type or domain the caller must be able to use.
     *
     * @param schema the type schema
     * @param name   the type name
     */
    public record TypeRef(String schema, String name) {

        /**
         * Validates the required values.
         */
        public TypeRef {
            Objects.requireNonNull(schema, "schema");
            Objects.requireNonNull(name, "name");
            if (schema.isBlank() || name.isBlank()) {
                throw new IllegalArgumentException("schema and name must not be blank");
            }
        }
    }
}
