package io.smstunnel.keycloak;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.RequiredActionFactory;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.events.Errors;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RequiredActionProviderModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.FormMessage;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * SMSTunnel SMS code: after the password, texts a code to the number in the user's phone attribute and asks for it.
 *
 * <p>A user without a number gets the "SMSTunnel: add a phone number" required action, which checks the new number
 * with a code before saving it. When that action is not enabled in the realm, Keycloak stops the sign-in ("credential
 * setup required") instead of letting the user in without a code.
 */
public class SmsCodeAuthenticator implements Authenticator {

    static final String NOTES = "smstunnel.login.";
    static final String TEMPLATE = "smstunnel-sms-code.ftl";
    static final String FIELD = "code";

    private static final Logger LOG = Logger.getLogger(SmsCodeAuthenticator.class);

    private final CodeSender sender;
    private final LongSupplier clock;

    SmsCodeAuthenticator(CodeSender sender, LongSupplier clock) {
        this.sender = sender;
        this.clock = clock;
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        SmsCodeConfig config = SmsCodeConfig.of(context.getAuthenticatorConfig());
        String phone = config.phoneOf(context.getUser());
        if (phone == null) {
            context.failure(AuthenticationFlowError.CREDENTIAL_SETUP_REQUIRED, errorPage(context.form(), Messages.NO_PHONE));
            return;
        }
        CodeChallenge codes = codes(context);
        if (codes.active()) {
            // A reload of the page: show it again, no second text.
            context.challenge(form(context, config, phone, codes, false).createForm(TEMPLATE));
            return;
        }
        send(context, config, phone, codes, false);
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        SmsCodeConfig config = SmsCodeConfig.of(context.getAuthenticatorConfig());
        String phone = config.phoneOf(context.getUser());
        if (phone == null) {
            context.failure(AuthenticationFlowError.CREDENTIAL_SETUP_REQUIRED, errorPage(context.form(), Messages.NO_PHONE));
            return;
        }
        CodeChallenge codes = codes(context);
        MultivaluedMap<String, String> params = context.getHttpRequest().getDecodedFormParameters();
        if (params.containsKey("resend")) {
            resend(context, config, phone, codes);
            return;
        }
        String entered = CodeChallenge.clean(params.getFirst(FIELD));
        if (entered.isEmpty()) {
            context.challenge(form(context, config, phone, codes, false)
                    .addError(new FormMessage(FIELD, Messages.CODE_MISSING))
                    .createForm(TEMPLATE));
            return;
        }
        UserModel user = context.getUser();
        switch (codes.check(entered, config.maxAttempts)) {
            case OK:
                codes.clearAll();
                markVerified(user, config);
                context.success();
                return;
            case WRONG:
                context.getEvent().user(user).error(Errors.INVALID_USER_CREDENTIALS);
                context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, form(context, config, phone, codes, false)
                        .addError(new FormMessage(FIELD, Messages.CODE_WRONG, codes.attemptsLeft(config.maxAttempts)))
                        .createForm(TEMPLATE));
                return;
            case TOO_MANY:
                context.getEvent().user(user).error(Errors.INVALID_USER_CREDENTIALS);
                codes.clearAll();
                // Back to the first sign-in page, with the reason on it.
                context.forkWithErrorMessage(new FormMessage(null, Messages.TOO_MANY_ATTEMPTS));
                return;
            case EXPIRED:
                context.getEvent().user(user).error(Errors.EXPIRED_CODE);
                context.failureChallenge(AuthenticationFlowError.EXPIRED_CODE, form(context, config, phone, codes, false)
                        .addError(new FormMessage(FIELD, Messages.CODE_EXPIRED))
                        .createForm(TEMPLATE));
                return;
            case NO_CODE:
            default:
                context.challenge(form(context, config, phone, codes, false)
                        .addError(new FormMessage(FIELD, Messages.CODE_EXPIRED))
                        .createForm(TEMPLATE));
        }
    }

    private void resend(AuthenticationFlowContext context, SmsCodeConfig config, String phone, CodeChallenge codes) {
        if (codes.sends() >= config.maxSends) {
            context.challenge(form(context, config, phone, codes, false).setError(Messages.NO_MORE_CODES).createForm(TEMPLATE));
            return;
        }
        long wait = codes.secondsUntilResend(config.resendAfterSeconds);
        if (wait > 0) {
            context.challenge(form(context, config, phone, codes, false)
                    .setError(Messages.WAIT_BEFORE_RESEND, wait).createForm(TEMPLATE));
            return;
        }
        send(context, config, phone, codes, true);
    }

    private void send(AuthenticationFlowContext context, SmsCodeConfig config, String phone, CodeChallenge codes,
            boolean again) {
        if (codes.sends() >= config.maxSends) {
            context.challenge(form(context, config, phone, codes, false).setError(Messages.NO_MORE_CODES).createForm(TEMPLATE));
            return;
        }
        KeycloakSession session = context.getSession();
        RealmModel realm = context.getRealm();
        String code = CodeChallenge.newCode(config.codeLength);
        String text = SmsText.forLogin(session, realm, context.getUser(), config, code);
        CodeDelivery.Outcome outcome = CodeDelivery.deliver(sender, session, realm, config, phone, text);
        switch (outcome) {
            case SENT:
            case QUEUED:
                codes.remember(code, config.codeTtlSeconds);
                context.challenge(form(context, config, phone, codes, again).createForm(TEMPLATE));
                return;
            case RATE_LIMITED:
                context.challenge(form(context, config, phone, codes, false).setError(Messages.TOO_MANY_TEXTS).createForm(TEMPLATE));
                return;
            case FAILED:
                context.challenge(form(context, config, phone, codes, false).setError(Messages.SEND_FAILED).createForm(TEMPLATE));
                return;
            case REFUSED:
                context.failure(AuthenticationFlowError.INTERNAL_ERROR, errorPage(context.form(), Messages.BAD_NUMBER));
                return;
            case NOT_SET_UP:
            default:
                context.failure(AuthenticationFlowError.INTERNAL_ERROR, errorPage(context.form(), Messages.NOT_SET_UP));
        }
    }

    private LoginFormsProvider form(AuthenticationFlowContext context, SmsCodeConfig config, String phone,
            CodeChallenge codes, boolean again) {
        Map<String, String> data = new HashMap<>();
        data.put("mode", "login");
        data.put("phoneEnding", PhoneNumbers.ending(phone));
        data.put("ttlMinutes", Integer.toString(config.ttlMinutes()));
        data.put("codeLength", Integer.toString(config.codeLength));
        data.put("sent", Boolean.toString(codes.active()));
        data.put("again", Boolean.toString(again));
        return context.form().setAttribute("smstunnel", data);
    }

    static Response errorPage(LoginFormsProvider form, String message) {
        return form.setError(message).createErrorPage(Response.Status.INTERNAL_SERVER_ERROR);
    }

    private CodeChallenge codes(AuthenticationFlowContext context) {
        return new CodeChallenge(context.getAuthenticationSession(), NOTES, clock);
    }

    private static void markVerified(UserModel user, SmsCodeConfig config) {
        String attribute = config.verifiedAttribute();
        if ("true".equals(user.getFirstAttribute(attribute))) {
            return;
        }
        try {
            user.setSingleAttribute(attribute, "true");
        } catch (RuntimeException e) {
            // A read-only user store, for example: the sign-in still succeeds.
            LOG.debugf(e, "SMSTunnel SMS code: could not set %s on user %s", attribute, user.getId());
        }
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    /** True when the user has a number in the phone attribute (of the realm's SMSTunnel configuration). */
    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        SmsCodeConfig config = SmsCodeConfig.forRealm(realm);
        return (config != null ? config : SmsCodeConfig.of((Map<String, String>) null)).phoneOf(user) != null;
    }

    /** Asks this sign-in (not the account for good) for the phone-number step. */
    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        AuthenticationSessionModel authSession = session.getContext().getAuthenticationSession();
        if (authSession != null) {
            if (!authSession.getRequiredActions().contains(PhoneNumberRequiredActionFactory.PROVIDER_ID)) {
                authSession.addRequiredAction(PhoneNumberRequiredActionFactory.PROVIDER_ID);
            }
        } else {
            user.addRequiredAction(PhoneNumberRequiredActionFactory.PROVIDER_ID);
        }
    }

    @Override
    public List<RequiredActionFactory> getRequiredActions(KeycloakSession session) {
        Object factory = session.getKeycloakSessionFactory()
                .getProviderFactory(RequiredActionProvider.class, PhoneNumberRequiredActionFactory.PROVIDER_ID);
        return factory instanceof RequiredActionFactory
                ? Collections.singletonList((RequiredActionFactory) factory)
                : Collections.emptyList();
    }

    /** Only when the phone-number step is registered and enabled can a user without a number go on. */
    @Override
    public boolean areRequiredActionsEnabled(KeycloakSession session, RealmModel realm) {
        RequiredActionProviderModel model = realm.getRequiredActionProviderByAlias(PhoneNumberRequiredActionFactory.PROVIDER_ID);
        return model != null && model.isEnabled();
    }

    @Override
    public void close() {
    }
}
