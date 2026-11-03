package io.smstunnel.keycloak.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Signs in to Keycloak 26 (with this provider and the test realm) the way a person does: username and password, then
 * the code that the SMSTunnel test server sent through its simulated phone, read from the server's database.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BrowserLoginE2ETest {

    private static final String KEYCLOAK = System.getenv().getOrDefault("KEYCLOAK_URL", "http://keycloak:8080");
    private static final String CODE_FORM = "kc-smstunnel-code-form";
    private static final String PHONE_FORM = "kc-smstunnel-phone-form";
    private static final String LOGIN_FORM = "kc-form-login";

    private static SentTexts texts;
    private static KeycloakAdmin admin;

    @BeforeAll
    static void connect() throws Exception {
        String mongo = System.getenv("SMSTUNNEL_E2E_MONGO_URL");
        if (mongo == null || mongo.isEmpty()) {
            throw new IllegalStateException("Set SMSTUNNEL_E2E_MONGO_URL to the SMSTunnel test server's database");
        }
        texts = new SentTexts(mongo);
        admin = new KeycloakAdmin(KEYCLOAK, System.getenv().getOrDefault("KEYCLOAK_ADMIN_PASSWORD", "admin-e2e-only"));
        admin.waitForRealm();
    }

    @AfterAll
    static void close() {
        if (texts != null) {
            texts.close();
        }
    }

    private static Date now() {
        // A little earlier, in case the container's clock and the server's differ slightly.
        return new Date(System.currentTimeMillis() - 2000);
    }

    private static Browser.Page password(Browser browser, Browser.Page login, String user) throws Exception {
        login.form(LOGIN_FORM);
        return browser.submit(login, LOGIN_FORM, Map.of("username", user, "password", user + "-e2e-password"));
    }

    private static Browser.Page enter(Browser browser, Browser.Page page, String code) throws Exception {
        return browser.click(page, CODE_FORM, Map.of("code", code), "login");
    }

    @Test
    @Order(1)
    void signsInWithTheCodeFromThePhone() throws Exception {
        Browser browser = new Browser(KEYCLOAK);
        Date since = now();

        Browser.Page page = password(browser, browser.startSignIn("test-app", Map.of()), "alice");

        page.form(CODE_FORM);
        assertTrue(page.text().contains("We sent a code by text message to your phone number ending in 7351"), page.text());
        Document text = texts.latestTo("+14155557351", since);
        String code = SentTexts.codeIn(text);
        assertEquals(code + " is your SMSTunnel Test sign-in code. It expires in 5 minutes.", text.getString("message"));
        assertEquals("outgoing", text.getString("type"));

        Browser.Page done = enter(browser, page, code);

        assertTrue(done.signedIn(), done.toString());
        Document claims = browser.idTokenClaims(done);
        assertEquals("alice", claims.getString("preferred_username"));
        assertEquals("+14155557351", claims.getString("phone_number"));
        assertEquals(Boolean.TRUE, claims.getBoolean("phone_number_verified"), "a right code marks the number checked");
    }

    @Test
    @Order(2)
    void aWrongCodeIsRefusedAndTheRightOneStillWorks() throws Exception {
        Browser browser = new Browser(KEYCLOAK);
        Date since = now();
        Browser.Page page = password(browser, browser.startSignIn("test-app", Map.of()), "carol");
        String code = SentTexts.codeIn(texts.latestTo("+14155557353", since));
        String wrong = code.equals("000000") ? "111111" : "000000";

        Browser.Page refused = enter(browser, page, wrong);

        refused.form(CODE_FORM);
        assertEquals("That code is not right. Tries left: 2.", refused.fieldError("code"));
        Browser.Page done = enter(browser, refused, code);
        assertTrue(done.signedIn(), done.toString());
    }

    @Test
    @Order(3)
    void tooManyWrongCodesStartTheSignInOver() throws Exception {
        Browser browser = new Browser(KEYCLOAK);
        Date since = now();
        Browser.Page page = password(browser, browser.startSignIn("test-app", Map.of()), "dave");
        String code = SentTexts.codeIn(texts.latestTo("+14155557354", since));
        String wrong = code.equals("000000") ? "111111" : "000000";

        page = enter(browser, page, wrong);
        page = enter(browser, page, wrong);
        Browser.Page restarted = enter(browser, page, wrong);

        assertFalse(restarted.signedIn());
        assertTrue(restarted.has(LOGIN_FORM), "back to the username and password: " + restarted);
        assertTrue(restarted.text().contains("Too many wrong codes. Sign in again."), restarted.text());
    }

    @Test
    @Order(4)
    void aNewCodeComesAfterTheWaitAndReplacesTheOldOne() throws Exception {
        Browser browser = new Browser(KEYCLOAK);
        Date since = now();
        Browser.Page page = password(browser, browser.startSignIn("test-app", Map.of()), "erin");
        String first = SentTexts.codeIn(texts.latestTo("+14155557355", since));

        Browser.Page tooSoon = browser.click(page, CODE_FORM, Map.of(), "resend");
        assertTrue(tooSoon.text().matches(".*Wait \\d seconds before asking for a new code.*"), tooSoon.text());
        assertEquals(1, texts.allTo("+14155557355", since).size());

        Thread.sleep(5500);
        Browser.Page again = browser.click(tooSoon, CODE_FORM, Map.of(), "resend");
        assertTrue(again.text().contains("We sent a new code to your phone number ending in 7355"), again.text());
        List<Document> sent = texts.allTo("+14155557355", since);
        assertEquals(2, sent.size());
        String second = SentTexts.codeIn(sent.get(1));

        if (!second.equals(first)) {
            Browser.Page old = enter(browser, again, first);
            assertEquals("That code is not right. Tries left: 2.", old.fieldError("code"));
            again = old;
        }
        Browser.Page done = enter(browser, again, second);
        assertTrue(done.signedIn(), done.toString());
    }

    @Test
    @Order(5)
    void aUserWithoutANumberAddsAndConfirmsOneAtSignIn() throws Exception {
        Browser browser = new Browser(KEYCLOAK);
        Date since = now();

        Browser.Page phonePage = password(browser, browser.startSignIn("test-app", Map.of()), "bob");

        phonePage.form(PHONE_FORM);
        assertTrue(phonePage.text().contains("Add your phone number"), phonePage.text());
        Browser.Page invalid = browser.submit(phonePage, PHONE_FORM, Map.of("phoneNumber", "call me maybe"));
        assertTrue(invalid.fieldError("phoneNumber").startsWith("This does not look like a mobile phone number"), invalid.text());

        // A local number: the step's default country code (+1) completes it.
        Browser.Page codePage = browser.submit(invalid, PHONE_FORM, Map.of("phoneNumber", "415 555 7352"));
        codePage.form(CODE_FORM);
        assertTrue(codePage.text().contains("We sent a code to +14155557352. Enter it to confirm the number."), codePage.text());
        Document text = texts.latestTo("+14155557352", since);
        String code = SentTexts.codeIn(text);
        assertEquals(code + " is your code to confirm this phone number for SMSTunnel Test.", text.getString("message"));
        assertTrue(admin.attributesOf("bob").getOrDefault("phoneNumber", Collections.emptyList()).isEmpty(),
                "not saved before the code comes back");

        Browser.Page done = browser.click(codePage, CODE_FORM, Map.of("code", code), "login");

        assertTrue(done.signedIn(), done.toString());
        assertEquals("+14155557352", browser.idTokenClaims(done).getString("phone_number"));
        Map<String, List<String>> attributes = admin.attributesOf("bob");
        assertEquals(List.of("+14155557352"), attributes.get("phoneNumber"));
        assertEquals(List.of("true"), attributes.get("phoneNumberVerified"));

        // The next sign-in asks for a code sent to the new number.
        Browser next = new Browser(KEYCLOAK);
        Date nextSince = now();
        Browser.Page nextPage = password(next, next.startSignIn("test-app", Map.of()), "bob");
        nextPage.form(CODE_FORM);
        Browser.Page nextDone = enter(next, nextPage, SentTexts.codeIn(texts.latestTo("+14155557352", nextSince)));
        assertTrue(nextDone.signedIn(), nextDone.toString());
    }

    @Test
    @Order(6)
    void speaksRomanianPagesAndTexts() throws Exception {
        Browser browser = new Browser(KEYCLOAK);
        Date since = now();

        Browser.Page page = password(browser, browser.startSignIn("test-app", Map.of("ui_locales", "ro")), "frank");

        page.form(CODE_FORM);
        assertTrue(page.text().contains("Ți-am trimis un cod prin SMS la numărul de telefon care se termină în 7356."), page.text());
        assertTrue(page.text().contains("Trimite un cod nou"), page.text());
        Document text = texts.latestTo("+14155557356", since);
        String code = SentTexts.codeIn(text);
        assertEquals(code + " este codul tau de conectare la SMSTunnel Test. Expira in 5 minute.", text.getString("message"));

        Browser.Page done = enter(browser, page, code);
        assertTrue(done.signedIn(), done.toString());
    }

    @Test
    @Order(7)
    void worksWithTheOlderLoginThemeToo() throws Exception {
        Browser browser = new Browser(KEYCLOAK);
        Date since = now();

        Browser.Page page = password(browser, browser.startSignIn("test-app-legacy-theme", Map.of()), "henry");

        page.form(CODE_FORM);
        assertTrue(page.doc.selectFirst("input#code").hasClass("pf-c-form-control"), "the older theme's field class");
        Browser.Page done = enter(browser, page, SentTexts.codeIn(texts.latestTo("+14155557357", since)));
        assertTrue(done.signedIn(), done.toString());
    }

    @Test
    @Order(8)
    void anApplicationCanAskTheUserToChangeTheNumber() throws Exception {
        Browser browser = new Browser(KEYCLOAK);
        Date since = now();
        Browser.Page page = password(browser, browser.startSignIn("test-app", Map.of()), "ivy");
        Browser.Page signedIn = enter(browser, page, SentTexts.codeIn(texts.latestTo("+14155557358", since)));
        assertTrue(signedIn.signedIn(), signedIn.toString());

        // Signed in a moment ago, so Keycloak goes straight to the step (no new sign-in).
        Browser.Page phonePage = browser.startSignIn("test-app", Map.of("kc_action", "smstunnel-phone-number"));
        phonePage.form(PHONE_FORM);
        assertTrue(phonePage.has("kc-smstunnel-cancel"), "an application-started step can be cancelled");
        Date changeSince = now();
        Browser.Page codePage = browser.submit(phonePage, PHONE_FORM, Map.of("phoneNumber", "+1 415 555 7359"));
        String code = SentTexts.codeIn(texts.latestTo("+14155557359", changeSince));
        Browser.Page done = browser.click(codePage, CODE_FORM, Map.of("code", code), "login");

        assertTrue(done.appRedirect != null, done.toString());
        assertEquals("success", Browser.query(done.appRedirect).get("kc_action_status"), done.appRedirect);
        assertEquals(List.of("+14155557359"), admin.attributesOf("ivy").get("phoneNumber"));
    }

    @Test
    @Order(9)
    void withoutTheEnabledPhoneStepAUserWithoutANumberIsNotLetIn() throws Exception {
        admin.setRequiredActionEnabled("smstunnel-phone-number", false);
        try {
            Browser browser = new Browser(KEYCLOAK);

            Browser.Page page = password(browser, browser.startSignIn("test-app", Map.of()), "gina");

            assertFalse(page.signedIn(), "signed in without a code: " + page);
            assertFalse(page.has(PHONE_FORM));
            assertFalse(page.has(CODE_FORM));
            assertTrue(page.text().toLowerCase().contains("credential setup required"), page.text());
        } finally {
            admin.setRequiredActionEnabled("smstunnel-phone-number", true);
        }
    }
}
