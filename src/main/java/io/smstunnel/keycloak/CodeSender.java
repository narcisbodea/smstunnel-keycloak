package io.smstunnel.keycloak;

import io.smstunnel.SendResult;

/** Sends one sign-in text. The real one goes through SMSTunnel; tests use their own. */
interface CodeSender {

    /**
     * @throws io.smstunnel.SmsTunnelException when SMSTunnel refuses the text or can't be reached
     * @throws IllegalArgumentException when the configuration is unusable (base URL, country code)
     */
    SendResult send(SmsCodeConfig config, String apiKey, String to, String text);
}
