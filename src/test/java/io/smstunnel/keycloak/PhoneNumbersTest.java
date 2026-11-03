package io.smstunnel.keycloak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PhoneNumbersTest {

    @Test
    void keepsInternationalNumbers() {
        assertEquals("+40721234567", PhoneNumbers.normalize("+40 721 234 567", null));
        assertEquals("+14155550123", PhoneNumbers.normalize("+1 (415) 555-0123", "+40"));
        assertEquals("+4915123456789", PhoneNumbers.normalize("0049 151 2345 6789", null));
    }

    @Test
    void completesLocalNumbersLikeSmsTunnelDoes() {
        assertEquals("+40721234567", PhoneNumbers.normalize("0721.234.567", "+40"));
        assertEquals("+40721234567", PhoneNumbers.normalize("0721 234 567", "40"));
        assertEquals("+14155550123", PhoneNumbers.normalize("415 555 0123", "+1"));
        assertEquals("+447911123456", PhoneNumbers.normalize("07911 123456", "0044"));
    }

    @Test
    void refusesLocalNumbersWithoutACountryCode() {
        assertNull(PhoneNumbers.normalize("0721 234 567", null));
        assertNull(PhoneNumbers.normalize("0721 234 567", "Romania"));
    }

    @Test
    void refusesWhatIsNotANumber() {
        assertNull(PhoneNumbers.normalize(null, "+40"));
        assertNull(PhoneNumbers.normalize("   ", "+40"));
        assertNull(PhoneNumbers.normalize("+40 72", "+40"));
        assertNull(PhoneNumbers.normalize("+40 721 abc 567", "+40"));
        assertNull(PhoneNumbers.normalize("+40721234567, +40722222222", "+40"));
        assertNull(PhoneNumbers.normalize("+1234567890123456", null));
    }

    @Test
    void readsCountryCodes() {
        assertEquals("+40", PhoneNumbers.countryCode("40"));
        assertEquals("+49", PhoneNumbers.countryCode("0049"));
        assertEquals("+1268", PhoneNumbers.countryCode("+1 268"));
        assertNull(PhoneNumbers.countryCode("+0"));
        assertNull(PhoneNumbers.countryCode("RO"));
    }

    @Test
    void showsOnlyTheLastDigits() {
        assertEquals("4567", PhoneNumbers.ending("+40721234567"));
        assertEquals("23", PhoneNumbers.ending("123"));
        assertEquals("", PhoneNumbers.ending(null));
    }
}
