package io.github.eyupmiduck.changelogvalidator.testing;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Minimal Maven-style version ordering, enough to select a release version from
 * a {@code maven-metadata.xml} listing.
 *
 * <p>The full Maven algorithm ({@code org.apache.maven.artifact.versioning.ComparableVersion})
 * is intentionally not used: the resolved classes ship in the published
 * {@code tests} classifier, so pulling Maven's artifact library would become an
 * undeclared consumer dependency. The versions this compares are project
 * releases of the form {@code MAJOR.MINOR.PATCH}, optionally with a
 * {@code -SNAPSHOT} or other qualifier suffix.
 *
 * <p>Token comparison follows the useful part of Maven's rules: the version is
 * split on {@code .}, {@code -} and {@code _}; a missing token is treated as
 * {@code 0} (so {@code 1.5 < 1.5.1}); numeric tokens compare numerically and
 * outrank qualifiers (so {@code 1.5.2-SNAPSHOT < 1.5.2}).
 */
final class MavenVersion {

    private static final Pattern SEPARATOR = Pattern.compile("[.\\-_]");
    private static final Pattern SNAPSHOT = Pattern.compile(".*(?:^|[-._])SNAPSHOT(?:$|[-._]).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QUALIFIER = Pattern.compile("([a-z]+)(\\d*)");

    private MavenVersion() {
    }

    /**
     * Returns whether a version string is a snapshot.
     *
     * @param version the version string
     * @return {@code true} when the version names a {@code SNAPSHOT}
     */
    static boolean isSnapshot(String version) {
        return version != null && SNAPSHOT.matcher(version).matches();
    }

    /**
     * Returns whether a version is a plain numeric release ({@code MAJOR.MINOR}
     * or {@code MAJOR.MINOR.PATCH}, and so on) with no pre-release or snapshot
     * qualifier. Used when selecting a previous release: an RC, beta or snapshot
     * is not the last shipped release.
     *
     * @param version the version string
     * @return {@code true} for a plain numeric release
     */
    static boolean isRelease(String version) {
        return version != null && version.matches("\\d+(\\.\\d+)*");
    }

    /**
     * Compares two version strings.
     *
     * @param left  the left version
     * @param right the right version
     * @return a negative integer, zero or a positive integer as {@code left} is
     * less than, equal to or greater than {@code right}
     */
    static int compare(String left, String right) {
        String[] leftTokens = SEPARATOR.split(left.toLowerCase(Locale.ROOT));
        String[] rightTokens = SEPARATOR.split(right.toLowerCase(Locale.ROOT));
        int length = Math.max(leftTokens.length, rightTokens.length);
        for (int i = 0; i < length; i++) {
            // A missing trailing token is Maven's implicit zero.
            int comparison = compareToken(token(leftTokens, i), token(rightTokens, i));
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static String token(String[] tokens, int index) {
        return index < tokens.length ? tokens[index] : "0";
    }

    private static int compareToken(String left, String right) {
        boolean leftNumeric = isNumeric(left);
        boolean rightNumeric = isNumeric(right);
        if (leftNumeric && rightNumeric) {
            return compareNumeric(left, right);
        }
        // A numeric token outranks a qualifier: 1.5.2 > 1.5.2-RC1.
        if (leftNumeric) {
            return 1;
        }
        if (rightNumeric) {
            return -1;
        }
        return compareQualifier(left, right);
    }

    /**
     * Compares two qualifier tokens, comparing a trailing numeric part
     * numerically so {@code RC2 < RC10}. Tokens that do not have a recognized
     * {@code letters + digits} shape fall back to lexical order.
     */
    private static int compareQualifier(String left, String right) {
        java.util.regex.Matcher leftMatcher = QUALIFIER.matcher(left);
        java.util.regex.Matcher rightMatcher = QUALIFIER.matcher(right);
        if (!leftMatcher.matches() || !rightMatcher.matches()) {
            return left.compareTo(right);
        }
        int prefix = leftMatcher.group(1).compareTo(rightMatcher.group(1));
        if (prefix != 0) {
            return prefix;
        }
        String leftNumber = leftMatcher.group(2);
        String rightNumber = rightMatcher.group(2);
        if (leftNumber.isEmpty() || rightNumber.isEmpty()) {
            return leftNumber.isEmpty() ? -1 : 1;
        }
        return compareNumeric(leftNumber, rightNumber);
    }

    /**
     * Compares arbitrary-length numeric tokens without overflow, ignoring
     * leading zeros.
     */
    private static int compareNumeric(String left, String right) {
        String normalizedLeft = stripLeadingZeros(left);
        String normalizedRight = stripLeadingZeros(right);
        if (normalizedLeft.length() != normalizedRight.length()) {
            return Integer.compare(normalizedLeft.length(), normalizedRight.length());
        }
        return normalizedLeft.compareTo(normalizedRight);
    }

    private static String stripLeadingZeros(String token) {
        int first = 0;
        while (first < token.length() - 1 && token.charAt(first) == '0') {
            first++;
        }
        return token.substring(first);
    }

    private static boolean isNumeric(String token) {
        if (token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
