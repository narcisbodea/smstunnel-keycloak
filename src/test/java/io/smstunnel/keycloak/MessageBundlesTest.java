package io.smstunnel.keycloak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class MessageBundlesTest {

    private static Properties bundle(String locale) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = MessageBundlesTest.class.getResourceAsStream(
                "/theme-resources/messages/messages_" + locale + ".properties")) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = MessageBundlesTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Every key the templates and the code use. */
    private static Set<String> usedKeys() throws Exception {
        Set<String> keys = new TreeSet<>();
        for (String template : new String[] {"smstunnel-sms-code.ftl", "smstunnel-phone-number.ftl"}) {
            Matcher m = Pattern.compile("msg\\(\"(smstunnel\\w+)\"").matcher(resource("/theme-resources/templates/" + template));
            while (m.find()) {
                keys.add(m.group(1));
            }
            Matcher choice = Pattern.compile("\"(smstunnel\\w+)\", \"(smstunnel\\w+)\"").matcher(resource("/theme-resources/templates/" + template));
            while (choice.find()) {
                keys.add(choice.group(1));
                keys.add(choice.group(2));
            }
        }
        for (Field field : Messages.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                field.setAccessible(true);
                keys.add((String) field.get(null));
            }
        }
        keys.add(SmsText.LOGIN_KEY);
        keys.add(SmsText.PHONE_KEY);
        return keys;
    }

    @Test
    void englishAndRomanianHaveEveryKey() throws Exception {
        Properties en = bundle("en");
        Properties ro = bundle("ro");
        for (String key : usedKeys()) {
            assertTrue(en.containsKey(key), "English is missing " + key);
            assertTrue(ro.containsKey(key), "Romanian is missing " + key);
        }
        assertEquals(new TreeSet<>(en.stringPropertyNames()), new TreeSet<>(ro.stringPropertyNames()), "the same keys");
    }

    @Test
    void pageTextsAreValidMessageFormats() throws Exception {
        for (String locale : new String[] {"en", "ro"}) {
            Properties p = bundle(locale);
            for (String key : p.stringPropertyNames()) {
                if (key.equals(SmsText.LOGIN_KEY) || key.equals(SmsText.PHONE_KEY)) {
                    continue;
                }
                String value = p.getProperty(key);
                try {
                    String formatted = new MessageFormat(value, Locale.forLanguageTag(locale)).format(new Object[] {"7341"});
                    assertTrue(!formatted.contains("{0}"), key);
                } catch (IllegalArgumentException e) {
                    fail(locale + " " + key + " is not a valid message format: " + value);
                }
                assertTrue(!value.contains("'"), locale + " " + key + ": an apostrophe would vanish in Keycloak's formatting");
            }
        }
    }

    @Test
    void theSmsTextsCarryTheCodeAndFitInOneTextMessage() throws Exception {
        for (String locale : new String[] {"en", "ro"}) {
            Properties p = bundle(locale);
            for (String key : new String[] {SmsText.LOGIN_KEY, SmsText.PHONE_KEY}) {
                String template = p.getProperty(key);
                assertTrue(template.contains("{code}"), locale + " " + key);
                String text = SmsText.render(template, "482913", "SMSTunnel Test", 5);
                assertTrue(SmsText.usable(text, "482913"), locale + " " + key + ": " + text);
                assertTrue(text.chars().allMatch(c -> c < 128), locale + " " + key
                        + " must stay in the basic alphabet, or the text becomes two messages: " + text);
                assertTrue(text.length() <= 160, locale + " " + key + " fits in one text message");
            }
        }
    }

    @Test
    void romanianPageTextsUseCommaBelowLetters() throws Exception {
        String ro = resource("/theme-resources/messages/messages_ro.properties");
        assertTrue(ro.contains("ț") && ro.contains("ș"), "Romanian page texts have their diacritics");
        assertTrue(!ro.contains("ţ") && !ro.contains("ş"), "ș and ț with a comma below, not a cedilla");
    }
}
