package top.speedcubing.common.chatfilter;

/**
 * One filter list entry.
 *
 * @param term      plain lowercase term; normalized by the engine the same way messages are
 * @param tier      what a hit does
 * @param wholeWord true if the hit must not touch a letter/digit on either side in the original text
 */
public record FilterEntry(String term, FilterTier tier, boolean wholeWord) {
}
