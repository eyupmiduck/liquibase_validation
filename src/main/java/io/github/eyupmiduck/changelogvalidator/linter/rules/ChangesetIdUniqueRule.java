package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.ProjectRule;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports a duplicate changeset identity: the same {@code id}, {@code author} and
 * changelog file.
 *
 * <p>Liquibase keys a changeset by those three values, so a repeat is ambiguous:
 * the second copy is not applied independently, and a whitelist or history entry
 * cannot tell the two apart. This is a project rule because it needs the whole
 * changelog graph.
 *
 * <p>The rule is an error and on by default. It reports every occurrence after
 * the first.
 */
public final class ChangesetIdUniqueRule implements ProjectRule {

    /**
     * The rule id.
     */
    public static final String ID = "changeset-id-unique";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public List<Violation> check(List<ChangeSet> changeSets) {
        Map<String, ChangeSet> firstByIdentity = new LinkedHashMap<>();
        List<Violation> violations = new ArrayList<>();
        for (ChangeSet changeSet : changeSets) {
            String key = changeSet.id() + '\u0000' + changeSet.author() + '\u0000'
                    + changeSet.changelogFile().toAbsolutePath().normalize();
            ChangeSet first = firstByIdentity.putIfAbsent(key, changeSet);
            if (first == null) {
                continue;
            }
            if (first.line() == changeSet.line()
                    && first.changelogFile().toAbsolutePath().normalize()
                    .equals(changeSet.changelogFile().toAbsolutePath().normalize())) {
                // The same declaration reached twice through a repeated include,
                // not two distinct changeset elements (which cannot share a line).
                continue;
            }
            violations.add(new Violation(
                    changeSet,
                    "changeSet " + changeSet.id(),
                    "duplicate changeset id '" + changeSet.id() + "' for author '" + changeSet.author()
                            + "' in this file; first declared at line " + first.line(),
                    "Make the id, author or file unique."));
        }
        return List.copyOf(violations);
    }
}
