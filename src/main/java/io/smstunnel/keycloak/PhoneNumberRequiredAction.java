package io.smstunnel.keycloak;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import org.jboss.logging.Logger;
import org.keycloak.authentication.InitiatedActionSupport;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.events.EventType;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.FormMessage;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * SMSTunnel: add a phone number. Asks for a number, texts a code to it, and saves it on the user (with
 * {@code <attribute>Verified=true}) only once the code comes back. It runs when the SMS code step meets a user without
 * a number, when an admin sets it on a user, or from an application ({@code kc_action=smstunnel-phone-number}) to
 * change the number; Keycloak then asks to sign in again if the last sign-in is older than five minutes.
 *
 * <p>It uses the settings of the realm's SMSTunnel SMS code step (key, country code, code length, lifetime, limits).
 */
public class PhoneNumberRequiredAction implements RequiredActionProvider {

    static final String NOTES = "smstunnel.phone.";
    static final String PENDING_NUMBER = NOTES + "number";
    static final String PHONE_TEMPLATE = "smstunnel-phone-number.ftl";
    static final String CODE_TEMPLATE = SmsCodeAuthenticator.TEMPLATE;
    static final String PHONE_FIELD = "phoneNumber";
    static final String CODE_FIELD = SmsCodeAuthenticator.FIELD;

    private static final Logger LOG = Logger.getLogger(PhoneNumberRequiredAction.class);

    private final CodeSender sender;
    private final LongSupplier clock;

    PhoneNumberRequiredAction(CodeSender sender, LongSupplier clock) {
        this.sender = sender;
        this.clock = clock;
    }

    @Override
    public InitiatedActionSupport initiatedActionSupport() {
        return InitiatedActionSupport.SUPPORTED;
    }

    @Override
    public void evaluateTriggers(RequiredActionContext context) {
        // Asked for by the SMS code step, by an admin, or by an application; never by itself.
    }

    @Override
    public void requiredActionChallenge(RequiredActionContext context) {
        SmsCodeConfig config = SmsCodeConfig.forRealm(context.getRealm());
        if (config == null) {
            notSetUp(context);
            return;
        }
        CodeChallenge codes = codes(context);
        String pending = context.getAuthenticationSession().getAuthNote(PENDING_NUMBER);
        if (pending != null && codes.active()) {
            context.challenge(codeForm(context, config, pending, codes, false).createForm(CODE_TEMPLATE));
            return;
        }
        context.challenge(phoneForm(context, config.phoneOf(context.getUser())).createForm(PHONE_TEMPLATE));
    }

    @Override
    public void processAction(RequiredActionContext context) {
        SmsCodeConfig config = SmsCodeConfig.forRealm(context.getRealm());
        if (config == null) {
            notSetUp(context);
            return;
        }
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        CodeChallenge codes = codes(context);
        MultivaluedMap<String, String> params = context.getHttpRequest().getDecodedFormParameters();
        String pending = authSession.getAuthNote(PENDING_NUMBER);

        if (params.containsKey("changeNumber")) {
            codes.clearCode();
            authSession.removeAuthNote(PENDING_NUMBER);
            context.challenge(phoneForm(context, pending).createForm(PHONE_TEMPLATE));
            return;
        }
        if (params.containsKey(PHONE_FIELD)) {
            String typed = params.getFirst(PHONE_FIELD);
            String number = PhoneNumbers.normalize(typed, config.defaultCountryCode);
            if (number == null) {
                context.challenge(phoneForm(context, typed)
                        .addError(new FormMessage(PHONE_FIELD, Messages.PHONE_INVALID))
                        .createForm(PHONE_TEMPLATE));
                return;
            }
            codes.clearCode();
            authSession.setAuthNote(PENDING_NUMBER, number);
            send(context, config, number, codes, false);
            return;
        }
        if (pending == null) {
            context.challenge(phoneForm(context, config.phoneOf(context.getUser())).createForm(PHONE_TEMPLATE));
            return;
        }
        if (params.containsKey("resend")) {
            send(context, config, pending, codes, true);
            return;
        }
        String entered = CodeChallenge.clean(params.getFirst(CODE_FIELD));
        if (entered.isEmpty()) {
            context.challenge(codeForm(context, config, pending, codes, false)
                    .addError(new FormMessage(CODE_FIELD, Messages.CODE_MISSING)).createForm(CODE_TEMPLATE));
            return;
        }
        switch (codes.check(entered, config.maxAttempts)) {
            case OK:
                save(context, config, pending, codes);
                return;
            case WRONG:
                context.challenge(codeForm(context, config, pending, codes, false)
                        .addError(new FormMessage(CODE_FIELD, Messages.CODE_WRONG, codes.attemptsLeft(config.maxAttempts)))
                        .createForm(CODE_TEMPLATE));
                return;
            case TOO_MANY:
                // The code is used up; a new number (or the same one) needs a new text, and texts are capped by
                // "Texts per sign-in", so the wrong codes start again from zero.
                codes.resetAttempts();
                authSession.removeAuthNote(PENDING_NUMBER);
                context.challenge(phoneForm(context, pending).setError(Messages.TOO_MANY_ATTEMPTS_PHONE).createForm(PHONE_TEMPLATE));
                return;
            case EXPIRED:
                context.challenge(codeForm(context, config, pending, codes, false)
                        .addError(new FormMessage(CODE_FIELD, Messages.CODE_EXPIRED)).createForm(CODE_TEMPLATE));
                return;
            case NO_CODE:
            default:
                context.challenge(phoneForm(context, pending).createForm(PHONE_TEMPLATE));
        }
    }

    private void send(RequiredActionContext context, SmsCodeConfig config, String number, CodeChallenge codes,
            boolean again) {
        if (codes.sends() >= config.maxSends) {
            context.challenge(retryForm(context, config, number, codes).setError(Messages.NO_MORE_CODES).createForm(templateFor(codes)));
            return;
        }
        long wait = codes.secondsUntilResend(config.resendAfterSeconds);
        if (wait > 0) {
            context.challenge(retryForm(context, config, number, codes)
                    .setError(Messages.WAIT_BEFORE_RESEND, wait).createForm(templateFor(codes)));
            return;
        }
        String code = CodeChallenge.newCode(config.codeLength);
        String text = SmsText.forPhoneCheck(context.getSession(), context.getRealm(), context.getUser(), config, code);
        CodeDelivery.Outcome outcome = CodeDelivery.deliver(sender, context.getSession(), context.getRealm(), config,
                number, text);
        switch (outcome) {
            case SENT:
            case QUEUED:
                codes.remember(code, config.codeTtlSeconds);
                context.challenge(codeForm(context, config, number, codes, again).createForm(CODE_TEMPLATE));
                return;
            case REFUSED:
                context.getAuthenticationSession().removeAuthNote(PENDING_NUMBER);
                context.challenge(phoneForm(context, number)
                        .addError(new FormMessage(PHONE_FIELD, Messages.PHONE_REFUSED)).createForm(PHONE_TEMPLATE));
                return;
            case RATE_LIMITED:
                context.challenge(retryForm(context, config, number, codes).setError(Messages.TOO_MANY_TEXTS).createForm(templateFor(codes)));
                return;
            case FAILED:
                context.challenge(retryForm(context, config, number, codes).setError(Messages.SEND_FAILED).createForm(templateFor(codes)));
                return;
            case NOT_SET_UP:
            default:
                notSetUp(context);
        }
    }

    private void save(RequiredActionContext context, SmsCodeConfig config, String number, CodeChallenge codes) {
        UserModel user = context.getUser();
        try {
            user.setSingleAttribute(config.phoneAttribute, number);
            user.setSingleAttribute(config.verifiedAttribute(), "true");
        } catch (RuntimeException e) {
            LOG.warnf(e, "SMSTunnel: could not save the phone number of user %s in realm %s", user.getId(),
                    context.getRealm().getName());
            context.challenge(context.form().setError(Messages.PHONE_NOT_SAVED)
                    .createErrorPage(Response.Status.INTERNAL_SERVER_ERROR));
            return;
        }
        codes.clearAll();
        context.getAuthenticationSession().removeAuthNote(PENDING_NUMBER);
        context.getEvent().clone().event(EventType.UPDATE_PROFILE).user(user)
                .detail("smstunnel_phone_attribute", config.phoneAttribute)
                .detail("smstunnel_phone_ending", PhoneNumbers.ending(number))
                .success();
        context.success();
    }

    /** After a refused or delayed send: the code page while a code is still valid, otherwise the number page. */
    private LoginFormsProvider retryForm(RequiredActionContext context, SmsCodeConfig config, String number,
            CodeChallenge codes) {
        return codes.active() ? codeForm(context, config, number, codes, false) : phoneForm(context, number);
    }

    private static String templateFor(CodeChallenge codes) {
        return codes.active() ? CODE_TEMPLATE : PHONE_TEMPLATE;
    }

    private LoginFormsProvider phoneForm(RequiredActionContext context, String value) {
        Map<String, String> data = new HashMap<>();
        data.put("phoneNumber", value != null ? value : "");
        return context.form().setAttribute("smstunnel", data);
    }

    private LoginFormsProvider codeForm(RequiredActionContext context, SmsCodeConfig config, String number,
            CodeChallenge codes, boolean again) {
        Map<String, String> data = new HashMap<>();
        data.put("mode", "verify");
        data.put("phoneNumber", number);
        data.put("phoneEnding", PhoneNumbers.ending(number));
        data.put("ttlMinutes", Integer.toString(config.ttlMinutes()));
        data.put("codeLength", Integer.toString(config.codeLength));
        data.put("sent", Boolean.toString(codes.active()));
        data.put("again", Boolean.toString(again));
        return context.form().setAttribute("smstunnel", data);
    }

    private static void notSetUp(RequiredActionContext context) {
        LOG.errorf("SMSTunnel: realm %s has no configured SMSTunnel SMS code step, so the phone-number step can't send"
                + " codes. Add the step to a flow and set its API key.", context.getRealm().getName());
        context.challenge(context.form().setError(Messages.NOT_SET_UP).createErrorPage(Response.Status.INTERNAL_SERVER_ERROR));
    }

    private CodeChallenge codes(RequiredActionContext context) {
        return new CodeChallenge(context.getAuthenticationSession(), NOTES, clock);
    }

    @Override
    public void close() {
    }
}
