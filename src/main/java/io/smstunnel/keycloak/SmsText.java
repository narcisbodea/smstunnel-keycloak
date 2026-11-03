package io.smstunnel.keycloak;

import java.io.IOException;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.theme.Theme;

/**
 * The words of the text with the code. Without a template in the configuration, the text comes from the login theme's
 * messages in the user's language ({@code smstunnelSmsText}, which Realm settings, Localization, Realm overrides can
 * change per language). Placeholders: {@code {code}}, {@code {realm}} and {@code {ttl}} (minutes).
 */
final class SmsText {

    /** SMSTunnel refuses a sign-in text longer than this. */
    static final int MAX_LENGTH = 320;

    static final String LOGIN_KEY = "smstunnelSmsText";
    static final String PHONE_KEY = "smstunnelPhoneSmsText";
    static final String DEFAULT_LOGIN = "{code} is your {realm} sign-in code. It expires in {ttl} minutes.";
    static final String DEFAULT_PHONE = "{code} is your code to confirm this phone number for {realm}.";

    private static final Logger LOG = Logger.getLogger(SmsText.class);

    private SmsText() {
    }

    static String forLogin(KeycloakSession session, RealmModel realm, UserModel user, SmsCodeConfig config, String code) {
        return build(session, realm, user, config, code, LOGIN_KEY, DEFAULT_LOGIN);
    }

    static String forPhoneCheck(KeycloakSession session, RealmModel realm, UserModel user, SmsCodeConfig config,
            String code) {
        return build(session, realm, user, config, code, PHONE_KEY, DEFAULT_PHONE);
    }

    private static String build(KeycloakSession session, RealmModel realm, UserModel user, SmsCodeConfig config,
            String code, String key, String fallback) {
        String template = config.smsText != null ? config.smsText : localized(session, realm, user, key, fallback);
        String text = render(template, code, realmName(realm), config.ttlMinutes());
        if (!usable(text, code)) {
            LOG.warnf("SMSTunnel SMS code: the text for realm %s must contain {code} as a number of its own and stay"
                    + " under %d characters; the built-in text is used instead", realm != null ? realm.getName() : "?",
                    MAX_LENGTH);
            text = render(fallback, code, realmName(realm), config.ttlMinutes());
        }
        return text;
    }

    static String render(String template, String code, String realm, int ttlMinutes) {
        return template.replace("{realm}", realm).replace("{ttl}", Integer.toString(ttlMinutes)).replace("{code}", code);
    }

    /** The code stands on its own (SMSTunnel's rule for sign-in texts) and the text is short enough. */
    static boolean usable(String text, String code) {
        return text.length() <= MAX_LENGTH && codeStandsAlone(text, code);
    }

    /**
     * Same rule as SMSTunnel's check: no letter, digit, underscore or "+" right before the code, no letter, digit or
     * underscore right after it, and no ".", ",", ":" or "/" joining it to other digits.
     */
    static boolean codeStandsAlone(String text, String code) {
        String quoted = Pattern.quote(code);
        return Pattern.compile("(?<![\\w+]|\\d[.,:/])" + quoted + "(?!\\w|[.,:/]\\d)").matcher(text).find();
    }

    private static String localized(KeycloakSession session, RealmModel realm, UserModel user, String key,
            String fallback) {
        try {
            Locale locale = session.getContext().resolveLocale(user);
            Theme theme = session.theme().getTheme(Theme.Type.LOGIN);
            Properties messages = theme.getEnhancedMessages(realm, locale != null ? locale : Locale.ENGLISH);
            String value = messages.getProperty(key);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        } catch (IOException | RuntimeException e) {
            LOG.debugf(e, "SMSTunnel SMS code: no localized text, using the English one");
        }
        return fallback;
    }

    static String realmName(RealmModel realm) {
        if (realm == null) {
            return "";
        }
        String display = realm.getDisplayName();
        return display != null && !display.trim().isEmpty() ? display.trim() : realm.getName();
    }
}
