package io.github.eyupmiduck.changelogvalidator.linter.cli;

import io.github.eyupmiduck.changelogvalidator.linter.Finding;
import io.github.eyupmiduck.changelogvalidator.linter.Linter;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfig;
import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfigLoader;
import io.github.eyupmiduck.changelogvalidator.linter.config.Whitelist;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangelogModel;
import io.github.eyupmiduck.changelogvalidator.linter.report.JsonReporter;
import io.github.eyupmiduck.changelogvalidator.linter.report.Reporter;
import io.github.eyupmiduck.changelogvalidator.linter.report.SarifReporter;
import io.github.eyupmiduck.changelogvalidator.linter.report.TtyReporter;
import io.github.eyupmiduck.changelogvalidator.linter.rules.Rules;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.*;

/**
 * The linter command line.
 *
 * <p>Usage: {@code liquibase-linter --changelog-root DIR [--master FILE]
 * [--config FILE] [--reporter tty|json|sarif] [--fail-on error|warning|info|none]}.
 * {@code --list-rules} and {@code --version} are informational and do not need a
 * changelog root.
 *
 * <p>The exit code is 0 when the run passes, 1 when findings reach the
 * {@code failOn} threshold or a whitelist entry is stale (0 under
 * {@code --fail-on none}), and 2 for a usage or runtime error.
 */
public final class LinterCli {

    private static final Set<String> OPTION_NAMES = Set.of(
            "-r", "--changelog-root", "-m", "--master", "-c", "--config", "-w", "--whitelist",
            "--reporter", "--fail-on", "--update-whitelist", "--list-rules", "--version", "-h", "--help");

    private static final String USAGE = """
            Usage: liquibase-linter --changelog-root DIR [options]
            
              -r, --changelog-root DIR   changelog directory (required)
              -m, --master FILE          master changelog (default DIR/db.changelog-master.xml)
              -c, --config FILE          config file (default .liquibase-linter.yml)
              -w, --whitelist FILE       accepted findings (default .liquibase-linter-whitelist.yml)
                  --reporter FORMAT      tty (default), json or sarif
                  --fail-on SEVERITY     error (default), warning, info or none
                  --update-whitelist     rewrite the whitelist from current findings, printing
                                         added and removed entries; review the diff
                  --list-rules           print the registered rules and exit
                  --version              print the version and exit
                  --                 end of options; no operands are accepted
              -h, --help                 print this help
            
            The master may be XML or a Liquibase formatted SQL changelog. The graph
            follows <include>, <includeAll> and formatted SQL changesets.
            Inline suppressions use: -- liquibase-linter:ignore [rule[,rule]] reason=...
            """;

    private LinterCli() {
    }

    /**
     * Runs the linter and exits with its status.
     *
     * @param args the command-line arguments
     */
    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        // JSON/SARIF reporters do not end with a newline, so auto-flush does not
        // fire; flush explicitly before System.exit discards buffered output.
        System.out.flush();
        System.err.flush();
        System.exit(code);
    }

    /**
     * Runs the linter.
     *
     * @param args the command-line arguments
     * @param out  where findings are written
     * @param err  where errors, the summary and the update report are written
     * @return the exit code: 0 pass, 1 findings at or above {@code failOn}, 2 error
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("error: " + describe(e));
            err.print(USAGE);
            return 2;
        }
        if (options.help()) {
            out.print(USAGE);
            return 0;
        }
        if (options.version()) {
            out.println("liquibase-linter " + version());
            return 0;
        }
        try {
            LinterConfig config = LinterConfigLoader.load(options.config());
            if (options.failOn() != null) {
                config = config.withFailOn(options.failOn());
            }
            if (options.listRules()) {
                return listRules(config, options.reporter(), out, err);
            }
            boolean ignoreFailures = options.ignoreFailures() || config.failOn() == null;
            List<ChangeSet> changeSets = ChangelogModel.changesets(options.changelogRoot(), options.master());
            if (changeSets.isEmpty()) {
                err.println("error: no changesets found in " + options.master());
                return 2;
            }
            Linter linter = new Linter(Rules.all(config), Rules.projectRules(), Rules.projectAnalysisRules(config), config);
            List<Finding> findings = linter.lint(changeSets);
            if (options.updateWhitelist()) {
                return updateWhitelist(options, findings, out, err);
            }
            Whitelist.Report report = Whitelist.load(options.whitelist()).apply(findings);
            options.reporter().report(report.unmatched(), out);
            summarize(report.unmatched(), err);
            if (!report.stale().isEmpty()) {
                for (Whitelist.AllowedFinding entry : report.stale()) {
                    err.println("stale whitelist entry: " + entry.describe());
                }
                return ignoreFailures ? 0 : 1;
            }
            return ignoreFailures || !linter.fails(report.unmatched()) ? 0 : 1;
        } catch (IOException | RuntimeException e) {
            err.println("error: " + describe(e));
            return 2;
        }
    }

    private static int listRules(LinterConfig config, Reporter reporter, PrintStream out, PrintStream err)
            throws IOException {
        if (reporter instanceof SarifReporter) {
            err.println("error: --list-rules does not support the sarif reporter");
            return 2;
        }
        List<RuleCatalog.Entry> entries = RuleCatalog.entries(config);
        if (reporter instanceof JsonReporter) {
            RuleCatalog.writeJson(entries, out);
        } else {
            RuleCatalog.writeTable(entries, out);
        }
        return 0;
    }

    /**
     * Rewrites the whitelist from the current findings, preserving entries that
     * still match and printing every added and removed entry. The new whitelist
     * is checked for ambiguity before it is written, so a baseline update cannot
     * silently hide a finding behind an overlapping entry.
     */
    private static int updateWhitelist(Options options, List<Finding> findings, PrintStream out, PrintStream err)
            throws IOException {
        Whitelist existing = Whitelist.load(options.whitelist());
        Whitelist.Report report = existing.apply(findings);
        List<Whitelist.AllowedFinding> kept = new ArrayList<>(existing.entries());
        kept.removeAll(report.stale());
        List<Whitelist.AllowedFinding> added = baselineEntries(report.unmatched(), options.changelogRoot());
        List<Whitelist.AllowedFinding> updated = new ArrayList<>(kept);
        updated.addAll(added);
        // Fail before writing if the new entries overlap, so the update cannot
        // create an ambiguous whitelist.
        Whitelist.of(updated).apply(findings);
        if (added.isEmpty() && report.stale().isEmpty()) {
            err.println("whitelist unchanged: " + count(kept.size(), "entry", "entries"));
            return 0;
        }
        Whitelist.write(updated, options.whitelist());
        err.println("whitelist updated: " + count(added.size(), "entry", "entries") + " added, "
                + count(report.stale().size(), "entry", "entries") + " removed, "
                + count(kept.size(), "entry", "entries") + " unchanged");
        for (Whitelist.AllowedFinding entry : added) {
            err.println("  + " + entry.describe());
        }
        for (Whitelist.AllowedFinding entry : report.stale()) {
            err.println("  - " + entry.describe());
        }
        if (!report.unmatched().isEmpty()) {
            err.println("accepted findings:");
            options.reporter().report(report.unmatched(), out);
        }
        return 0;
    }

    private static List<Whitelist.AllowedFinding> baselineEntries(List<Finding> findings, Path changelogRoot) {
        Path root = changelogRoot.toAbsolutePath().normalize();
        Map<String, List<Finding>> groups = new LinkedHashMap<>();
        for (Finding finding : findings) {
            String file = baselineFile(finding.file(), root);
            String key = finding.ruleId() + "\0" + file + "\0" + nullable(finding.changeSetId());
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(finding);
        }
        Set<String> seen = new HashSet<>();
        List<Whitelist.AllowedFinding> entries = new ArrayList<>();
        for (List<Finding> group : groups.values()) {
            Finding first = group.getFirst();
            String file = baselineFile(first.file(), root);
            String changeSet = nullable(first.changeSetId());
            boolean allHaveStatement = group.stream()
                    .allMatch(finding -> finding.statement() != null && !finding.statement().isBlank());
            if (!allHaveStatement) {
                addEntry(entries, seen, new Whitelist.AllowedFinding(first.ruleId(), file, changeSet,
                        null, reason(first)));
            } else {
                Set<String> statements = new LinkedHashSet<>();
                for (Finding finding : group) {
                    statements.add(finding.statement());
                }
                for (String statement : statements) {
                    addEntry(entries, seen, new Whitelist.AllowedFinding(first.ruleId(), file, changeSet,
                            statement, reason(first)));
                }
            }
        }
        return entries;
    }

    private static void addEntry(List<Whitelist.AllowedFinding> entries, Set<String> seen,
                                 Whitelist.AllowedFinding entry) {
        String key = entry.rule() + "\0" + entry.file() + "\0" + entry.changeset() + "\0" + entry.statement();
        if (seen.add(key)) {
            entries.add(entry);
        }
    }

    static String baselineFile(Path file, Path root) {
        Path normalized = file.toAbsolutePath().normalize();
        if (normalized.startsWith(root)) {
            return root.relativize(normalized).toString().replace(File.separatorChar, '/');
        }
        // Keep the parent directory so two external files with the same name do
        // not collapse into one over-broad suffix-matching whitelist entry.
        Path parent = normalized.getParent();
        Path parentName = parent == null ? null : parent.getFileName();
        return parentName == null ? normalized.getFileName().toString()
                : parentName + "/" + normalized.getFileName();
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String reason(Finding finding) {
        String message = finding.message().replaceAll("\\R", " ").strip();
        return message.isEmpty() ? "baseline" : "baseline: " + message;
    }

    private static void summarize(List<Finding> findings, PrintStream err) {
        if (findings.isEmpty()) {
            err.println("no findings");
            return;
        }
        long errors = findings.stream().filter(finding -> finding.severity() == Severity.ERROR).count();
        long warnings = findings.stream().filter(finding -> finding.severity() == Severity.WARNING).count();
        long infos = findings.stream().filter(finding -> finding.severity() == Severity.INFO).count();
        err.println(count(findings.size(), "finding", "findings") + ": "
                + count(errors, "error", "errors") + ", "
                + count(warnings, "warning", "warnings") + ", "
                + count(infos, "info", "info"));
    }

    private static String count(long count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }

    private static String version() {
        try (InputStream input = LinterCli.class.getResourceAsStream("version.properties")) {
            if (input != null) {
                Properties properties = new Properties();
                properties.load(input);
                String version = properties.getProperty("version");
                if (version != null && !version.isBlank() && !version.contains("${")) {
                    return version;
                }
            }
        } catch (IOException e) {
            // Fall back to the manifest or "unknown" below.
        }
        String manifest = LinterCli.class.getPackage().getImplementationVersion();
        return manifest == null || manifest.isBlank() ? "unknown" : manifest;
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.toString() : message;
    }

    /**
     * The parsed command-line options.
     */
    private record Options(Path changelogRoot, Path master, Path config, Path whitelist, Reporter reporter,
                           Severity failOn, boolean ignoreFailures, boolean help, boolean version,
                           boolean listRules, boolean updateWhitelist) {

        private static Options parse(String[] args) {
            Path changelogRoot = null;
            Path master = null;
            Path config = Path.of(".liquibase-linter.yml");
            Path whitelist = Path.of(".liquibase-linter-whitelist.yml");
            Reporter reporter = new TtyReporter();
            Severity failOn = null;
            boolean ignoreFailures = false;
            boolean help = false;
            boolean version = false;
            boolean listRules = false;
            boolean updateWhitelist = false;
            boolean endOfOptions = false;
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < args.length; i++) {
                String argument = args[i];
                if (endOfOptions) {
                    throw new IllegalArgumentException("unexpected argument after --: " + argument);
                }
                if ("--".equals(argument)) {
                    // No operands are accepted: "--" only asserts that no more
                    // options follow, and any later token is rejected above.
                    endOfOptions = true;
                    continue;
                }
                switch (argument) {
                    case "-r", "--changelog-root" -> {
                        mark(seen, "changelog-root", argument);
                        changelogRoot = Path.of(value(args, ++i, argument));
                    }
                    case "-m", "--master" -> {
                        mark(seen, "master", argument);
                        master = Path.of(value(args, ++i, argument));
                    }
                    case "-c", "--config" -> {
                        mark(seen, "config", argument);
                        config = Path.of(value(args, ++i, argument));
                    }
                    case "-w", "--whitelist" -> {
                        mark(seen, "whitelist", argument);
                        whitelist = Path.of(value(args, ++i, argument));
                    }
                    case "--reporter" -> {
                        mark(seen, "reporter", argument);
                        reporter = reporter(value(args, ++i, argument));
                    }
                    case "--fail-on" -> {
                        mark(seen, "fail-on", argument);
                        String severity = value(args, ++i, argument);
                        if ("none".equalsIgnoreCase(severity)) {
                            ignoreFailures = true;
                        } else {
                            failOn = Severity.from(severity);
                        }
                    }
                    case "--update-whitelist" -> updateWhitelist = true;
                    case "--list-rules" -> listRules = true;
                    case "--version" -> version = true;
                    // Repeating help is harmless; only value-taking options are
                    // required to be unique.
                    case "-h", "--help" -> help = true;
                    default -> throw new IllegalArgumentException("unknown option: " + argument);
                }
            }
            if (!help && !version && !listRules && changelogRoot == null) {
                throw new IllegalArgumentException("--changelog-root is required");
            }
            Path masterFile = master != null
                    ? master
                    : changelogRoot == null ? null : changelogRoot.resolve("db.changelog-master.xml");
            return new Options(changelogRoot, masterFile, config, whitelist, reporter, failOn, ignoreFailures,
                    help, version, listRules, updateWhitelist);
        }

        private static void mark(Set<String> seen, String canonical, String argument) {
            if (!seen.add(canonical)) {
                throw new IllegalArgumentException("duplicate option: " + argument);
            }
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("missing value for " + option);
            }
            String value = args[index];
            if ("--".equals(value) || OPTION_NAMES.contains(value)) {
                throw new IllegalArgumentException("missing value for " + option + ": " + value + " is an option");
            }
            return value;
        }

        private static Reporter reporter(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "tty" -> new TtyReporter();
                case "json" -> new JsonReporter();
                case "sarif" -> new SarifReporter();
                default -> throw new IllegalArgumentException("unknown reporter: " + value);
            };
        }
    }
}
