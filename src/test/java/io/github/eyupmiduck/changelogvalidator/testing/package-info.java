/**
 * Shared test support for projects that build a Liquibase changelog and
 * PostgreSQL routines with liquibase-validation.
 *
 * <p>These classes are packaged as a {@code tests} classifier of the
 * {@code liquibase-validation} artifact (see
 * {@code docs/adr/0004-shared-test-support-packaging.md}) so consumers can share
 * one implementation of the PostgreSQL test base and the classpath changelog
 * resolver instead of copying them. They are parameterized by the consumer's
 * schema prefix, owner/test roles and changelog resource.
 *
 * <p>The {@code *Contract} classes hold the tests themselves: a consumer
 * subclasses one and returns its one {@link
 * io.github.eyupmiduck.changelogvalidator.testing.ProjectProfile} from
 * {@code projectProfile()}, so the checks stay identical across projects and the
 * schema, role and changelog values cannot drift between test classes.
 * {@code ChangelogLayoutContract} additionally joins a changeset id to its file
 * names and, for a routine or trigger, to the declaration inside the file.
 * {@code CommentContract} checks the catalog-comment convention over the
 * profile's managed schemas, and {@code AuditColumnsContract} checks the
 * audit-column convention plus an optional behavioral trigger probe.
 * {@code TestResources} gives those contracts and the consumer's own tests
 * classpath access to files and SQL directories.
 */
package io.github.eyupmiduck.changelogvalidator.testing;
