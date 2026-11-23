package io.github.eyupmiduck.changelogvalidator.testing;

/**
 * Runs the shared {@link ChangelogReapplyContract} against the library's fixture
 * changelog, so a normal second application is proven to be a no-op.
 */
class ChangelogReapplyContractTest extends ChangelogReapplyContract {

    @Override
    protected ProjectProfile projectProfile() {
        return RehearsalFixture.PROFILE;
    }
}
