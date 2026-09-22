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

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The linter command line.
 *
 * <p>Usage: {@code liquibase-linter --changelog-root DIR [--master FILE]
 * [--config FILE] [--reporter tty|json|sarif] [--fail-on error|warning|info|none]}.
 *
 * <p>The exit code is 0 when the run passes, 1 when it fails the {@code failOn}
 * threshold, and 2 for a usage or runtime error.
 */
public final class LinterCli {

    private static final String USAGE = """
            Usage: liquibase-linter --changelog-root DIR [options]

              -r, --changelog-root DIR   changelog directory (required)
              -m, --master FILE          master changelog (default DIR/db.changelog-master.xml)
              -c, --config FILE          config file (default .liquibase-linter.yml)
              -w, --whitelist FILE       accepted findings (default .liquibase-linter-whitelist.yml)
                  --reporter FORMAT      tty (default), json or sarif
                  --fail-on SEVERITY     error (default), warning, info or none
              -h, --help                 print this help

            """;

    private LinterCli() {
    }

    /**
     * Runs the linter and exits with its status.
     *
     * @param args the command-line arguments
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs the linter.
     *
     * @param args the command-line arguments
     * @param out  where findings are written
     * @param err  where errors are written
     * @return the exit code: 0 pass, 1 findings at or above {@code failOn}, 2 error
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            err.print(USAGE);
            return 2;
        }
        if (options.help()) {
            out.print(USAGE);
            return 0;
        }
        try {
            LinterConfig config = LinterConfigLoader.load(options.config());
            if (options.failOn() != null) {
                config = config.withFailOn(options.failOn());
            }
            List<ChangeSet> changeSets = ChangelogModel.changesets(options.changelogRoot(), options.master());
            Linter linter = new Linter(Rules.all(majorVersion(config.pgVersion())), config);
            List<Finding> findings = linter.lint(changeSets);
            Whitelist.Report report = Whitelist.load(options.whitelist()).apply(findings);
            options.reporter().report(report.unmatched(), out);
            if (!report.stale().isEmpty()) {
                for (Whitelist.AllowedFinding entry : report.stale()) {
                    err.println("stale whitelist entry: " + entry.describe());
                }
                return 1;
            }
            return options.ignoreFailures() || !linter.fails(report.unmatched()) ? 0 : 1;
        } catch (IOException | RuntimeException e) {
            err.println("error: " + e.getMessage());
            return 2;
        }
    }

    private static Integer majorVersion(String pgVersion) {
        if (pgVersion == null || pgVersion.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(pgVersion.strip().split("\\.")[0]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The parsed command-line options.
     */
    private record Options(Path changelogRoot, Path master, Path config, Path whitelist, Reporter reporter,
                           Severity failOn, boolean ignoreFailures, boolean help) {

        private static Options parse(String[] args) {
            Path changelogRoot = null;
            Path master = null;
            Path config = Path.of(".liquibase-linter.yml");
            Path whitelist = Path.of(".liquibase-linter-whitelist.yml");
            Reporter reporter = new TtyReporter();
            Severity failOn = null;
            boolean ignoreFailures = false;
            boolean help = false;
            for (int i = 0; i < args.length; i++) {
                String argument = args[i];
                switch (argument) {
                    case "-r", "--changelog-root" -> changelogRoot = Path.of(value(args, ++i, argument));
                    case "-m", "--master" -> master = Path.of(value(args, ++i, argument));
                    case "-c", "--config" -> config = Path.of(value(args, ++i, argument));
                    case "-w", "--whitelist" -> whitelist = Path.of(value(args, ++i, argument));
                    case "--reporter" -> reporter = reporter(value(args, ++i, argument));
                    case "--fail-on" -> {
                        String severity = value(args, ++i, argument);
                        if ("none".equalsIgnoreCase(severity)) {
                            ignoreFailures = true;
                        } else {
                            failOn = Severity.from(severity);
                        }
                    }
                    case "-h", "--help" -> help = true;
                    default -> throw new IllegalArgumentException("unknown option: " + argument);
                }
            }
            if (!help && changelogRoot == null) {
                throw new IllegalArgumentException("--changelog-root is required");
            }
            Path masterFile = master != null
                    ? master
                    : changelogRoot == null ? null : changelogRoot.resolve("db.changelog-master.xml");
            return new Options(changelogRoot, masterFile, config, whitelist, reporter, failOn, ignoreFailures, help);
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("missing value for " + option);
            }
            return args[index];
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
