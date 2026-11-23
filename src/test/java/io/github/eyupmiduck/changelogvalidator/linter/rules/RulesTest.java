package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.ProjectRule;
import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void registersEveryRuleImplementationInThePackage() throws Exception {
        Set<String> registered = Rules.all(null).stream()
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
}
