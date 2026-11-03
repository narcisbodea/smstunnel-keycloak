package io.smstunnel.keycloak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class CodeChallengeTest {

    private final Map<String, String> notes = new HashMap<>();
    private final Fakes.Clock clock = new Fakes.Clock();
    private final CodeChallenge codes = new CodeChallenge(Fakes.authSession(notes, new LinkedHashSet<>()), "t.", clock::get);

    @Test
    void makesCodesOfTheAskedLengthFromDigits() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String code = CodeChallenge.newCode(6);
            assertTrue(code.matches("\\d{6}"), code);
            seen.add(code);
        }
        assertTrue(seen.size() > 190, "codes are random");
        assertTrue(CodeChallenge.newCode(10).matches("\\d{10}"));
        assertTrue(CodeChallenge.newCode(4).matches("\\d{4}"));
    }

    @Test
    void keepsAHashNotTheCode() {
        codes.remember("482913", 300);

        assertFalse(notes.containsValue("482913"));
        assertTrue(codes.active());
        assertEquals(1, codes.sends());
    }

    @Test
    void acceptsTheRightCodeOnce() {
        codes.remember("482913", 300);

        assertEquals(CodeChallenge.Result.OK, codes.check(" 482-913 ", 5));
        assertEquals(CodeChallenge.Result.NO_CODE, codes.check("482913", 5));
    }

    @Test
    void countsWrongCodesAcrossNewCodes() {
        codes.remember("111111", 300);
        assertEquals(CodeChallenge.Result.WRONG, codes.check("000000", 3));
        assertEquals(2, codes.attemptsLeft(3));

        codes.remember("222222", 300);
        assertEquals(CodeChallenge.Result.WRONG, codes.check("111111", 3), "the earlier code no longer works");
        assertEquals(CodeChallenge.Result.TOO_MANY, codes.check("333333", 3));
        assertFalse(codes.active());
        assertEquals(CodeChallenge.Result.NO_CODE, codes.check("222222", 3));
    }

    @Test
    void expires() {
        codes.remember("482913", 60);
        clock.advanceSeconds(61);

        assertFalse(codes.active());
        assertEquals(CodeChallenge.Result.EXPIRED, codes.check("482913", 5));
    }

    @Test
    void waitsBeforeANewCode() {
        assertEquals(0, codes.secondsUntilResend(30));
        codes.remember("482913", 300);
        clock.advanceSeconds(10);

        assertEquals(20, codes.secondsUntilResend(30));
        clock.advanceSeconds(20);
        assertEquals(0, codes.secondsUntilResend(30));
    }

    @Test
    void forgetsEverything() {
        codes.remember("482913", 300);
        codes.check("000000", 5);

        codes.clearAll();

        assertTrue(notes.isEmpty());
        assertEquals(0, codes.sends());
        assertEquals(0, codes.attempts());
    }

    @Test
    void canStartTheWrongCodesAgainButNotTheTexts() {
        codes.remember("482913", 300);
        codes.check("000000", 5);

        codes.resetAttempts();

        assertEquals(0, codes.attempts());
        assertEquals(1, codes.sends());
    }
}
