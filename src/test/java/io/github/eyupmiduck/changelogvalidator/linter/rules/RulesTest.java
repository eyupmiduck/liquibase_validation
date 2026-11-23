package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.ProjectAnalysisRule;
import io.github.eyupmiduck.changelogvalidator.linter.ProjectRule;
import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that every built-in {@link Rule} in this package is registered in
 * {@link Rules}: a forgotten registration would otherwise silently disable the
 * rule from the default set.
 */
class RulesTest {

    private static Rule instantiate(Class<?> type) throws ReflectiveOperationException {
        try {
            return (Rule) type.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException ignored) {
            // Version-gated rules may only declare an Integer constructor.
            return (Rule) type.getDeclaredConstructor(Integer.class).newInstance((Integer) null);
        }
    }

    private static List<Class<?>> classesInPackage() throws Exception {
        Package rulePackage = Rules.class.getPackage();
        Path directory = Path.of(Rules.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve(rulePackage.getName().replace('.', '/'));
        List<Class<?>> classes = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".class") || name.contains("$")) {
                    continue;
                }
                classes.add(Class.forName(rulePackage.getName() + "."
                        + name.substring(0, name.length() - ".class".length())));
            }
        }
        return classes;
    }

    private static LinterConfig config(Map<String, Object> options) {
        return new LinterConfig(null, Severity.ERROR, List.of(), List.of(DynamicSqlRule.ID),
                Map.of(DynamicSqlRule.ID, new LinterConfig.RuleSettings(null, options)));
    }

    private static LinterConfig analysisConfig(String ruleId, Map<String, Object> options) {
        return new LinterConfig(null, Severity.ERROR, List.of(), List.of(ruleId),
                Map.of(ruleId, new LinterConfig.RuleSettings(null, options)));
    }

    @Test
    void registersEveryRuleImplementationInThePackage() throws Exception {
        Set<String> registered = Rules.all((Integer) null).stream()
                .map(Rule::id)
                .collect(Collectors.toSet());
        assertFalse(registered.isEmpty());

        List<String> missing = new ArrayList<>();
        int implementations = 0;
        for (Class<?> type : classesInPackage()) {
            if (!Rule.class.isAssignableFrom(type)
                    || type.isInterface()
                    || Modifier.isAbstract(type.getModifiers())) {
                continue;
            }
            Rule rule = instantiate(type);
            implementations++;
            if (!registered.contains(rule.id())) {
                missing.add(type.getSimpleName() + " (" + rule.id() + ")");
            }
        }

        assertFalse(implementations == 0, "no rule implementations found to check");
        assertTrue(missing.isEmpty(), () -> "rules missing from Rules.all(): " + missing);
    }

    @Test
    void registersEveryProjectRuleImplementationInThePackage() throws Exception {
        Set<String> registered = Rules.projectRules().stream()
                .map(ProjectRule::id)
                .collect(Collectors.toSet());
        assertFalse(registered.isEmpty());

        List<String> missing = new ArrayList<>();
        int implementations = 0;
        for (Class<?> type : classesInPackage()) {
            if (!ProjectRule.class.isAssignableFrom(type)
                    || type.isInterface()
                    || Modifier.isAbstract(type.getModifiers())) {
                continue;
            }
            ProjectRule rule = (ProjectRule) type.getDeclaredConstructor().newInstance();
            implementations++;
            if (!registered.contains(rule.id())) {
                missing.add(type.getSimpleName() + " (" + rule.id() + ")");
            }
        }

        assertFalse(implementations == 0, "no project rule implementations found to check");
        assertTrue(missing.isEmpty(), () -> "project rules missing from Rules.projectRules(): " + missing);
    }

    @Test
    void registersEveryProjectAnalysisRuleImplementationInThePackage() throws Exception {
        Set<String> registered = Rules.projectAnalysisRules().stream()
                .map(ProjectAnalysisRule::id)
                .collect(Collectors.toSet());
        assertFalse(registered.isEmpty());

        List<String> missing = new ArrayList<>();
        int implementations = 0;
        for (Class<?> type : classesInPackage()) {
            if (!ProjectAnalysisRule.class.isAssignableFrom(type)
                    || type.isInterface()
                    || Modifier.isAbstract(type.getModifiers())) {
                continue;
            }
            ProjectAnalysisRule rule = (ProjectAnalysisRule) type.getDeclaredConstructor().newInstance();
            implementations++;
            if (!registered.contains(rule.id())) {
                missing.add(type.getSimpleName() + " (" + rule.id() + ")");
            }
        }

        assertFalse(implementations == 0, "no project analysis rule implementations found to check");
        assertTrue(missing.isEmpty(),
                () -> "project analysis rules missing from Rules.projectAnalysisRules(): " + missing);
    }

    @Test
    void rejectsAMalformedKnownTablesOption() {
        assertThrows(IllegalArgumentException.class,
                () -> Rules.projectAnalysisRules(analysisConfig(ForeignKeyTargetRule.ID,
                        Map.of("knownTables", "t"))));
        assertThrows(IllegalArgumentException.class,
                () -> Rules.projectAnalysisRules(analysisConfig(OrderingDependencyRule.ID,
                        Map.of("knownTables", List.of(1)))));
        assertThrows(IllegalArgumentException.class,
                () -> Rules.projectAnalysisRules(analysisConfig(OrderingDependencyRule.ID,
                        Map.of("knownTables", List.of(" ")))));
        assertFalse(Rules.projectAnalysisRules(analysisConfig(ForeignKeyTargetRule.ID,
                Map.of("knownTables", List.of(" app.t ")))).isEmpty());
    }

    @Test
    void rejectsAMalformedDynamicSqlAllowList() {
        LinterConfig notAList = config(Map.of("allowedRoutines", "app.foo"));
        assertThrows(IllegalArgumentException.class, () -> Rules.all(notAList));

        LinterConfig nonString = config(Map.of("allowedRoutines", List.of(1)));
        assertThrows(IllegalArgumentException.class, () -> Rules.all(nonString));

        LinterConfig blank = config(Map.of("allowedRoutines", List.of(" ")));
        assertThrows(IllegalArgumentException.class, () -> Rules.all(blank));
    }
}
