package io.github.eyupmiduck.changelogvalidator.linter.lexer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the shared keyword-matching helpers in {@link TokenWords}.
 */
class TokenWordsTest {

    private static List<Token> tokens(String sql) {
        return TokenWords.within(SqlLexer.tokenize(sql), 0, sql.length());
    }

    /**
     * {@code startsWith}, {@code contains}, {@code containsAny} and
     * {@code indexOf} match case-insensitively.
     */
    @Test
    void matchesStartsWithContainsAndAny() {
        List<Token> words = tokens("CREATE UNIQUE INDEX idx ON t (c)");

        assertTrue(TokenWords.startsWith(words, "CREATE", "UNIQUE"));
        assertFalse(TokenWords.startsWith(words, "CREATE", "INDEX"));
        assertTrue(TokenWords.contains(words, "index"));
        assertTrue(TokenWords.containsAny(words, "DROP", "INDEX"));
        assertFalse(TokenWords.containsAny(words, "DROP", "VACUUM"));
        assertEquals(2, TokenWords.indexOf(words, "INDEX", 0));
        assertEquals(-1, TokenWords.indexOf(words, "DROP", 0));
    }

    /**
     * {@code adjacent} requires the two keywords to be next to each other.
     */
    @Test
    void matchesAdjacentExactly() {
        List<Token> words = tokens("CREATE INDEX idx ON t (concurrently)");

        assertFalse(TokenWords.adjacent(words, "INDEX", "CONCURRENTLY"));
        assertTrue(TokenWords.adjacent(words, "CREATE", "INDEX"));

        List<Token> concurrent = tokens("DROP INDEX CONCURRENTLY idx");
        assertTrue(TokenWords.adjacent(concurrent, "INDEX", "CONCURRENTLY"));
    }

    /**
     * {@code within} keeps only non-trivia tokens inside the offset range.
     */
    @Test
    void withinFiltersTriviaAndTheSpan() {
        String sql = "SELECT /* c */ a, b";
        List<Token> all = SqlLexer.tokenize(sql);

        List<Token> within = TokenWords.within(all, 0, sql.length());
        assertFalse(within.isEmpty());
        assertTrue(within.stream().noneMatch(Token::isTrivia));

        assertEquals(List.of("SELECT"), TokenWords.within(all, 0, 6).stream().map(Token::text).toList());
    }
}
