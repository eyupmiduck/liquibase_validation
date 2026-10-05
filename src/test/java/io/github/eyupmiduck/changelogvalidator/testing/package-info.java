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
 */
package io.github.eyupmiduck.changelogvalidator.testing;
