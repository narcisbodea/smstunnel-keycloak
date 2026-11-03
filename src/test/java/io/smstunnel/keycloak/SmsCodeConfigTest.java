package io.smstunnel.keycloak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.AuthenticationFlowModel;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

class SmsCodeConfigTest {

    @Test
    void hasSafeDefaults() {
        SmsCodeConfig config = SmsCodeConfig.of((AuthenticatorConfigModel) null);

        assertNull(config.apiKey);
        assertEquals("https://smstunnel.io", config.baseUrl);
        assertEquals(6, config.codeLength);
        assertEquals(300, config.codeTtlSeconds);
        assertEquals(5, config.ttlMinutes());
        assertEquals(5, config.maxAttempts);
        assertEquals(3, config.maxSends);
        assertEquals(30, config.resendAfterSeconds);
        assertEquals("phoneNumber", config.phoneAttribute);
        assertEquals("phoneNumberVerified", config.verifiedAttribute());
    }

    @Test
    void readsTheSettingsAndKeepsThemInRange() {
        Map<String, String> c = new HashMap<>();
        c.put(SmsCodeConfig.API_KEY, " " + Fakes.KEY + " ");
        c.put(SmsCodeConfig.BASE_URL, "http://host.docker.internal:3141");
        c.put(SmsCodeConfig.CODE_LENGTH, "12");
        c.put(SmsCodeConfig.CODE_TTL, "90");
        c.put(SmsCodeConfig.MAX_ATTEMPTS, "zero");
        c.put(SmsCodeConfig.RESEND_AFTER, "-5");
        c.put(SmsCodeConfig.PHONE_ATTRIBUTE, "mobile");
        c.put(SmsCodeConfig.SMS_TEXT, "   ");

        SmsCodeConfig config = SmsCodeConfig.of(c);

        assertEquals(Fakes.KEY, config.apiKey);
        assertEquals("http://host.docker.internal:3141", config.baseUrl);
        assertEquals(10, config.codeLength);
        assertEquals(90, config.codeTtlSeconds);
        assertEquals(2, config.ttlMinutes());
        assertEquals(5, config.maxAttempts);
        assertEquals(0, config.resendAfterSeconds);
        assertEquals("mobile", config.phoneAttribute);
        assertNull(config.smsText);
    }

    @Test
    void readsTheKeyFromTheVault() {
        KeycloakSession session = Fakes.session(new Properties(), Collections.singletonMap("smstunnel_api_key", Fakes.VAULT_KEY),
                Fakes.authSession());

        assertEquals(Fakes.VAULT_KEY,
                SmsCodeConfig.of(Collections.singletonMap(SmsCodeConfig.API_KEY, "${vault.smstunnel_api_key}")).resolveApiKey(session));
        assertNull(SmsCodeConfig.of(Collections.singletonMap(SmsCodeConfig.API_KEY, "${vault.missing}")).resolveApiKey(session));
        assertEquals(Fakes.PLAIN_KEY,
                SmsCodeConfig.of(Collections.singletonMap(SmsCodeConfig.API_KEY, Fakes.PLAIN_KEY)).resolveApiKey(session));
    }

    @Test
    void findsTheStepInANestedBrowserFlow() {
        RealmModel realm = mock(RealmModel.class);
        AuthenticationFlowModel browser = new AuthenticationFlowModel();
        browser.setId("browser");
        when(realm.getBrowserFlow()).thenReturn(browser);
        AuthenticationExecutionModel forms = new AuthenticationExecutionModel();
        forms.setAuthenticatorFlow(true);
        forms.setFlowId("forms");
        when(realm.getAuthenticationExecutionsStream("browser")).thenAnswer(i -> Stream.of(forms));
        AuthenticationExecutionModel password = new AuthenticationExecutionModel();
        password.setAuthenticator("auth-username-password-form");
        password.setRequirement(AuthenticationExecutionModel.Requirement.REQUIRED);
        AuthenticationExecutionModel sms = new AuthenticationExecutionModel();
        sms.setAuthenticator(SmsCodeAuthenticatorFactory.PROVIDER_ID);
        sms.setAuthenticatorConfig("cfg-1");
        sms.setRequirement(AuthenticationExecutionModel.Requirement.REQUIRED);
        when(realm.getAuthenticationExecutionsStream("forms")).thenAnswer(i -> Stream.of(password, sms));
        AuthenticatorConfigModel model = new AuthenticatorConfigModel();
        model.setConfig(Collections.singletonMap(SmsCodeConfig.PHONE_ATTRIBUTE, "mobile"));
        when(realm.getAuthenticatorConfigById("cfg-1")).thenReturn(model);

        assertSame(model, SmsCodeConfig.findConfig(realm).orElseThrow());
        assertEquals("mobile", SmsCodeConfig.forRealm(realm).phoneAttribute);
    }

    @Test
    void aRealmWithoutTheStepHasNoConfig() {
        RealmModel realm = mock(RealmModel.class);
        when(realm.getAuthenticationFlowsStream()).thenAnswer(i -> Stream.empty());

        assertNull(SmsCodeConfig.forRealm(realm));
        assertTrue(SmsCodeConfig.findConfig(null).isEmpty());
    }
}
