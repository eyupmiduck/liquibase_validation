package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link RegistryCredentials} resolves credentials the way CI
 * (environment variables) and a local build ({@code settings.xml}) provide them.
 */
class RegistryCredentialsTest {

    /**
     * The environment variables win when both a username and a token are set.
     */
    @Test
    void readsCredentialsFromTheEnvironment() {
        RegistryCredentials credentials = RegistryCredentials.fromEnvironment(
                Map.of("GITHUB_ACTOR", "octocat", "GITHUB_TOKEN", "ghp_secret"));

        assertTrue(credentials.present());
        assertEquals("octocat", credentials.username());
        assertEquals("ghp_secret", credentials.password());
    }

    /**
     * {@code GH_TOKEN} is used when {@code GITHUB_TOKEN} is absent.
     */
    @Test
    void fallsBackToGhToken() {
        RegistryCredentials credentials = RegistryCredentials.fromEnvironment(
                Map.of("GITHUB_ACTOR", "octocat", "GH_TOKEN", "ghp_alt"));

        assertTrue(credentials.present());
        assertEquals("ghp_alt", credentials.password());
    }

    /**
     * A token without a username is not usable, so it falls through to the
     * settings file.
     */
    @Test
    void requiresBothEnvironmentValues() {
        assertFalse(RegistryCredentials.fromEnvironment(Map.of("GITHUB_TOKEN", "ghp_secret")).present());
        assertFalse(RegistryCredentials.fromEnvironment(Map.of("GITHUB_ACTOR", "octocat")).present());
    }

    /**
     * The {@code github} server's credentials are read, expanding
     * {@code ${env.VAR}} placeholders.
     */
    @Test
    void readsPlaceholdersFromSettingsXml(@TempDir Path directory) throws IOException {
        Path settings = writeSettings(directory, """
                <settings>
                  <servers>
                    <server>
                      <id>github</id>
                      <username>${env.MY_ACTOR}</username>
                      <password>${env.MY_TOKEN}</password>
                    </server>
                  </servers>
                </settings>
                """);

        RegistryCredentials credentials = RegistryCredentials.fromSettingsXml(settings,
                name -> Map.of("MY_ACTOR", "octocat", "MY_TOKEN", "ghp_fromsettings").get(name));

        assertTrue(credentials.present());
        assertEquals("octocat", credentials.username());
        assertEquals("ghp_fromsettings", credentials.password());
    }

    /**
     * A server with another id is ignored, and a plain value is used as-is.
     */
    @Test
    void ignoresOtherServersAndKeepsPlainValues(@TempDir Path directory) throws IOException {
        Path settings = writeSettings(directory, """
                <settings>
                  <servers>
                    <server>
                      <id>other</id>
                      <username>ignored</username>
                      <password>ignored</password>
                    </server>
                    <server>
                      <id>github</id>
                      <username>plain-user</username>
                      <password>plain-token</password>
                    </server>
                  </servers>
                </settings>
                """);

        RegistryCredentials credentials =
                RegistryCredentials.fromSettingsXml(settings, name -> null);

        assertTrue(credentials.present());
        assertEquals("plain-user", credentials.username());
        assertEquals("plain-token", credentials.password());
    }

    /**
     * A missing {@code github} server yields empty credentials, and an unset
     * placeholder does not produce a literal value.
     */
    @Test
    void returnsNoCredentialsWhenAbsent(@TempDir Path directory) throws IOException {
        Path settings = writeSettings(directory, """
                <settings>
                  <servers>
                    <server>
                      <id>other</id>
                    </server>
                  </servers>
                </settings>
                """);

        assertFalse(RegistryCredentials.fromSettingsXml(settings, name -> null).present());
        assertNull(RegistryCredentials.resolvePlaceholder("${env.UNSET}", name -> null));
    }

    /**
     * The environment is preferred over the settings file.
     */
    @Test
    void prefersTheEnvironmentOverSettings(@TempDir Path directory) throws IOException {
        Path settings = writeSettings(directory, """
                <settings>
                  <servers>
                    <server>
                      <id>github</id>
                      <username>from-settings</username>
                      <password>from-settings</password>
                    </server>
                  </servers>
                </settings>
                """);

        RegistryCredentials credentials = RegistryCredentials.detect(
                Map.of("GITHUB_ACTOR", "from-env", "GITHUB_TOKEN", "env-token"), settings);

        assertEquals("from-env", credentials.username());
        assertEquals("env-token", credentials.password());
    }

    /**
     * A DTD is rejected, so a crafted settings file cannot expand entities.
     */
    @Test
    void rejectsDoctypes(@TempDir Path directory) throws IOException {
        Path settings = writeSettings(directory, """
                <?xml version="1.0"?>
                <!DOCTYPE settings [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <settings>
                  <servers>
                    <server>
                      <id>github</id>
                      <username>&xxe;</username>
                      <password>token</password>
                    </server>
                  </servers>
                </settings>
                """);

        IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> RegistryCredentials.fromSettingsXml(settings, name -> null));
        assertTrue(failure.getMessage().contains(settings.toString()));
    }

    private static Path writeSettings(Path directory, String xml) throws IOException {
        Path settings = directory.resolve("settings.xml");
        Files.writeString(settings, xml);
        return settings;
    }
}
