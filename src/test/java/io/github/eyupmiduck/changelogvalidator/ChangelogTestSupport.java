package io.github.eyupmiduck.changelogvalidator;

import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;

import java.util.List;

/**
 * Fixtures shared by the changelog model and validator tests: the XSD-wrapped
 * changelog template and lookup of a changeset by id.
 */
public final class ChangelogTestSupport {

    private ChangelogTestSupport() {
    }

    /**
     * Wraps {@code body} in a {@code databaseChangeLog} document with the
     * Liquibase namespace and schema location.
     *
     * @param body the changelog body
     * @return the full XML document
     */
    public static String changelog(String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog \
                https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                %s
                </databaseChangeLog>
                """.formatted(body);
    }

    /**
     * Returns the changeset with the given id.
     *
     * @param changesets the changesets
     * @param id         the changeset id
     * @return the changeset
     * @throws AssertionError when no changeset has that id
     */
    public static ChangeSet byId(List<ChangeSet> changesets, String id) {
        return changesets.stream()
                .filter(changeset -> changeset.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no changeset " + id));
    }
}
