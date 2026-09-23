package io.github.eyupmiduck.changelogvalidator.linter.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The safe SnakeYAML parser used to load the config, the linter whitelist and the
 * plpgsql_check allow-list.
 */
public final class YamlDocuments {

    private YamlDocuments() {
    }

    /**
     * Returns a {@link Yaml} built with {@link SafeConstructor}, which only
     * produces plain maps, lists and scalars. The default constructor would
     * resolve global tags and can instantiate arbitrary classes
     * (CVE-2022-1471).
     *
     * @return the safe parser
     */
    public static Yaml safe() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }
}
