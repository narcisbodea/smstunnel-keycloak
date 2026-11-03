package io.smstunnel.keycloak;

import java.util.regex.Pattern;

/** Phone numbers as SMSTunnel reads them (the same steps as its normalizePhoneNumber). */
final class PhoneNumbers {

    private static final Pattern FORMATTING = Pattern.compile("[\\s.\\-()]");
    private static final Pattern E164 = Pattern.compile("^\\+[0-9]{7,15}$");
    private static final Pattern COUNTRY_CODE = Pattern.compile("^\\+[1-9][0-9]{0,3}$");

    private PhoneNumbers() {
    }

    /**
     * The number in international format ({@code +40721234567}), or null when it can't be one. A number written with
     * {@code +} or {@code 00} keeps its country; a local one ({@code 0721 234 567}) gets {@code defaultCountryCode},
     * without its leading 0. Without a default country code, a local number is refused.
     */
    static String normalize(String raw, String defaultCountryCode) {
        if (raw == null) {
            return null;
        }
        String cleaned = FORMATTING.matcher(raw).replaceAll("");
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.startsWith("00")) {
            cleaned = "+" + cleaned.substring(2);
        } else if (!cleaned.startsWith("+")) {
            String country = countryCode(defaultCountryCode);
            if (country == null) {
                return null;
            }
            cleaned = country + (cleaned.startsWith("0") ? cleaned.substring(1) : cleaned);
        }
        return E164.matcher(cleaned).matches() ? cleaned : null;
    }

    /** {@code "+40"} from {@code "+40"}, {@code "40"} or {@code "0040"}; null for anything else. */
    static String countryCode(String value) {
        if (value == null) {
            return null;
        }
        String code = FORMATTING.matcher(value).replaceAll("");
        if (code.startsWith("00")) {
            code = "+" + code.substring(2);
        } else if (!code.startsWith("+")) {
            code = "+" + code;
        }
        return COUNTRY_CODE.matcher(code).matches() ? code : null;
    }

    /** The last digits of a number, to show which phone got the code without showing the number. */
    static String ending(String phone) {
        if (phone == null) {
            return "";
        }
        String digits = phone.replaceAll("[^0-9]", "");
        int keep = digits.length() >= 8 ? 4 : Math.min(2, digits.length());
        return digits.substring(digits.length() - keep);
    }
}
