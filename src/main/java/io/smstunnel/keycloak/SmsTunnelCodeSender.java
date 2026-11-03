package io.smstunnel.keycloak;

import io.smstunnel.SendOptions;
import io.smstunnel.SendResult;
import io.smstunnel.SmsTunnelClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.keycloak.common.Version;

/**
 * Sends sign-in codes through SMSTunnel's sign-in code route ({@code POST /api/v1/sms/send-2fa}). One client per base
 * URL and API key is kept (at most 32), so their connections are reused.
 */
final class SmsTunnelCodeSender implements CodeSender {

    private static final int MAX_CLIENTS = 32;

    private final Map<String, SmsTunnelClient> clients = Collections.synchronizedMap(
            new LinkedHashMap<String, SmsTunnelClient>(16, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SmsTunnelClient> eldest) {
                    return size() > MAX_CLIENTS;
                }
            });

    @Override
    public SendResult send(SmsCodeConfig config, String apiKey, String to, String text) {
        SmsTunnelClient client = clients.computeIfAbsent(cacheKey(config.baseUrl, apiKey), key -> SmsTunnelClient.builder()
                .apiKey(apiKey)
                .baseUrl(config.baseUrl)
                // Someone is waiting on the sign-in page: give up sooner than a background job would.
                .timeout(Duration.ofSeconds(10))
                .connectTimeout(Duration.ofSeconds(5))
                .maxRetries(1)
                .userAgentSuffix("smstunnel-keycloak/" + version() + " Keycloak/" + Version.VERSION)
                .build());
        SendOptions.Builder options = SendOptions.builder();
        if (config.deviceId != null) {
            options.deviceId(config.deviceId);
        }
        if (config.defaultCountryCode != null) {
            options.defaultCountryCode(config.defaultCountryCode);
        }
        return client.sendSignInCode(to, text, options.build());
    }

    private static String cacheKey(String baseUrl, String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((baseUrl + "\n" + apiKey).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String version() {
        String version = SmsTunnelCodeSender.class.getPackage() != null
                ? SmsTunnelCodeSender.class.getPackage().getImplementationVersion()
                : null;
        return version != null ? version : "dev";
    }
}
