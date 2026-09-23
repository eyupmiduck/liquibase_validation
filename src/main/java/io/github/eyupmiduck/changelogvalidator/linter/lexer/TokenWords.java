package io.github.eyupmiduck.changelogvalidator.linter.lexer;

import java.util.List;

/**
 * Keyword matching over an ordered list of a statement's word tokens, shared by
 * the rules and the transaction-forbidden classifier so their keyword logic
 * cannot drift.
 *
 * <p>Matching is case-insensitive via {@link Token#matchesKeyword(String)}, so a
 * keyword inside a string or comment (which is not a word token) is not matched.
 */
public final class TokenWords {

    private TokenWords() {
    }

    /**
     * Returns the non-trivia tokens within an offset range, for example a
     * statement's span.
     *
     * @param tokens      the SQL's tokens
     * @param startOffset the inclusive start offset
     * @param endOffset   the exclusive end offset
     * @return the non-trivia tokens in the range, in order
     */
    public static List<Token> within(List<Token> tokens, int startOffset, int endOffset) {
        return tokens.stream()
                .filter(token -> !token.isTrivia())
                .filter(token -> token.startOffset() >= startOffset && token.endOffset() <= endOffset)
                .toList();
    }

    /**
     * Returns whether {@code words} begin with the keyword sequence.
     *
     * @param words    the word tokens
     * @param keywords the keywords, in order
     * @return {@code true} when the sequence matches the start
     */
    public static boolean startsWith(List<Token> words, String... keywords) {
        if (words.size() < keywords.length) {
            return false;
        }
        for (int i = 0; i < keywords.length; i++) {
            if (!words.get(i).matchesKeyword(keywords[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether {@code words} contain the keyword anywhere.
     *
     * @param words   the word tokens
     * @param keyword the keyword
     * @return {@code true} when the keyword is present
     */
    public static boolean contains(List<Token> words, String keyword) {
        return indexOf(words, keyword, 0) >= 0;
    }

    /**
     * Returns whether {@code words} contain any of the keywords.
     *
     * @param words    the word tokens
     * @param keywords the keywords
     * @return {@code true} when at least one keyword is present
     */
    public static boolean containsAny(List<Token> words, String... keywords) {
        for (String keyword : keywords) {
            if (contains(words, keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether {@code first} is immediately followed by {@code second}.
     *
     * @param words  the word tokens
     * @param first  the first keyword
     * @param second the second keyword
     * @return {@code true} when the two keywords are adjacent, in order
     */
    public static boolean adjacent(List<Token> words, String first, String second) {
        for (int i = 0; i + 1 < words.size(); i++) {
            if (words.get(i).matchesKeyword(first) && words.get(i + 1).matchesKeyword(second)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the index of the first keyword at or after {@code from}.
     *
     * @param words   the word tokens
     * @param keyword the keyword
     * @param from    the index to start at
     * @return the index, or {@code -1} when the keyword is absent
     */
    public static int indexOf(List<Token> words, String keyword, int from) {
        for (int i = from; i < words.size(); i++) {
            if (words.get(i).matchesKeyword(keyword)) {
                return i;
            }
        }
        return -1;
    }
}
