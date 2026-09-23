package io.github.eyupmiduck.changelogvalidator.linter.config;

import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Loads {@link LinterConfig} from a YAML file.
 *
 * <p>A missing file yields {@link LinterConfig#defaults()}. Unknown keys and
 * malformed values fail fast, matching the linter's fail-closed posture.
 */
public final class LinterConfigLoader {

    private static final Set<String> KNOWN_KEYS = Set.of("pgVersion", "failOn", "exclude", "include", "rules");
    private static final Set<String> KNOWN_RULE_KEYS = Set.of("severity", "options");

    private LinterConfigLoader() {
    }

    /**
     * Loads the configuration from {@code configFile}.
     *
     * @param configFile the YAML file; a missing file yields the defaults
     * @return the configuration
     * @throws IOException if the file cannot be read
     */
    public static LinterConfig load(Path configFile) throws IOException {
        if (!Files.exists(configFile)) {
            return LinterConfig.defaults();
        }
        try (Reader reader = Files.newBufferedReader(configFile)) {
            // SafeConstructor only produces plain maps/lists/scalars; the default
            // Constructor would resolve global tags and can instantiate classes
            // (CVE-2022-1471).
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            if (loaded == null) {
                return LinterConfig.defaults();
            }
            if (!(loaded instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("config must be a mapping: " + configFile);
            }
            return parse(map);
        }
    }

    private static LinterConfig parse(Map<?, ?> map) {
        for (Object key : map.keySet()) {
            if (!KNOWN_KEYS.contains(String.valueOf(key))) {
                throw new IllegalArgumentException("unknown config key: " + key);
            }
        }
        return new LinterConfig(
                optionalString(map, "pgVersion"),
                map.containsKey("failOn") ? Severity.from(requireString(map, "failOn")) : Severity.ERROR,
                stringList(map, "exclude"),
                stringList(map, "include"),
                rules(map));
    }

    private static Map<String, LinterConfig.RuleSettings> rules(Map<?, ?> map) {
        Object value = map.get("rules");
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> ruleMap)) {
            throw new IllegalArgumentException("rules must be a mapping");
        }
        Map<String, LinterConfig.RuleSettings> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ruleMap.entrySet()) {
            if (!(entry.getKey() instanceof String id)) {
                throw new IllegalArgumentException("rule id must be a string, got: " + entry.getKey());
            }
            result.put(id, ruleSettings(id, entry.getValue()));
        }
        return result;
    }

    private static LinterConfig.RuleSettings ruleSettings(String id, Object value) {
        if (value instanceof String severity) {
            return new LinterConfig.RuleSettings(Severity.from(severity), Map.of());
        }
        if (value instanceof Map<?, ?> settings) {
            for (Object key : settings.keySet()) {
                if (!KNOWN_RULE_KEYS.contains(String.valueOf(key))) {
                    throw new IllegalArgumentException("unknown key for rule " + id + ": " + key);
                }
            }
            Severity severity = settings.containsKey("severity")
                    ? Severity.from(requireString(settings, "severity")) : null;
            Object options = settings.get("options");
            if (options == null) {
                return new LinterConfig.RuleSettings(severity, Map.of());
            }
            if (!(options instanceof Map<?, ?> optionMap)) {
                throw new IllegalArgumentException("options for rule " + id + " must be a mapping");
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : optionMap.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(
                            "option key for rule " + id + " must be a string, got: " + entry.getKey());
                }
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException("option " + key + " for rule " + id + " has no value");
                }
                normalized.put(key, entry.getValue());
            }
            return new LinterConfig.RuleSettings(severity, normalized);
        }
        throw new IllegalArgumentException("rule " + id + " must be a severity or a mapping");
    }

    private static List<String> stringList(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(key + " must be a list");
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text)) {
                throw new IllegalArgumentException(key + " must contain strings");
            }
            result.add(text);
        }
        return result;
    }

    private static String requireString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing value for " + key);
        }
        return String.valueOf(value);
    }

    private static String optionalString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
