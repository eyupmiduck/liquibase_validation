package io.github.eyupmiduck.changelogvalidator.testing;

/**
 * Runs the shared {@link TrackingTablesContract} against the library's fixture
 * template database, so the inherited test and its profile plumbing are
 * exercised here too. The library's default profile uses {@code public} as its
 * only application schema.
 */
class TrackingTablesContractTest extends TrackingTablesContract {
}
