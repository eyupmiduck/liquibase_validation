package io.github.eyupmiduck.changelogvalidator.testing;

import io.github.eyupmiduck.changelogvalidator.XmlDocuments;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Credentials for a Maven package registry, used by {@link PublishedRelease} to
 * fetch an artifact from GitHub Packages.
 *
 * <p>Resolution order matches how CI and a local build provide credentials:
 *
 * <ol>
 *   <li>the {@code GITHUB_ACTOR} and {@code GITHUB_TOKEN} (or {@code GH_TOKEN})
 *       environment variables, which {@code actions/setup-java} exports to the
 *       Maven step;</li>
 *   <li>the {@code github} server in {@code ~/.m2/settings.xml}, resolving
 *       {@code ${env.VAR}} placeholders (setup-java writes the token as
 *       {@code ${env.GITHUB_TOKEN}}, so the placeholder must be expanded or the
 *       literal string would be sent).</li>
 * </ol>
 *
 * <p>A missing credential is represented by {@link #none()}; the caller decides
 * whether that is fatal (the registry rejects anonymous requests to GitHub
 * Packages).
 *
 * @param username the registry username, or {@code null}
 * @param password the registry token, or {@code null}
 */
public record RegistryCredentials(String username, String password) {

    /**
     * The Maven server id resolved from {@code settings.xml}.
     */
    public static final String DEFAULT_SERVER_ID = "github";

    /**
     * The environment variable holding the registry username.
     */
    public static final String ENV_USERNAME = "GITHUB_ACTOR";

    /**
     * The environment variable holding the registry token.
     */
    public static final String ENV_TOKEN = "GITHUB_TOKEN";

    /**
     * A fallback environment variable holding the registry token.
     */
    public static final String ENV_TOKEN_FALLBACK = "GH_TOKEN";

    private static final Pattern ENV_PLACEHOLDER =
            Pattern.compile("\\$\\{env\\.([A-Za-z_][A-Za-z0-9_]*)}");

    /**
     * Returns credentials that are not present.
     *
     * @return empty credentials
     */
    public static RegistryCredentials none() {
        return new RegistryCredentials(null, null);
    }

    /**
     * Detects credentials from the environment, falling back to
     * {@code ~/.m2/settings.xml}.
     *
     * @return the detected credentials, or {@link #none()} when there are none
     */
    public static RegistryCredentials detect() {
        Path settings = defaultSettingsXml();
        if (settings != null && Files.isRegularFile(settings)) {
            return detect(System.getenv(), settings);
        }
        return fromEnvironment(System.getenv());
    }

    /**
     * Detects credentials from an environment map and a settings file.
     *
     * @param environment the environment variables
     * @param settingsXml the settings file, or {@code null}
     * @return the detected credentials, or {@link #none()}
     */
    static RegistryCredentials detect(Map<String, String> environment, Path settingsXml) {
        RegistryCredentials fromEnvironment = fromEnvironment(environment);
        if (fromEnvironment.present()) {
            return fromEnvironment;
        }
        if (settingsXml != null && Files.isRegularFile(settingsXml)) {
            return fromSettingsXml(settingsXml, environment::get);
        }
        return none();
    }

    /**
     * Reads credentials from an environment map.
     *
     * @param environment the environment variables
     * @return the credentials, or {@link #none()}
     */
    static RegistryCredentials fromEnvironment(Map<String, String> environment) {
        String username = environment.get(ENV_USERNAME);
        String password = environment.get(ENV_TOKEN);
        if (password == null || password.isBlank()) {
            password = environment.get(ENV_TOKEN_FALLBACK);
        }
        return new RegistryCredentials(blankToNull(username), blankToNull(password));
    }

    /**
     * Returns the default {@code settings.xml} path, or {@code null} when the
     * home directory is unknown.
     *
     * @return the settings path, or {@code null}
     */
    static Path defaultSettingsXml() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return null;
        }
        return Path.of(home, ".m2", "settings.xml");
    }

    /**
     * Reads the {@link #DEFAULT_SERVER_ID} server's credentials from a settings
     * file, resolving {@code ${env.VAR}} placeholders.
     *
     * @param settingsXml the settings file
     * @param environment a lookup for environment variable values
     * @return the credentials, or {@link #none()} when the server is absent
     */
    static RegistryCredentials fromSettingsXml(Path settingsXml, Function<String, String> environment) {
        Document document = parseSecurely(settingsXml);
        NodeList servers = document.getElementsByTagName("server");
        for (int i = 0; i < servers.getLength(); i++) {
            Element server = (Element) servers.item(i);
            if (!DEFAULT_SERVER_ID.equals(text(server, "id"))) {
                continue;
            }
            String username = resolvePlaceholder(text(server, "username"), environment);
            String password = resolvePlaceholder(text(server, "password"), environment);
            return new RegistryCredentials(blankToNull(username), blankToNull(password));
        }
        return none();
    }

    /**
     * Expands a {@code ${env.VAR}} placeholder, or returns the value unchanged.
     *
     * @param value       the raw settings value
     * @param environment a lookup for environment variable values
     * @return the expanded value, or {@code null} when the placeholder is unset
     */
    static String resolvePlaceholder(String value, Function<String, String> environment) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        Matcher matcher = ENV_PLACEHOLDER.matcher(trimmed);
        if (matcher.matches()) {
            return environment.apply(matcher.group(1));
        }
        return trimmed;
    }

    private static Document parseSecurely(Path settingsXml) {
        try {
            // The settings file is user-controlled; XmlDocuments rejects DTDs
            // and external entities, so a crafted file cannot reach the network
            // or the disk.
            return XmlDocuments.parse(settingsXml);
        } catch (IOException e) {
            throw new IllegalStateException("could not read Maven settings from " + settingsXml, e);
        }
    }

    private static String text(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        return nodes.item(0).getTextContent();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Returns whether both a username and a password are present.
     *
     * @return {@code true} when the credentials are usable
     */
    public boolean present() {
        return username != null && !username.isBlank()
                && password != null && !password.isBlank();
    }
}
