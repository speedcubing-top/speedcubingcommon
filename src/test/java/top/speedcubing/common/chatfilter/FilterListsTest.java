package top.speedcubing.common.chatfilter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FilterListsTest {
    private static ChatFilter filter;

    @BeforeAll
    static void load() {
        // the dictionary files shipped to /storage/chatfilter/ live in the repo's chatfilter/ directory
        ChatFilter.load(Paths.get("chatfilter"));
        filter = ChatFilter.get();
    }

    @Test
    void loadsEntries() {
        assertTrue(filter.size() > 100, "lists loaded " + filter.size() + " entries");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "gay", "gays", "lesbian", "lgbt", "lgbtq", "queer", "trans", "bi", "jew", "jewish", "muslim", "christian",
            "black", "white", "asian", "chinese", "indian", "arab", "woman", "girl", "autistic", "disabled",
            "i am gay and proud", "black and white", "my asian friend",
            "damn", "crap", "wtf", "hell", "ass", "pissed", "sucks", "noob", "trash", "idiot", "stupid",
            "what the hell noob", "damn that sucks",
            "outdated", "analysis", "analytics", "analyst", "banal", "class", "assassin", "bass", "canal", "scunthorpe", "ban 7d", "this is hit",
            "an all", "gg wp", "你在幹嘛", "他媽媽的手機"})
    void passesUntouched(String message) {
        FilterResult r = filter.check(message);
        assertFalse(r.isBlocked(), message + " blocked by " + r.blockedTerms());
        assertFalse(r.isMasked(), message + " masked as " + r.masked());
        assertSame(message, r.masked(), message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fuck", "shit", "fuck you", "holy shit", "f u c k", "sh1t", "fuuuck"})
    void masksProfanity(String message) {
        FilterResult r = filter.check(message);
        assertFalse(r.isBlocked(), message + " blocked by " + r.blockedTerms());
        assertTrue(r.isMasked(), message);
        assertFalse(r.masked().equals(message), message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"nigger", "faggot", "tranny", "retard", "kys", "k y s", "td", "go td", "台獨", "台独", "kill yourself"})
    void blocksSlursAndThreats(String message) {
        FilterResult r = filter.check(message);
        assertTrue(r.isBlocked(), message);
        assertFalse(r.blockedTerms().isEmpty(), message);
    }
}
