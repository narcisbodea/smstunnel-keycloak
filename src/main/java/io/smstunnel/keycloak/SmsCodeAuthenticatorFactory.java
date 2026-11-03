package io.smstunnel.keycloak;

import java.util.List;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

/** Registers the "SMSTunnel SMS code" step and its settings (Authentication, a flow, Add step). */
public class SmsCodeAuthenticatorFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "smstunnel-sms-code";

    /** One sender for every realm: it keeps one SMSTunnel client per base URL and key. */
    static final CodeSender SENDER = new SmsTunnelCodeSender();

    private static final AuthenticationExecutionModel.Requirement[] REQUIREMENTS = {
        AuthenticationExecutionModel.Requirement.REQUIRED,
        AuthenticationExecutionModel.Requirement.ALTERNATIVE,
        AuthenticationExecutionModel.Requirement.DISABLED,
    };

    private static final List<ProviderConfigProperty> CONFIG = ProviderConfigurationBuilder.create()
            .property().name(SmsCodeConfig.API_KEY).label("API key")
            .helpText("Your SMSTunnel API key: in the SMSTunnel dashboard (https://dashboard.smstunnel.io/apps) open"
                    + " Apps, Add an app. Or a vault reference, such as ${vault.smstunnel_api_key}, to keep the key in"
                    + " Keycloak's vault instead of the database.")
            .type(ProviderConfigProperty.PASSWORD).secret(true).add()
            .property().name(SmsCodeConfig.BASE_URL).label("SMSTunnel address")
            .helpText("https://smstunnel.io. Change it only for a self-hosted SMSTunnel server.")
            .type(ProviderConfigProperty.STRING_TYPE).defaultValue(SmsCodeConfig.DEFAULT_BASE_URL).add()
            .property().name(SmsCodeConfig.DEFAULT_COUNTRY_CODE).label("Default country code")
            .helpText("For numbers saved without + or 00, such as +40 or +1. Numbers in international format"
                    + " (+40721234567) don't need it. Without it, users must type their number with + and the"
                    + " country code.")
            .type(ProviderConfigProperty.STRING_TYPE).add()
            .property().name(SmsCodeConfig.PHONE_ATTRIBUTE).label("Phone number attribute")
            .helpText("The user attribute that holds the number. phoneNumber is the one Keycloak's 'phone' client"
                    + " scope puts in tokens as phone_number. After a right code, <attribute>Verified is set to true.")
            .type(ProviderConfigProperty.STRING_TYPE).defaultValue(SmsCodeConfig.DEFAULT_PHONE_ATTRIBUTE).add()
            .property().name(SmsCodeConfig.CODE_LENGTH).label("Code length")
            .helpText("Digits in the code, 4 to 10.")
            .type(ProviderConfigProperty.STRING_TYPE).defaultValue(Integer.toString(SmsCodeConfig.DEFAULT_CODE_LENGTH)).add()
            .property().name(SmsCodeConfig.CODE_TTL).label("Code lifetime (seconds)")
            .helpText("How long a code works, 30 to 3600 seconds.")
            .type(ProviderConfigProperty.STRING_TYPE).defaultValue(Integer.toString(SmsCodeConfig.DEFAULT_CODE_TTL)).add()
            .property().name(SmsCodeConfig.MAX_ATTEMPTS).label("Wrong codes allowed")
            .helpText("Wrong codes in one sign-in before it starts over. Turn on brute force detection (Realm"
                    + " settings, Security defenses) to also lock out repeated failures.")
            .type(ProviderConfigProperty.STRING_TYPE).defaultValue(Integer.toString(SmsCodeConfig.DEFAULT_MAX_ATTEMPTS)).add()
            .property().name(SmsCodeConfig.MAX_SENDS).label("Texts per sign-in")
            .helpText("How many codes one sign-in may send, the first one and new ones the user asks for.")
            .type(ProviderConfigProperty.STRING_TYPE).defaultValue(Integer.toString(SmsCodeConfig.DEFAULT_MAX_SENDS)).add()
            .property().name(SmsCodeConfig.RESEND_AFTER).label("Wait before a new code (seconds)")
            .helpText("How long a user waits before asking for a new code.")
            .type(ProviderConfigProperty.STRING_TYPE).defaultValue(Integer.toString(SmsCodeConfig.DEFAULT_RESEND_AFTER)).add()
            .property().name(SmsCodeConfig.SMS_TEXT).label("Text of the SMS")
            .helpText("Leave empty for the built-in text in the user's language (English and Romanian included; change"
                    + " it per language with the key smstunnelSmsText in Realm settings, Localization). Placeholders:"
                    + " {code}, {realm} and {ttl} (minutes). The code must stand on its own, and the text must stay"
                    + " under 320 characters.")
            .type(ProviderConfigProperty.STRING_TYPE).add()
            .property().name(SmsCodeConfig.DEVICE_ID).label("Phone ID (optional)")
            .helpText("Send through one particular phone of your SMSTunnel account: its ID is under Phones in the"
                    + " dashboard. Empty: SMSTunnel picks one of your phones.")
            .type(ProviderConfigProperty.STRING_TYPE).add()
            .build();

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "SMSTunnel SMS code";
    }

    @Override
    public String getReferenceCategory() {
        return "sms";
    }

    @Override
    public String getHelpText() {
        return "Texts a one-time sign-in code to the user's phone number, from your own Android phone and your own"
                + " number, through SMSTunnel.";
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return REQUIREMENTS;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return true;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG;
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return new SmsCodeAuthenticator(SENDER, System::currentTimeMillis);
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }
}
