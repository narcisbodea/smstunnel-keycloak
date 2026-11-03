package io.smstunnel.keycloak;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.smstunnel.SendResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.ws.rs.core.Response;

import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.AuthenticationFlowModel;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.ThemeManager;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.theme.Theme;
import org.keycloak.vault.VaultStringSecret;
import org.keycloak.vault.VaultTranscriber;

/** Mockito stand-ins for the Keycloak objects, with real state where the code reads back what it wrote. */
final class Fakes {

    // Made-up keys, built from pieces: written in one piece they look like real keys to secret scanners.
    static final String KEY = "sk_" + "live_" + "test";
    static final String VAULT_KEY = "sk_" + "live_" + "from_vault";
    static final String PLAIN_KEY = "sk_" + "live_" + "plain";

    private Fakes() {
    }

    static final class Clock {
        final AtomicLong now = new AtomicLong(1_800_000_000_000L);

        long get() {
            return now.get();
        }

        void advanceSeconds(long seconds) {
            now.addAndGet(seconds * 1000);
        }
    }

    /** Records each text and answers with a result or throws what the test sets. */
    static final class Sender implements CodeSender {
        final List<String> texts = new ArrayList<>();
        final List<String> numbers = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        RuntimeException failure;
        boolean queued;

        @Override
        public SendResult send(SmsCodeConfig config, String apiKey, String to, String text) {
            if (failure != null) {
                throw failure;
            }
            texts.add(text);
            numbers.add(to);
            keys.add(apiKey);
            SendResult result = mock(SendResult.class);
            when(result.getMessageId()).thenReturn("m" + texts.size());
            when(result.isQueued()).thenReturn(queued);
            return result;
        }

        String lastCode() {
            Matcher m = Pattern.compile("\\d{4,10}").matcher(texts.get(texts.size() - 1));
            if (!m.find()) {
                throw new AssertionError("no code in " + texts);
            }
            return m.group();
        }

        void fail(RuntimeException e) {
            failure = e;
        }
    }

    static AuthenticationSessionModel authSession(Map<String, String> notes, Set<String> requiredActions) {
        AuthenticationSessionModel session = mock(AuthenticationSessionModel.class);
        when(session.getAuthNote(anyString())).thenAnswer(i -> notes.get(i.getArgument(0)));
        doAnswer(i -> notes.put(i.getArgument(0), i.getArgument(1))).when(session).setAuthNote(anyString(), anyString());
        doAnswer(i -> notes.remove(i.getArgument(0))).when(session).removeAuthNote(anyString());
        when(session.getRequiredActions()).thenReturn(requiredActions);
        doAnswer(i -> requiredActions.add(i.getArgument(0))).when(session).addRequiredAction(anyString());
        return session;
    }

    static AuthenticationSessionModel authSession() {
        return authSession(new HashMap<>(), new LinkedHashSet<>());
    }

    static UserModel user(Map<String, String> attributes) {
        UserModel user = mock(UserModel.class);
        when(user.getId()).thenReturn("user-1");
        when(user.getUsername()).thenReturn("alice");
        when(user.getFirstAttribute(anyString())).thenAnswer(i -> attributes.get(i.getArgument(0)));
        doAnswer(i -> attributes.put(i.getArgument(0), i.getArgument(1))).when(user).setSingleAttribute(anyString(), anyString());
        return user;
    }

    static RealmModel realm() {
        RealmModel realm = mock(RealmModel.class);
        when(realm.getName()).thenReturn("acme");
        when(realm.getDisplayName()).thenReturn("Acme");
        when(realm.getAuthenticationFlowsStream()).thenAnswer(i -> Stream.empty());
        return realm;
    }

    /** A realm whose browser flow has an SMSTunnel SMS code step with this configuration. */
    static RealmModel realmWithStep(Map<String, String> config) {
        RealmModel realm = realm();
        AuthenticationFlowModel browser = new AuthenticationFlowModel();
        browser.setId("browser");
        when(realm.getBrowserFlow()).thenReturn(browser);
        AuthenticationExecutionModel step = new AuthenticationExecutionModel();
        step.setAuthenticator(SmsCodeAuthenticatorFactory.PROVIDER_ID);
        step.setAuthenticatorConfig("cfg");
        step.setRequirement(AuthenticationExecutionModel.Requirement.REQUIRED);
        when(realm.getAuthenticationExecutionsStream("browser")).thenAnswer(i -> Stream.of(step));
        AuthenticatorConfigModel model = new AuthenticatorConfigModel();
        model.setId("cfg");
        model.setConfig(config);
        when(realm.getAuthenticatorConfigById("cfg")).thenReturn(model);
        return realm;
    }

    /** A session whose login theme has these messages, and whose vault knows these secrets. */
    static KeycloakSession session(Properties messages, Map<String, String> vault, AuthenticationSessionModel authSession) {
        KeycloakSession session = mock(KeycloakSession.class);
        KeycloakContext context = mock(KeycloakContext.class);
        when(session.getContext()).thenReturn(context);
        when(context.resolveLocale(any())).thenReturn(Locale.ENGLISH);
        when(context.getAuthenticationSession()).thenReturn(authSession);
        ThemeManager themes = mock(ThemeManager.class);
        Theme theme = mock(Theme.class);
        try {
            when(themes.getTheme(Theme.Type.LOGIN)).thenReturn(theme);
            when(theme.getEnhancedMessages(any(), any())).thenReturn(messages);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(session.theme()).thenReturn(themes);
        VaultTranscriber transcriber = mock(VaultTranscriber.class);
        when(transcriber.getStringSecret(anyString())).thenAnswer(i -> {
            String expression = i.getArgument(0);
            String name = expression.replaceAll("^\\$\\{vault\\.(.*)}$", "$1");
            VaultStringSecret secret = mock(VaultStringSecret.class);
            when(secret.get()).thenReturn(Optional.ofNullable(vault.get(name)));
            return secret;
        });
        when(session.vault()).thenReturn(transcriber);
        return session;
    }

    /** A form whose builder methods return itself and whose pages are distinct mocks, remembered by name. */
    static final class Forms {
        final LoginFormsProvider form = mock(LoginFormsProvider.class, RETURNS_SELF);
        final Map<String, Response> pages = new HashMap<>();
        final Response errorPage = mock(Response.class);

        Forms() {
            when(form.createForm(anyString())).thenAnswer(i -> pages.computeIfAbsent(i.getArgument(0), name -> mock(Response.class)));
            when(form.createErrorPage(any())).thenReturn(errorPage);
        }

        Response page(String template) {
            return pages.get(template);
        }
    }
}
