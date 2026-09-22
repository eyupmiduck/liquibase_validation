package io.github.eyupmiduck.changelogvalidator.linter.report;

import io.github.eyupmiduck.changelogvalidator.linter.Finding;

import java.io.IOException;
import java.util.List;

/**
 * Renders linter findings in a specific format.
 */
public interface Reporter {

    /**
     * Writes {@code findings} to {@code out}.
     *
     * @param findings the findings to render
     * @param out      the destination
     * @throws IOException if writing fails
     */
    void report(List<Finding> findings, Appendable out) throws IOException;
}
