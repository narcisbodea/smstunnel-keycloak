package io.smstunnel.keycloak;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jboss.logging.Logger;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.AuthenticationFlowModel;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.vault.VaultStringSecret;

/**
 * The settings of an "SMSTunnel SMS code" step, from its configuration in the realm's flow (Authentication, the step's
 * gear icon). Reading never fails: a missing or wrong value falls back to its default, with a warning in the log.
 */
public final class SmsCodeConfig {

    static final String API_KEY = "apiKey";
    static final String BASE_URL = "baseUrl";
    static final String DEFAULT_COUNTRY_CODE = "defaultCountryCode";
    static final String DEVICE_ID = "deviceId";
    static final String CODE_LENGTH = "codeLength";
    static final String CODE_TTL = "codeTtl";
    static final String SMS_TEXT = "smsText";
    static final String MAX_ATTEMPTS = "maxAttempts";
    static final String MAX_SENDS = "maxSends";
    static final String RESEND_AFTER = "resendAfter";
    static final String PHONE_ATTRIBUTE = "phoneAttribute";

    static final String DEFAULT_BASE_URL = "https://smstunnel.io";
    static final String DEFAULT_PHONE_ATTRIBUTE = "phoneNumber";
    static final int DEFAULT_CODE_LENGTH = 6;
    static final int DEFAULT_CODE_TTL = 300;
    static final int DEFAULT_MAX_ATTEMPTS = 5;
    static final int DEFAULT_MAX_SENDS = 3;
    static final int DEFAULT_RESEND_AFTER = 30;

    private static final Logger LOG = Logger.getLogger(SmsCodeConfig.class);

    final String apiKey;
    final String baseUrl;
    final String defaultCountryCode;
    final String deviceId;
    final int codeLength;
    final int codeTtlSeconds;
    final String smsText;
    final int maxAttempts;
    final int maxSends;
    final int resendAfterSeconds;
    final String phoneAttribute;

    private SmsCodeConfig(Map<String, String> config) {
        Map<String, String> c = config != null ? config : Collections.emptyMap();
        this.apiKey = text(c, API_KEY);
        String url = text(c, BASE_URL);
        this.baseUrl = url != null ? url : DEFAULT_BASE_URL;
        this.defaultCountryCode = text(c, DEFAULT_COUNTRY_CODE);
        this.deviceId = text(c, DEVICE_ID);
        this.codeLength = number(c, CODE_LENGTH, DEFAULT_CODE_LENGTH, 4, 10);
        this.codeTtlSeconds = number(c, CODE_TTL, DEFAULT_CODE_TTL, 30, 3600);
        this.smsText = text(c, SMS_TEXT);
        this.maxAttempts = number(c, MAX_ATTEMPTS, DEFAULT_MAX_ATTEMPTS, 1, 20);
        this.maxSends = number(c, MAX_SENDS, DEFAULT_MAX_SENDS, 1, 10);
        this.resendAfterSeconds = number(c, RESEND_AFTER, DEFAULT_RESEND_AFTER, 0, 600);
        String attribute = text(c, PHONE_ATTRIBUTE);
        this.phoneAttribute = attribute != null ? attribute : DEFAULT_PHONE_ATTRIBUTE;
    }

    /** The settings of one step; null (a step without configuration) gives the defaults, without an API key. */
    public static SmsCodeConfig of(AuthenticatorConfigModel model) {
        return new SmsCodeConfig(model != null ? model.getConfig() : null);
    }

    static SmsCodeConfig of(Map<String, String> config) {
        return new SmsCodeConfig(config);
    }

    /**
     * The settings of the realm's first configured "SMSTunnel SMS code" step: in the browser flow first, then in any
     * flow. The phone-number step uses them, as does the check whether a user has a number. Null when the realm has
     * none.
     */
    public static SmsCodeConfig forRealm(RealmModel realm) {
        return findConfig(realm).map(SmsCodeConfig::of).orElse(null);
    }

    static Optional<AuthenticatorConfigModel> findConfig(RealmModel realm) {
        if (realm == null) {
            return Optional.empty();
        }
        Set<String> seen = new HashSet<>();
        AuthenticationFlowModel browser = realm.getBrowserFlow();
        if (browser != null) {
            Optional<AuthenticatorConfigModel> found = findIn(realm, browser.getId(), seen);
            if (found.isPresent()) {
                return found;
            }
        }
        return realm.getAuthenticationFlowsStream()
                .map(flow -> findIn(realm, flow.getId(), seen))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private static Optional<AuthenticatorConfigModel> findIn(RealmModel realm, String flowId, Set<String> seen) {
        if (flowId == null || !seen.add(flowId)) {
            return Optional.empty();
        }
        for (AuthenticationExecutionModel execution : (Iterable<AuthenticationExecutionModel>)
                realm.getAuthenticationExecutionsStream(flowId)::iterator) {
            if (execution.isAuthenticatorFlow()) {
                Optional<AuthenticatorConfigModel> nested = findIn(realm, execution.getFlowId(), seen);
                if (nested.isPresent()) {
                    return nested;
                }
            } else if (SmsCodeAuthenticatorFactory.PROVIDER_ID.equals(execution.getAuthenticator())
                    && execution.getAuthenticatorConfig() != null
                    && execution.getRequirement() != AuthenticationExecutionModel.Requirement.DISABLED) {
                AuthenticatorConfigModel config = realm.getAuthenticatorConfigById(execution.getAuthenticatorConfig());
                if (config != null) {
                    return Optional.of(config);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The API key: the configured value, or the secret it names when it is a vault reference such as
     * {@code ${vault.smstunnel_api_key}}. Null when there is none.
     */
    String resolveApiKey(KeycloakSession session) {
        if (apiKey == null) {
            return null;
        }
        if (!apiKey.startsWith("${vault.")) {
            return apiKey;
        }
        try (VaultStringSecret secret = session.vault().getStringSecret(apiKey)) {
            Optional<String> value = secret.get().map(String::trim).filter(s -> !s.isEmpty());
            if (value.isEmpty()) {
                LOG.warnf("SMSTunnel SMS code: the vault has no secret for %s", apiKey);
            }
            return value.orElse(null);
        }
    }

    /** The user's number from the configured attribute, trimmed, or null. */
    String phoneOf(UserModel user) {
        String value = user != null ? user.getFirstAttribute(phoneAttribute) : null;
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The attribute set to "true" once a code sent to the number was entered: {@code phoneNumberVerified}. */
    String verifiedAttribute() {
        return phoneAttribute + "Verified";
    }

    /** The lifetime in whole minutes, for the texts. */
    int ttlMinutes() {
        return (codeTtlSeconds + 59) / 60;
    }

    private static String text(Map<String, String> config, String name) {
        String value = config.get(name);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int number(Map<String, String> config, String name, int fallback, int min, int max) {
        String value = text(config, name);
        if (value == null) {
            return fallback;
        }
        try {
            int n = Integer.parseInt(value);
            if (n < min || n > max) {
                LOG.warnf("SMSTunnel SMS code: %s=%s is outside %d..%d; using %d", name, value, min, max,
                        Math.max(min, Math.min(max, n)));
                return Math.max(min, Math.min(max, n));
            }
            return n;
        } catch (NumberFormatException e) {
            LOG.warnf("SMSTunnel SMS code: %s=%s is not a whole number; using %d", name, value, fallback);
            return fallback;
        }
    }
}
