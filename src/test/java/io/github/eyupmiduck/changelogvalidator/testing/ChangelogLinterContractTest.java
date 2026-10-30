package io.github.eyupmiduck.changelogvalidator.testing;

/**
 * Runs the shared {@link ChangelogLinterContract} against the library's own
 * fixture changelog, so the contract a consumer inherits is exercised here too.
 * The fixture has no linter config or whitelist next to it, so this also covers
 * the default-configuration path.
 */
class ChangelogLinterContractTest extends ChangelogLinterContract {
}
