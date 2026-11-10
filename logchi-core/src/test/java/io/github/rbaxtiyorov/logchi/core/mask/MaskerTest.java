package io.github.rbaxtiyorov.logchi.core.mask;

import io.github.rbaxtiyorov.logchi.core.LogchiSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaskerTest {

    private final Masker masker = Masker.from(new LogchiSettings.Mask());

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "login password=hunter2 ok              | login password=*** ok",
            "accessToken=abc.def                    | accessToken=***",
            "x-auth-token: abc                      | x-auth-token: ***",
            "Authorization: Bearer abc.def.ghi      | Authorization: ***",
            "https://a.io/x?token=abc&page=2        | https://a.io/x?token=***&page=2",
            "{\"password\":\"a b c\",\"name\":\"bob\"}  | {\"password\":\"***\",\"name\":\"bob\"}",
            "User(password=abc, name=bob)           | User(password=***, name=bob)",
    })
    void masksSensitiveKeyValues(String input, String expected) {
        assertEquals(expected, masker.mask(input));
    }

    @Test
    void masksJsonEmbeddedInAString() {
        assertEquals("body={\\\"secret\\\":\\\"***\\\"}", masker.mask("body={\\\"secret\\\":\\\"abc\\\"}"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "paid with 4111111111111111 today    | paid with *** today",
            "paid with 4111 1111 1111 1111 today | paid with *** today",
            "paid with 4111-1111-1111-1111 today | paid with *** today",
    })
    void masksCardNumbers(String input, String expected) {
        assertEquals(expected, masker.mask(input));
    }

    @ParameterizedTest
    @CsvSource({
            "order 1762700000000 created",     // epoch milliseconds
            "ref 4111111111111112 not a card", // fails the Luhn check
            "user=bob amount=99.5",
    })
    void leavesOrdinaryTextAlone(String input) {
        assertEquals(input, masker.mask(input));
    }

    @Test
    void customKeysAddToDefaults() {
        LogchiSettings.Mask settings = new LogchiSettings.Mask();
        settings.setKeys(List.of("ssn"));
        Masker custom = Masker.from(settings);
        assertEquals("ssn=*** password=***", custom.mask("ssn=123 password=x"));
    }

    @Test
    void customPatternsApply() {
        LogchiSettings.Mask settings = new LogchiSettings.Mask();
        settings.setPatterns(List.of("AB\\d+"));
        assertEquals("id *** end", Masker.from(settings).mask("id AB123 end"));
    }

    @Test
    void keyCheckIsCaseInsensitiveAndPartial() {
        assertTrue(masker.isSensitiveKey("userPassword"));
        assertFalse(masker.isSensitiveKey("orderId"));
    }

    @Test
    void disabledMaskerChangesNothing() {
        LogchiSettings.Mask settings = new LogchiSettings.Mask();
        settings.setEnabled(false);
        assertEquals("password=x", Masker.from(settings).mask("password=x"));
    }

    @Test
    void stayQuickOnHugeTokens() {
        String huge = "x".repeat(2_000_000);
        long start = System.nanoTime();
        assertEquals(huge, masker.mask(huge + ""));
        masker.mask("data=" + huge);
        assertTrue(System.nanoTime() - start < 2_000_000_000L, "masking should be linear on long tokens");
    }
}
