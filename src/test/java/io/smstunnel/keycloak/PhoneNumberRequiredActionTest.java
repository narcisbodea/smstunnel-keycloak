package io.smstunnel.keycloak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.smstunnel.SmsTunnelApiException;

import jakarta.ws.rs.core.MultivaluedHashMap;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.InitiatedActionSupport;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.FormMessage;
import org.keycloak.sessions.AuthenticationSessionModel;

class PhoneNumberRequiredActionTest {

    private final Fakes.Clock clock = new Fakes.Clock();
    private final Fakes.Sender sender = new Fakes.Sender();
    private final Map<String, String> notes = new HashMap<>();
    private final Map<String, String> attributes = new HashMap<>();
    private final Map<String, String> config = new HashMap<>();
    private final MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
    private final Fakes.Forms forms = new Fakes.Forms();

    private RequiredActionContext context;
    private EventBuilder event;
    private UserModel user;
    private PhoneNumberRequiredAction action;

    @BeforeEach
    void setUp() {
        config.put(SmsCodeConfig.API_KEY, Fakes.KEY);
        config.put(SmsCodeConfig.DEFAULT_COUNTRY_CODE, "+1");
        AuthenticationSessionModel authSession = Fakes.authSession(notes, new LinkedHashSet<>());
        user = Fakes.user(attributes);
        RealmModel realm = Fakes.realmWithStep(config);
        KeycloakSession session = Fakes.session(new Properties(), Collections.emptyMap(), authSession);

        context = mock(RequiredActionContext.class);
        when(context.getRealm()).thenReturn(realm);
        when(context.getUser()).thenReturn(user);
        when(context.getSession()).thenReturn(session);
        when(context.getAuthenticationSession()).thenReturn(authSession);
        when(context.form()).thenReturn(forms.form);
        HttpRequest http = mock(HttpRequest.class);
        when(http.getDecodedFormParameters()).thenReturn(params);
        when(context.getHttpRequest()).thenReturn(http);
        event = mock(EventBuilder.class, RETURNS_SELF);
        when(event.clone()).thenReturn(event);
        when(context.getEvent()).thenReturn(event);

        action = new PhoneNumberRequiredAction(sender, clock::get);
    }

    private void submit(String name, String value) {
        params.clear();
        params.putSingle(name, value);
        action.processAction(context);
    }

    @Test
    void firstAsksForTheNumber() {
        action.requiredActionChallenge(context);

        verify(context).challenge(forms.page(PhoneNumberRequiredAction.PHONE_TEMPLATE));
        assertTrue(sender.texts.isEmpty());
    }

    @Test
    void savesTheNumberOnlyOnceItsCodeComesBack() {
        submit("phoneNumber", "(415) 555-7342");

        assertEquals("+14155557342", sender.numbers.get(0), "completed with the default country code");
        assertEquals(sender.lastCode() + " is your code to confirm this phone number for Acme.", sender.texts.get(0));
        verify(context).challenge(forms.page(PhoneNumberRequiredAction.CODE_TEMPLATE));
        assertNull(attributes.get("phoneNumber"), "not saved yet");

        submit("code", sender.lastCode());

        assertEquals("+14155557342", attributes.get("phoneNumber"));
        assertEquals("true", attributes.get("phoneNumberVerified"));
        verify(event).event(EventType.UPDATE_PROFILE);
        verify(event).success();
        verify(context).success();
        assertTrue(notes.isEmpty());
    }

    @Test
    void refusesWhatIsNotANumber() {
        submit("phoneNumber", "call me");

        verify(forms.form).addError(argThat((FormMessage m) -> Messages.PHONE_INVALID.equals(m.getMessage())));
        assertTrue(sender.texts.isEmpty());
    }

    @Test
    void aWrongCodeKeepsTheOldNumber() {
        attributes.put("phoneNumber", "+14155550000");
        submit("phoneNumber", "+1 415 555 7343");

        submit("code", "000000".equals(sender.lastCode()) ? "111111" : "000000");

        assertEquals("+14155550000", attributes.get("phoneNumber"));
        verify(forms.form).addError(argThat((FormMessage m) -> Messages.CODE_WRONG.equals(m.getMessage())));
        verify(context, never()).success();
    }

    @Test
    void tooManyWrongCodesAskForTheNumberAgain() {
        config.put(SmsCodeConfig.MAX_ATTEMPTS, "1");
        submit("phoneNumber", "+1 415 555 7344");

        submit("code", "000000".equals(sender.lastCode()) ? "111111" : "000000");

        verify(forms.form).setError(Messages.TOO_MANY_ATTEMPTS_PHONE);
        assertNull(notes.get(PhoneNumberRequiredAction.PENDING_NUMBER));
        verify(context, never()).success();
    }

    @Test
    void anotherNumberCanBeTriedWithinTheTextLimit() {
        config.put(SmsCodeConfig.MAX_SENDS, "2");
        config.put(SmsCodeConfig.RESEND_AFTER, "0");
        submit("phoneNumber", "+1 415 555 7345");
        submit("changeNumber", "true");
        submit("phoneNumber", "+1 415 555 7346");
        submit("changeNumber", "true");
        submit("phoneNumber", "+1 415 555 7347");

        assertEquals(2, sender.texts.size());
        verify(forms.form).setError(Messages.NO_MORE_CODES);
    }

    @Test
    void waitsBeforeANewCode() {
        submit("phoneNumber", "+1 415 555 7348");

        submit("resend", "true");

        verify(forms.form).setError(eq(Messages.WAIT_BEFORE_RESEND), eq(30L));
        assertEquals(1, sender.texts.size());
    }

    @Test
    void aNumberSmsTunnelRefusesIsShownAsSuch() {
        sender.fail(new SmsTunnelApiException(400, "Sending to short/premium numbers is blocked for security", null, null));

        submit("phoneNumber", "+1 900 555 0100");

        verify(forms.form).addError(argThat((FormMessage m) -> Messages.PHONE_REFUSED.equals(m.getMessage())));
        assertNull(notes.get(PhoneNumberRequiredAction.PENDING_NUMBER));
    }

    @Test
    void aRealmWithoutTheStepCannotSendCodes() {
        RealmModel bare = Fakes.realm();
        when(context.getRealm()).thenReturn(bare);

        action.requiredActionChallenge(context);

        verify(forms.form).setError(Messages.NOT_SET_UP);
        verify(context).challenge(forms.errorPage);
    }

    @Test
    void canBeStartedByAnApplication() {
        assertEquals(InitiatedActionSupport.SUPPORTED, action.initiatedActionSupport());
        assertEquals("SMSTunnel: add a phone number", new PhoneNumberRequiredActionFactory().getDisplayText());
    }
}
