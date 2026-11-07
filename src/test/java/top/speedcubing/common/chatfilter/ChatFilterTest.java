package top.speedcubing.common.chatfilter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChatFilterTest {
    private static final ChatFilter FILTER = ChatFilter.compile(List.of(
            new FilterEntry("fuck", FilterTier.MASK, false),
            new FilterEntry("shit", FilterTier.MASK, true),
            new FilterEntry("anal", FilterTier.MASK, true),
            new FilterEntry("boob", FilterTier.MASK, true),
            new FilterEntry("cunt", FilterTier.MASK, false),
            new FilterEntry("scunthorpe", FilterTier.ALLOW, false),
            new FilterEntry("td", FilterTier.BLOCK, true),
            new FilterEntry("kys", FilterTier.BLOCK, true),
            new FilterEntry("kill yourself", FilterTier.BLOCK, true),
            new FilterEntry("台獨", FilterTier.BLOCK, false),
            new FilterEntry("幹", FilterTier.MASK, false),
            new FilterEntry("幹嘛", FilterTier.ALLOW, false),
            new FilterEntry("他媽的", FilterTier.MASK, false)));

    private static void clean(String message) {
        FilterResult r = FILTER.check(message);
        assertFalse(r.isBlocked(), message);
        assertFalse(r.isMasked(), message);
        assertSame(message, r.masked(), message);
        assertTrue(r.blockedTerms().isEmpty(), message);
    }

    private static void masked(String message, String expected) {
        FilterResult r = FILTER.check(message);
        assertFalse(r.isBlocked(), message);
        assertTrue(r.isMasked(), message);
        assertEquals(expected, r.masked());
    }

    private static void blocked(String message, String term) {
        FilterResult r = FILTER.check(message);
        assertTrue(r.isBlocked(), message);
        assertEquals(List.of(term), r.blockedTerms());
    }

    @Test
    void masksExactPositions() {
        masked("what the fuck man", "what the **** man");
        masked("fuck", "****");
        masked("oh shit, really?", "oh ****, really?");
        masked("motherfucker", "mother****er");
    }

    @Test
    void ignoresCase() {
        masked("FuCk", "****");
        masked("SHIT", "****");
    }

    @Test
    void foldsLeet() {
        masked("sh1t", "****");
        masked("5h!t", "****");
        masked("sh!t happens", "**** happens");
    }

    @Test
    void foldsFullWidth() {
        masked("ｆｕｃｋ", "****");
        masked("ＳＨＩＴ ok", "**** ok");
    }

    @Test
    void foldsLookAlikes() {
        masked("fuсk", "****"); // Cyrillic es
        masked("ѕhіt", "****"); // Cyrillic dze, i
        masked("fúck", "****");
    }

    @Test
    void ignoresInvisibleCharacters() {
        masked("fu\u200Bck", "*****");
    }

    @Test
    void joinsIsolatedLetters() {
        masked("f u c k", "* * * *");
        masked("f.u.c.k", "*******");
        masked("f_u-c*k you", "******* you");
        blocked("k y s", "kys");
        blocked("t.d", "td");
    }

    @Test
    void collapsesRepeats() {
        masked("fuuuuck", "*******");
        masked("ffuucckk", "********");
        masked("shiiiit", "*******");
    }

    @Test
    void repeatsRequiredByTermMustBePresent() {
        clean("bob is here");
        masked("boob", "****");
        masked("booooob", "*******");
    }

    @Test
    void wholeWordBoundaries() {
        blocked("td", "td");
        blocked("TD!", "td");
        blocked("go td now", "td");
        clean("outdated");
        clean("std");
        clean("td2");
        clean("analysis");
        clean("canal");
        masked("anal", "****");
        clean("ashit");
        blocked("kys!", "kys");
        clean("kysa");
    }

    @Test
    void doesNotJoinNormalWords() {
        clean("this is hit");
        clean("an all");
        clean("a nal");
        clean("fu ck");
        clean("t dd");
    }

    @Test
    void multiWordTerms() {
        blocked("just kill yourself", "kill yourself");
        blocked("kill   yourself", "kill yourself");
        clean("killyourself");
    }

    @Test
    void allowSuppressesInnerHits() {
        clean("I live in Scunthorpe");
        masked("scunthorpe cunt", "scunthorpe ****");
    }

    @Test
    void chineseSubstrings() {
        blocked("我支持台獨", "台獨");
        blocked("台 獨", "台獨");
        clean("你在幹嘛");
        masked("幹你", "*你");
        masked("幹嘛 幹", "幹嘛 *");
    }

    @Test
    void cleanMessageReturnsSameInstance() {
        String message = "hello there, gg wp everyone!";
        assertSame(message, FILTER.check(message).masked());
        clean("");
        clean("   ");
    }

    @Test
    void doesNotCollapseChineseReduplication() {
        clean("他媽媽的手機");
        masked("他媽的", "***");
    }

    @Test
    void leetDigitsAloneDoNotHit() {
        clean("ban 7d");
        clean("mute 7d please");
        blocked("ky5", "kys");
        masked("5h1t", "****");
    }

    @Test
    void blockAlsoMasks() {
        FilterResult r = FILTER.check("kys fuck");
        assertTrue(r.isBlocked());
        assertTrue(r.isMasked());
        assertEquals("*** ****", r.masked());
    }

    @Test
    void emptyFilter() {
        ChatFilter empty = ChatFilter.compile(List.of());
        String message = "fuck td";
        FilterResult r = empty.check(message);
        assertFalse(r.isBlocked());
        assertFalse(r.isMasked());
        assertSame(message, r.masked());
        assertEquals(0, empty.size());
    }

    @Test
    void parsesListFormat() throws Exception {
        String list = "# comment\n\nmask sub fuck # note\nblock word kill yourself\nbogus word x\nmask maybe y\nallow\nallow sub ...\n";
        List<FilterEntry> entries = new java.util.ArrayList<>();
        FilterListLoader.parse("t.txt", new java.io.BufferedReader(new java.io.StringReader(list)), entries,
                java.util.logging.Logger.getLogger("test"));
        assertEquals(List.of(new FilterEntry("fuck", FilterTier.MASK, false),
                new FilterEntry("kill yourself", FilterTier.BLOCK, true)), entries);
    }
}
