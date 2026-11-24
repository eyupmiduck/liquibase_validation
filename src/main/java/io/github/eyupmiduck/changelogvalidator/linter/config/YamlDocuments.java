package io.github.eyupmiduck.changelogvalidator.linter.config;

import org.yaml.snakeyaml.DumperOptions;
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
     * Returns a fresh {@link Yaml} built with {@link SafeConstructor}, which
     * only produces plain maps, lists and scalars. The default constructor would
     * resolve global tags and can instantiate arbitrary classes
     * (CVE-2022-1471). Duplicate keys are rejected and alias/nesting/codepoint
     * limits are explicit. The returned parser is stateful and must not be
     * shared between threads; callers create one per load.
     *
     * @return the safe parser
     */
    public static Yaml safe() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        options.setNestingDepthLimit(50);
        options.setCodePointLimit(1_000_000);
        return new Yaml(new SafeConstructor(options));
    }

    /**
     * Renders a value as block-style YAML, for writing a whitelist. Block style
     * is explicit because SnakeYAML's default {@code AUTO} renders a list of
     * maps in flow style; two-space indentation is the SnakeYAML default.
     *
     * @param value the value to render
     * @return the YAML document
     */
    public static String dump(Object value) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(value);
    }
}
