package io.smstunnel.keycloak;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.LongSupplier;

import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * The code of one sign-in (or one phone-number check), kept in the authentication session's notes: a salted hash of
 * the code (never the code itself), when it expires, how many wrong codes were entered and how many texts were sent.
 */
final class CodeChallenge {

    enum Result {
        /** The right code: it is used up. */
        OK,
        /** A wrong code; more tries are left. */
        WRONG,
        /** The code has expired. */
        EXPIRED,
        /** No code was sent (or it was used up). */
        NO_CODE,
        /** A wrong code, and no tries are left: the code is used up. */
        TOO_MANY
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthenticationSessionModel session;
    private final String prefix;
    private final LongSupplier clock;

    CodeChallenge(AuthenticationSessionModel session, String prefix, LongSupplier clock) {
        this.session = session;
        this.prefix = prefix;
        this.clock = clock;
    }

    /** A new random code of {@code length} digits (leading zeros allowed). */
    static String newCode(int length) {
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append((char) ('0' + RANDOM.nextInt(10)));
        }
        return code.toString();
    }

    /** What the user typed, without spaces and dashes ({@code 123 456} and {@code 123-456} both work). */
    static String clean(String entered) {
        return entered == null ? "" : entered.replaceAll("[\\s\\-]", "");
    }

    /** Remembers a code that was just sent: the earlier one stops working. */
    void remember(String code, int ttlSeconds) {
        long now = clock.getAsLong();
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        String saltText = Base64.getEncoder().encodeToString(salt);
        session.setAuthNote(prefix + "salt", saltText);
        session.setAuthNote(prefix + "hash", hash(saltText, code));
        session.setAuthNote(prefix + "expires", Long.toString(now + ttlSeconds * 1000L));
        session.setAuthNote(prefix + "sends", Integer.toString(sends() + 1));
        session.setAuthNote(prefix + "lastSent", Long.toString(now));
    }

    /** Checks a code. Wrong codes count for the whole sign-in, whichever code they were meant for. */
    Result check(String entered, int maxAttempts) {
        String hash = session.getAuthNote(prefix + "hash");
        String salt = session.getAuthNote(prefix + "salt");
        if (hash == null || salt == null) {
            return Result.NO_CODE;
        }
        if (clock.getAsLong() >= number("expires")) {
            return Result.EXPIRED;
        }
        boolean right = MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),
                hash(salt, clean(entered)).getBytes(StandardCharsets.US_ASCII));
        if (right) {
            clearCode();
            return Result.OK;
        }
        int attempts = attempts() + 1;
        session.setAuthNote(prefix + "attempts", Integer.toString(attempts));
        if (attempts >= maxAttempts) {
            clearCode();
            return Result.TOO_MANY;
        }
        return Result.WRONG;
    }

    /** True while a code was sent and has not expired or been used. */
    boolean active() {
        return session.getAuthNote(prefix + "hash") != null && clock.getAsLong() < number("expires");
    }

    int sends() {
        return (int) number("sends");
    }

    int attempts() {
        return (int) number("attempts");
    }

    int attemptsLeft(int maxAttempts) {
        return Math.max(0, maxAttempts - attempts());
    }

    /** Seconds until a new code may be sent, or 0. */
    long secondsUntilResend(int resendAfterSeconds) {
        long lastSent = number("lastSent");
        if (lastSent == 0) {
            return 0;
        }
        long waitMillis = lastSent + resendAfterSeconds * 1000L - clock.getAsLong();
        return waitMillis <= 0 ? 0 : (waitMillis + 999) / 1000;
    }

    /** Forgets the code; the counts of texts and wrong codes stay. */
    void clearCode() {
        session.removeAuthNote(prefix + "hash");
        session.removeAuthNote(prefix + "salt");
        session.removeAuthNote(prefix + "expires");
    }

    /** Starts the count of wrong codes again (the count of texts stays). */
    void resetAttempts() {
        session.removeAuthNote(prefix + "attempts");
    }

    /** Forgets everything, counts included. */
    void clearAll() {
        clearCode();
        session.removeAuthNote(prefix + "attempts");
        session.removeAuthNote(prefix + "sends");
        session.removeAuthNote(prefix + "lastSent");
    }

    private long number(String name) {
        String value = session.getAuthNote(prefix + name);
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String hash(String salt, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            digest.update(code.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this Java runtime", e);
        }
    }
}
