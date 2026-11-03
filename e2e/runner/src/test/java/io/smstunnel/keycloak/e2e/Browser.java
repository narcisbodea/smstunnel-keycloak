package io.smstunnel.keycloak.e2e;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.bson.Document;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/**
 * A browser without a screen: keeps cookies, follows Keycloak's redirects, reads and submits its forms, and stops at
 * the redirect to the application, where it trades the authorization code for tokens.
 */
final class Browser {

    static final String REDIRECT_URI = "http://localhost/smstunnel-e2e/callback";
    static final String REALM = "smstunnel-test";

    private final String keycloak;
    private final HttpClient http;
    private final String verifier;
    private String clientId;

    Browser(String keycloak) {
        this.keycloak = keycloak;
        this.http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        this.verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    /** One page as the browser got it, or the redirect to the application. */
    static final class Page {
        final int status;
        final URI uri;
        final String body;
        final org.jsoup.nodes.Document doc;
        final String appRedirect;

        Page(int status, URI uri, String body, String appRedirect) {
            this.status = status;
            this.uri = uri;
            this.body = body;
            this.doc = Jsoup.parse(body == null ? "" : body, uri.toString());
            this.appRedirect = appRedirect;
        }

        boolean has(String id) {
            return doc.getElementById(id) != null;
        }

        Element form(String id) {
            Element form = doc.getElementById(id);
            if (form == null) {
                throw new AssertionError("No #" + id + " on " + uri + " (HTTP " + status + "): " + text());
            }
            return form;
        }

        String text() {
            return doc.text();
        }

        /** The text of a field's error, or "". */
        String fieldError(String field) {
            Element error = doc.getElementById("input-error-" + field);
            return error == null ? "" : error.text();
        }

        boolean signedIn() {
            return appRedirect != null && query(appRedirect).containsKey("code");
        }

        String authorizationCode() {
            return query(appRedirect).get("code");
        }

        @Override
        public String toString() {
            return "HTTP " + status + " " + uri + (appRedirect != null ? " -> " + appRedirect : "") + ": " + text();
        }
    }

    /** Opens the sign-in page of a client, with extra parameters such as ui_locales or kc_action. */
    Page startSignIn(String clientId, Map<String, String> extra) throws Exception {
        this.clientId = clientId;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", clientId);
        params.put("redirect_uri", REDIRECT_URI);
        params.put("response_type", "code");
        params.put("scope", "openid phone");
        params.put("state", Long.toHexString(System.nanoTime()));
        params.put("code_challenge", challenge());
        params.put("code_challenge_method", "S256");
        params.putAll(extra);
        return get(keycloak + "/realms/" + REALM + "/protocol/openid-connect/auth?" + form(params));
    }

    Page get(String url) throws Exception {
        return follow(HttpRequest.newBuilder(URI.create(url)).GET().build(), 0);
    }

    /** Submits a form with its own fields (hidden ones included), the given values, and no particular button. */
    Page submit(Page page, String formId, Map<String, String> values) throws Exception {
        return post(page, formId, values, null);
    }

    /** Submits a form by clicking the button with this name (it must be on the form). */
    Page click(Page page, String formId, Map<String, String> values, String button) throws Exception {
        return post(page, formId, values, button);
    }

    private Page post(Page page, String formId, Map<String, String> values, String button) throws Exception {
        Element form = page.form(formId);
        Map<String, String> data = new LinkedHashMap<>();
        for (Element input : form.select("input[name]")) {
            String type = input.attr("type").toLowerCase();
            if (type.equals("submit") || type.equals("button")) {
                continue;
            }
            if ((type.equals("checkbox") || type.equals("radio")) && !input.hasAttr("checked")) {
                continue;
            }
            data.put(input.attr("name"), input.val());
        }
        data.putAll(values);
        if (button != null) {
            Element clicked = form.selectFirst("button[name=" + button + "], input[type=submit][name=" + button + "]");
            if (clicked == null) {
                throw new AssertionError("No button '" + button + "' on #" + formId + ": " + page.text());
            }
            data.put(button, clicked.val().isEmpty() ? "true" : clicked.val());
        }
        String action = form.absUrl("action");
        HttpRequest request = HttpRequest.newBuilder(URI.create(action))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(data)))
                .build();
        return follow(request, 0);
    }

    private Page follow(HttpRequest request, int hops) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String location = response.headers().firstValue("Location").orElse(null);
        if (response.statusCode() / 100 == 3 && location != null) {
            URI next = request.uri().resolve(location);
            if (next.toString().startsWith(REDIRECT_URI)) {
                return new Page(response.statusCode(), request.uri(), response.body(), next.toString());
            }
            if (hops > 10) {
                throw new AssertionError("Too many redirects, last " + next);
            }
            return follow(HttpRequest.newBuilder(next).GET().build(), hops + 1);
        }
        return new Page(response.statusCode(), request.uri(), response.body(), null);
    }

    /** Trades the authorization code for tokens and returns the ID token's claims. */
    Document idTokenClaims(Page signedIn) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "authorization_code");
        params.put("client_id", clientId);
        params.put("code", signedIn.authorizationCode());
        params.put("redirect_uri", REDIRECT_URI);
        params.put("code_verifier", verifier);
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create(keycloak + "/realms/" + REALM + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(params)))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new AssertionError("Token request failed: HTTP " + response.statusCode() + " " + response.body());
        }
        String idToken = Document.parse(response.body()).getString("id_token");
        String payload = idToken.split("\\.")[1];
        return Document.parse(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
    }

    private String challenge() throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    static Map<String, String> query(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        String query = URI.create(url).getRawQuery();
        if (query == null) {
            return params;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            params.put(name, value);
        }
        return params;
    }
}
