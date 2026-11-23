package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;

import java.util.List;

/**
 * Reports a changeset without a non-blank {@code author}.
 *
 * <p>Liquibase identifies a changeset by id, author and file, and the author is
 * part of the history a project relies on to find who made a change. A blank or
 * whitespace-only author is treated as missing.
 *
 * <p>The rule is an error and on by default.
 */
public final class ChangesetAuthorRequiredRule implements Rule {

    /**
     * The rule id.
     */
    public static final String ID = "changeset-author-required";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public List<Violation> check(RuleContext context) {
        if (!context.changeSet().author().isBlank()) {
            return List.of();
        }
        return List.of(new Violation(
                "changeset has no author",
                "Set a non-blank author on the changeSet.",
                context.changeSet().changelogFile(), context.changeSet().line(), 1));
    }
}
