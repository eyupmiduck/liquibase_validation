package io.github.eyupmiduck.changelogvalidator.linter.report;

import io.github.eyupmiduck.changelogvalidator.linter.Finding;

import java.io.IOException;
import java.util.List;

/**
 * Renders linter findings in a specific format.
 *
 * <p>Implementations must treat both arguments as non-null, must not close or
 * flush the supplied {@link Appendable} (the caller owns it), and may have
 * written partial output when an {@link IOException} is thrown. Callers that
 * need all-or-nothing output should render into a buffer or a temporary file.
 */
public interface Reporter {

    /**
     * Writes {@code findings} to {@code out}.
     *
     * @param findings the findings to render; never null
     * @param out      the destination; never null and not closed by the reporter
     * @throws IOException if writing fails, possibly after partial output
     */
    void report(List<Finding> findings, Appendable out) throws IOException;
}
