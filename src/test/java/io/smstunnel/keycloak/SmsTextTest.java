package io.smstunnel.keycloak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

class SmsTextTest {

    private final RealmModel realm = Fakes.realm();

    private static KeycloakSession sessionWith(String key, String text) {
        Properties messages = new Properties();
        if (text != null) {
            messages.setProperty(key, text);
        }
        return Fakes.session(messages, Collections.emptyMap(), Fakes.authSession());
    }

    private static SmsCodeConfig config(String smsText) {
        Map<String, String> c = new HashMap<>();
        if (smsText != null) {
            c.put(SmsCodeConfig.SMS_TEXT, smsText);
        }
        return SmsCodeConfig.of(c);
    }

    @Test
    void usesTheLoginThemeTextInTheUsersLanguage() {
        KeycloakSession session = sessionWith(SmsText.LOGIN_KEY, "{code} este codul tau de conectare la {realm}. Expira in {ttl} minute.");

        String text = SmsText.forLogin(session, realm, null, config(null), "482913");

        assertEquals("482913 este codul tau de conectare la Acme. Expira in 5 minute.", text);
    }

    @Test
    void usesTheConfiguredTextForEveryLanguage() {
        KeycloakSession session = sessionWith(SmsText.LOGIN_KEY, "{code} este codul tau.");

        String text = SmsText.forLogin(session, realm, null, config("Acme code: {code} (valid {ttl} min)"), "0042");

        assertEquals("Acme code: 0042 (valid 5 min)", text);
    }

    @Test
    void fallsBackToTheBuiltInTextWhenTheThemeHasNone() {
        String text = SmsText.forLogin(sessionWith(SmsText.LOGIN_KEY, null), realm, null, config(null), "482913");

        assertEquals("482913 is your Acme sign-in code. It expires in 5 minutes.", text);
    }

    @Test
    void replacesATextWhereTheCodeIsGluedToOtherCharacters() {
        String text = SmsText.forLogin(sessionWith(SmsText.LOGIN_KEY, null), realm, null, config("Code{code}"), "482913");

        assertEquals("482913 is your Acme sign-in code. It expires in 5 minutes.", text);
    }

    @Test
    void replacesATextWithoutTheCode() {
        String text = SmsText.forLogin(sessionWith(SmsText.LOGIN_KEY, null), realm, null, config("Welcome to {realm}"), "482913");

        assertTrue(text.startsWith("482913 is your"));
    }

    @Test
    void replacesATooLongText() {
        String longText = "{code} " + "x".repeat(SmsText.MAX_LENGTH);

        String text = SmsText.forLogin(sessionWith(SmsText.LOGIN_KEY, null), realm, null, config(longText), "482913");

        assertTrue(text.length() < 100);
    }

    @Test
    void theCodeMustStandOnItsOwnLikeSmsTunnelChecks() {
        assertTrue(SmsText.codeStandsAlone("Your code is 482913.", "482913"));
        assertTrue(SmsText.codeStandsAlone("482913 is your code", "482913"));
        assertTrue(SmsText.codeStandsAlone("Code:482913", "482913"));
        assertTrue(SmsText.codeStandsAlone("G-482913", "482913"));
        assertFalse(SmsText.codeStandsAlone("Code482913", "482913"));
        assertFalse(SmsText.codeStandsAlone("482913abc", "482913"));
        assertFalse(SmsText.codeStandsAlone("+482913", "482913"));
        assertFalse(SmsText.codeStandsAlone("1.482913", "482913"));
        assertFalse(SmsText.codeStandsAlone("482913/12", "482913"));
    }

    @Test
    void thePhoneCheckHasItsOwnText() {
        String text = SmsText.forPhoneCheck(sessionWith(SmsText.PHONE_KEY, null), realm, null, config(null), "123456");

        assertEquals("123456 is your code to confirm this phone number for Acme.", text);
    }

    @Test
    void theRealmNameFallsBackToItsId() {
        RealmModel plain = Fakes.realm();
        org.mockito.Mockito.when(plain.getDisplayName()).thenReturn(" ");

        assertEquals("acme", SmsText.realmName(plain));
    }
}
