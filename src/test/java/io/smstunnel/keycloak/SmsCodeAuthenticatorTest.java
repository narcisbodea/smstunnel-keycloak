package io.smstunnel.keycloak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.smstunnel.SmsTunnelApiException;
import io.smstunnel.SmsTunnelAuthenticationException;
import io.smstunnel.SmsTunnelConnectionException;
import io.smstunnel.SmsTunnelRateLimitException;
import io.smstunnel.SmsTunnelServerException;

import jakarta.ws.rs.core.MultivaluedHashMap;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.events.Errors;
import org.keycloak.events.EventBuilder;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RequiredActionProviderModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.FormMessage;
import org.keycloak.sessions.AuthenticationSessionModel;

class SmsCodeAuthenticatorTest {

    private static final String PHONE = "+14155557341";

    private final Fakes.Clock clock = new Fakes.Clock();
    private final Fakes.Sender sender = new Fakes.Sender();
    private final Map<String, String> notes = new HashMap<>();
    private final Set<String> requiredActions = new LinkedHashSet<>();
    private final Map<String, String> attributes = new HashMap<>();
    private final Map<String, String> config = new HashMap<>();
    private final MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
    private final Fakes.Forms forms = new Fakes.Forms();

    private AuthenticationSessionModel authSession;
    private UserModel user;
    private RealmModel realm;
    private KeycloakSession session;
    private AuthenticationFlowContext context;
    private EventBuilder event;
    private SmsCodeAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        config.put(SmsCodeConfig.API_KEY, Fakes.KEY);
        attributes.put("phoneNumber", PHONE);
        authSession = Fakes.authSession(notes, requiredActions);
        user = Fakes.user(attributes);
        realm = Fakes.realm();
        session = Fakes.session(new Properties(), Collections.singletonMap("smstunnel_api_key", Fakes.VAULT_KEY), authSession);

        AuthenticatorConfigModel model = new AuthenticatorConfigModel();
        model.setConfig(config);
        context = mock(AuthenticationFlowContext.class);
        when(context.getAuthenticatorConfig()).thenReturn(model);
        when(context.getUser()).thenReturn(user);
        when(context.getAuthenticationSession()).thenReturn(authSession);
        when(context.getSession()).thenReturn(session);
        when(context.getRealm()).thenReturn(realm);
        when(context.form()).thenReturn(forms.form);
        HttpRequest http = mock(HttpRequest.class);
        when(http.getDecodedFormParameters()).thenReturn(params);
        when(context.getHttpRequest()).thenReturn(http);
        event = mock(EventBuilder.class, RETURNS_SELF);
        when(context.getEvent()).thenReturn(event);

        authenticator = new SmsCodeAuthenticator(sender, clock::get);
    }

    private void submit(String name, String value) {
        params.clear();
        params.putSingle(name, value);
        authenticator.action(context);
    }

    // ─── Sending ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void textsACodeToTheUsersNumberAndAsksForIt() {
        authenticator.authenticate(context);

        assertEquals(1, sender.texts.size());
        assertEquals(PHONE, sender.numbers.get(0));
        assertEquals(Fakes.KEY, sender.keys.get(0));
        String code = sender.lastCode();
        assertEquals(6, code.length());
        assertEquals(code + " is your Acme sign-in code. It expires in 5 minutes.", sender.texts.get(0));
        verify(context).challenge(forms.page(SmsCodeAuthenticator.TEMPLATE));
        verify(forms.form).setAttribute(eq("smstunnel"), argThat(data -> "7341".equals(((Map<?, ?>) data).get("phoneEnding"))
                && "true".equals(((Map<?, ?>) data).get("sent"))));
        assertFalse(notes.containsValue(code), "only a hash of the code is kept");
    }

    @Test
    void aReloadShowsThePageAgainWithoutASecondText() {
        authenticator.authenticate(context);
        authenticator.authenticate(context);

        assertEquals(1, sender.texts.size());
        verify(context, times(2)).challenge(forms.page(SmsCodeAuthenticator.TEMPLATE));
    }

    @Test
    void usesTheConfiguredLengthTextAndVaultKey() {
        config.put(SmsCodeConfig.CODE_LENGTH, "8");
        config.put(SmsCodeConfig.SMS_TEXT, "Acme: {code}");
        config.put(SmsCodeConfig.API_KEY, "${vault.smstunnel_api_key}");

        authenticator.authenticate(context);

        assertTrue(sender.texts.get(0).matches("Acme: \\d{8}"), sender.texts.get(0));
        assertEquals(Fakes.VAULT_KEY, sender.keys.get(0));
    }

    @Test
    void anOfflinePhoneStillGetsItsCodeWhenItIsBack() {
        sender.queued = true;

        authenticator.authenticate(context);

        verify(context).challenge(forms.page(SmsCodeAuthenticator.TEMPLATE));
        assertTrue(notes.containsKey(SmsCodeAuthenticator.NOTES + "hash"));
    }

    // ─── The code ────────────────────────────────────────────────────────────────────────────────

    @Test
    void theRightCodeSignsInAndMarksTheNumberChecked() {
        authenticator.authenticate(context);

        submit("code", sender.lastCode());

        verify(context).success();
        assertEquals("true", attributes.get("phoneNumberVerified"));
        assertTrue(notes.isEmpty(), "nothing left in the session");
    }

    @Test
    void aWrongCodeIsAFailedSignInAndSaysWhatIsLeft() {
        authenticator.authenticate(context);

        submit("code", "000000".equals(sender.lastCode()) ? "111111" : "000000");

        verify(event).error(Errors.INVALID_USER_CREDENTIALS);
        verify(context).failureChallenge(eq(AuthenticationFlowError.INVALID_CREDENTIALS), any());
        verify(forms.form).addError(argThat((FormMessage m) -> Messages.CODE_WRONG.equals(m.getMessage())
                && Integer.valueOf(4).equals(m.getParameters()[0])));
        verify(context, never()).success();
    }

    @Test
    void tooManyWrongCodesStartTheSignInOver() {
        config.put(SmsCodeConfig.MAX_ATTEMPTS, "2");
        authenticator.authenticate(context);
        String wrong = "000000".equals(sender.lastCode()) ? "111111" : "000000";

        submit("code", wrong);
        submit("code", wrong);

        verify(context).forkWithErrorMessage(argThat(m -> Messages.TOO_MANY_ATTEMPTS.equals(m.getMessage())));
        assertNull(notes.get(SmsCodeAuthenticator.NOTES + "hash"));
    }

    @Test
    void anExpiredCodeIsRefused() {
        authenticator.authenticate(context);
        String code = sender.lastCode();
        clock.advanceSeconds(301);

        submit("code", code);

        verify(event).error(Errors.EXPIRED_CODE);
        verify(context).failureChallenge(eq(AuthenticationFlowError.EXPIRED_CODE), any());
        verify(context, never()).success();
    }

    @Test
    void anEmptyCodeOnlyAsksForIt() {
        authenticator.authenticate(context);

        submit("code", "  ");

        verify(forms.form).addError(argThat((FormMessage m) -> Messages.CODE_MISSING.equals(m.getMessage())));
        verify(event, never()).error(any());
    }

    // ─── New codes ───────────────────────────────────────────────────────────────────────────────

    @Test
    void aNewCodeAfterTheWaitAndOnlyItWorks() {
        authenticator.authenticate(context);
        String first = sender.lastCode();

        submit("resend", "true");
        verify(forms.form).setError(Messages.WAIT_BEFORE_RESEND, 30L);
        assertEquals(1, sender.texts.size());

        clock.advanceSeconds(30);
        submit("resend", "true");
        assertEquals(2, sender.texts.size());
        String second = sender.lastCode();

        if (!first.equals(second)) {
            submit("code", first);
            verify(context).failureChallenge(eq(AuthenticationFlowError.INVALID_CREDENTIALS), any());
        }
        submit("code", second);
        verify(context).success();
    }

    @Test
    void noMoreTextsThanAllowedPerSignIn() {
        config.put(SmsCodeConfig.MAX_SENDS, "2");
        config.put(SmsCodeConfig.RESEND_AFTER, "0");
        authenticator.authenticate(context);

        submit("resend", "true");
        submit("resend", "true");

        assertEquals(2, sender.texts.size());
        verify(forms.form).setError(Messages.NO_MORE_CODES);
    }

    // ─── When SMSTunnel says no ──────────────────────────────────────────────────────────────────

    @Test
    void aRefusedKeyIsASetupProblem() {
        sender.fail(new SmsTunnelAuthenticationException("Invalid or expired API key", null));

        authenticator.authenticate(context);

        verify(forms.form).setError(Messages.NOT_SET_UP);
        verify(context).failure(AuthenticationFlowError.INTERNAL_ERROR, forms.errorPage);
    }

    @Test
    void aMissingKeyIsASetupProblemAndNothingIsSent() {
        config.remove(SmsCodeConfig.API_KEY);

        authenticator.authenticate(context);

        assertTrue(sender.texts.isEmpty());
        verify(context).failure(AuthenticationFlowError.INTERNAL_ERROR, forms.errorPage);
    }

    @Test
    void aBadSettingIsASetupProblem() {
        sender.fail(new IllegalArgumentException("baseUrl must be an http(s) address"));

        authenticator.authenticate(context);

        verify(context).failure(AuthenticationFlowError.INTERNAL_ERROR, forms.errorPage);
    }

    @Test
    void aRefusedNumberSendsTheUserToTheAdmin() {
        sender.fail(new SmsTunnelApiException(400, "Invalid phone number", null, null));

        authenticator.authenticate(context);

        verify(forms.form).setError(Messages.BAD_NUMBER);
        verify(context).failure(AuthenticationFlowError.INTERNAL_ERROR, forms.errorPage);
    }

    @Test
    void tooManyTextsAskToWait() {
        sender.fail(new SmsTunnelRateLimitException("Too Many Requests", null, Duration.ofSeconds(20)));

        authenticator.authenticate(context);

        verify(forms.form).setError(Messages.TOO_MANY_TEXTS);
        verify(context).challenge(forms.page(SmsCodeAuthenticator.TEMPLATE));
    }

    @Test
    void aServerOrNetworkProblemLetsTheUserTryAgain() {
        sender.fail(new SmsTunnelServerException(503, "Service Unavailable", null, null));
        authenticator.authenticate(context);
        verify(forms.form).setError(Messages.SEND_FAILED);
        assertNull(notes.get(SmsCodeAuthenticator.NOTES + "hash"));

        sender.fail(new SmsTunnelConnectionException("timeout", null, true));
        submit("resend", "true");

        sender.failure = null;
        submit("resend", "true");
        assertEquals(1, sender.texts.size(), "a failed send neither counts nor starts the wait");
        submit("code", sender.lastCode());
        verify(context).success();
    }

    // ─── Setup of users without a number ─────────────────────────────────────────────────────────

    @Test
    void isConfiguredOnlyForUsersWithANumber() {
        assertTrue(authenticator.configuredFor(session, realm, user));

        attributes.remove("phoneNumber");
        assertFalse(authenticator.configuredFor(session, realm, user));

        attributes.put("phoneNumber", "  ");
        assertFalse(authenticator.configuredFor(session, realm, user));
    }

    @Test
    void readsTheAttributeOfTheRealmsStep() {
        RealmModel custom = Fakes.realmWithStep(Collections.singletonMap(SmsCodeConfig.PHONE_ATTRIBUTE, "mobile"));
        attributes.put("mobile", "+40721234567");

        assertTrue(authenticator.configuredFor(session, custom, user));
        attributes.remove("mobile");
        assertFalse(authenticator.configuredFor(session, custom, user));
    }

    @Test
    void asksThisSignInForTheNumberNotTheAccount() {
        authenticator.setRequiredActions(session, realm, user);

        assertTrue(requiredActions.contains(PhoneNumberRequiredActionFactory.PROVIDER_ID));
        verify(user, never()).addRequiredAction(any(String.class));
    }

    @Test
    void usersWithoutANumberGoOnOnlyWhenThePhoneStepIsEnabled() {
        assertFalse(authenticator.areRequiredActionsEnabled(session, realm), "not registered");

        RequiredActionProviderModel model = new RequiredActionProviderModel();
        model.setAlias(PhoneNumberRequiredActionFactory.PROVIDER_ID);
        model.setEnabled(false);
        when(realm.getRequiredActionProviderByAlias(PhoneNumberRequiredActionFactory.PROVIDER_ID)).thenReturn(model);
        assertFalse(authenticator.areRequiredActionsEnabled(session, realm), "registered, not enabled");

        model.setEnabled(true);
        assertTrue(authenticator.areRequiredActionsEnabled(session, realm));
    }

    @Test
    void theFactoryDescribesTheStep() {
        SmsCodeAuthenticatorFactory factory = new SmsCodeAuthenticatorFactory();

        assertEquals("smstunnel-sms-code", factory.getId());
        assertEquals("SMSTunnel SMS code", factory.getDisplayType());
        assertTrue(factory.isConfigurable());
        assertTrue(factory.isUserSetupAllowed());
        assertTrue(factory.getConfigProperties().stream().anyMatch(p -> p.getName().equals(SmsCodeConfig.API_KEY) && p.isSecret()));
        assertEquals(11, factory.getConfigProperties().size());
    }
}
