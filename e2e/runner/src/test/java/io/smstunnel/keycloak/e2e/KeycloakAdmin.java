package io.smstunnel.keycloak.e2e;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;

/** The few admin REST calls the tests need (as the bootstrap admin of the test container). */
final class KeycloakAdmin {

    private final String keycloak;
    private final String password;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1).build();

    KeycloakAdmin(String keycloak, String password) {
        this.keycloak = keycloak;
        this.password = password;
    }

    /** Waits until the test realm answers (Keycloak imports it at start). */
    void waitForRealm() throws Exception {
        long deadline = System.currentTimeMillis() + 180_000;
        while (true) {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(
                                keycloak + "/realms/" + Browser.REALM + "/.well-known/openid-configuration")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    return;
                }
            } catch (java.io.IOException notYet) {
                // starting
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Keycloak did not serve the test realm in time");
            }
            Thread.sleep(2000);
        }
    }

    private String token() throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "password");
        params.put("client_id", "admin-cli");
        params.put("username", "admin");
        params.put("password", password);
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(
                        keycloak + "/realms/master/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(Browser.form(params))).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new AssertionError("Admin sign-in failed: HTTP " + r.statusCode() + " " + r.body());
        }
        return Document.parse(r.body()).getString("access_token");
    }

    private HttpResponse<String> call(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(keycloak + "/admin/realms/" + Browser.REALM + path))
                .header("Authorization", "Bearer " + token())
                .header("Content-Type", "application/json");
        b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** A user's attributes ({@code attributes} as name to list of values). */
    @SuppressWarnings("unchecked")
    Map<String, List<String>> attributesOf(String username) throws Exception {
        HttpResponse<String> r = call("GET", "/users?exact=true&briefRepresentation=false&username="
                + URLEncoder.encode(username, StandardCharsets.UTF_8), null);
        List<Document> users = Document.parse("{\"users\":" + r.body() + "}").getList("users", Document.class);
        if (users.isEmpty()) {
            throw new AssertionError("No user " + username);
        }
        Document attributes = users.get(0).get("attributes", Document.class);
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (attributes != null) {
            for (String name : attributes.keySet()) {
                result.put(name, (List<String>) attributes.get(name));
            }
        }
        return result;
    }

    /** Turns a required action on or off in the test realm. */
    void setRequiredActionEnabled(String alias, boolean enabled) throws Exception {
        HttpResponse<String> current = call("GET", "/authentication/required-actions/" + alias, null);
        if (current.statusCode() != 200) {
            throw new AssertionError("No required action " + alias + ": " + current.body());
        }
        Document action = Document.parse(current.body());
        action.put("enabled", enabled);
        HttpResponse<String> r = call("PUT", "/authentication/required-actions/" + alias, action.toJson());
        if (r.statusCode() / 100 != 2) {
            throw new AssertionError("Could not update " + alias + ": HTTP " + r.statusCode() + " " + r.body());
        }
    }
}
