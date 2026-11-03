package io.smstunnel.keycloak;

import org.keycloak.Config;
import org.keycloak.authentication.RequiredActionFactory;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/** Registers "SMSTunnel: add a phone number" (Authentication, Required actions). */
public class PhoneNumberRequiredActionFactory implements RequiredActionFactory {

    public static final String PROVIDER_ID = "smstunnel-phone-number";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayText() {
        return "SMSTunnel: add a phone number";
    }

    @Override
    public RequiredActionProvider create(KeycloakSession session) {
        return new PhoneNumberRequiredAction(SmsCodeAuthenticatorFactory.SENDER, System::currentTimeMillis);
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
