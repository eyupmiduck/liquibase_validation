package io.github.eyupmiduck.changelogvalidator.testing;

/**
 * The changelog fixture and profile shared by this library's own round-trip and
 * re-apply contract tests: a small changelog, a fixture changelog and an
 * installed baseline schema.
 */
final class RehearsalFixture {

    static final PostgresTestBase.Configuration CONFIGURATION = new PostgresTestBase.Configuration(
            "test", "test", "test", "test", "test", PostgresTestBase.DEFAULT_POSTGRES_IMAGE,
            "db/changelog-roundtrip/db.changelog-master.xml",
            "databasechangelog", "databasechangeloglock",
            "db/changelog-roundtrip-fixtures/db.changelog-fixtures.xml", null);

    static final ProjectProfile PROFILE = ProjectProfile.builder(CONFIGURATION)
            .managedSchemas("roundtrip_app")
            .roundTripSchemas("roundtrip_app", "roundtrip_fixtures", "roundtrip_baseline")
            .baselineChangelogs("db/changelog-roundtrip-baseline/db.changelog-baseline.xml")
            .build();

    private RehearsalFixture() {
    }
}
