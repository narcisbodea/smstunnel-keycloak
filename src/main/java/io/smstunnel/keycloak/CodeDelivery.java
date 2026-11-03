package io.smstunnel.keycloak;

import io.smstunnel.SendResult;
import io.smstunnel.SmsTunnelApiException;
import io.smstunnel.SmsTunnelAuthenticationException;
import io.smstunnel.SmsTunnelConnectionException;
import io.smstunnel.SmsTunnelException;
import io.smstunnel.SmsTunnelPermissionException;
import io.smstunnel.SmsTunnelRateLimitException;

import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

/**
 * Sends one code and says how it went, in terms the sign-in pages can show. The log names the realm and the last
 * digits of the number, never the number, the code or the key.
 */
final class CodeDelivery {

    enum Outcome {
        /** SMSTunnel accepted the text. */
        SENT,
        /** SMSTunnel accepted it, but the phone is offline: it goes out when the phone is back. */
        QUEUED,
        /** No usable API key, a key SMSTunnel refuses, or a setting that is not valid: the admin must fix it. */
        NOT_SET_UP,
        /** SMSTunnel refused the number or the text. */
        REFUSED,
        /** Too many texts in a short time, or the plan's allowance for the day is used up. */
        RATE_LIMITED,
        /** SMSTunnel could not be reached or had a problem: trying again later can work. */
        FAILED
    }

    private static final Logger LOG = Logger.getLogger(CodeDelivery.class);

    private CodeDelivery() {
    }

    static Outcome deliver(CodeSender sender, KeycloakSession session, RealmModel realm, SmsCodeConfig config,
            String to, String text) {
        String where = "realm " + realm.getName() + ", number ending " + PhoneNumbers.ending(to);
        String apiKey;
        try {
            apiKey = config.resolveApiKey(session);
        } catch (RuntimeException e) {
            LOG.errorf(e, "SMSTunnel SMS code: the API key could not be read from the vault (%s)", where);
            return Outcome.NOT_SET_UP;
        }
        if (apiKey == null) {
            LOG.errorf("SMSTunnel SMS code: no API key in the step's configuration (%s). Add one under"
                    + " Authentication, the flow, the SMSTunnel SMS code step's settings.", where);
            return Outcome.NOT_SET_UP;
        }
        try {
            SendResult result = sender.send(config, apiKey, to, text);
            if (result.isQueued()) {
                LOG.warnf("SMSTunnel SMS code: the SMSTunnel phone is offline; the code waits and goes out when it"
                        + " is back (%s, message %s)", where, result.getMessageId());
                return Outcome.QUEUED;
            }
            LOG.debugf("SMSTunnel SMS code: code sent (%s, message %s)", where, result.getMessageId());
            return Outcome.SENT;
        } catch (SmsTunnelAuthenticationException | SmsTunnelPermissionException e) {
            LOG.errorf("SMSTunnel SMS code: SMSTunnel refused the API key (%s): %s", where, e);
            return Outcome.NOT_SET_UP;
        } catch (SmsTunnelRateLimitException e) {
            LOG.warnf("SMSTunnel SMS code: SMSTunnel asked to slow down (%s): %s", where, e);
            return Outcome.RATE_LIMITED;
        } catch (SmsTunnelApiException e) {
            if (e.getStatusCode() >= 400 && e.getStatusCode() < 500) {
                LOG.warnf("SMSTunnel SMS code: SMSTunnel refused the text (%s): %s", where, e);
                return Outcome.REFUSED;
            }
            LOG.warnf("SMSTunnel SMS code: SMSTunnel had a problem (%s): %s", where, e);
            return Outcome.FAILED;
        } catch (SmsTunnelConnectionException e) {
            LOG.warnf("SMSTunnel SMS code: SMSTunnel could not be reached at %s (%s): %s", config.baseUrl, where,
                    e.getMessage());
            return Outcome.FAILED;
        } catch (SmsTunnelException e) {
            LOG.warnf("SMSTunnel SMS code: the code did not go out (%s): %s", where, e);
            return Outcome.FAILED;
        } catch (IllegalArgumentException e) {
            LOG.errorf("SMSTunnel SMS code: a setting of the step is not valid (%s): %s", where, e.getMessage());
            return Outcome.NOT_SET_UP;
        }
    }
}
