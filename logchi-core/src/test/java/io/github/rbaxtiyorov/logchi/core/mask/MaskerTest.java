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

    /** A regex that recurses per character overflows the stack on values this long. */
    @Test
    void survivesVeryLongSecretsOnASmallStack() throws Exception {
        String plain = "{\"access_token\":\"" + "a".repeat(1_000_000) + "\",\"user\":\"bob\"}";
        String escapes = "{\"access_token\":\"" + "\\\"x".repeat(200_000) + "\",\"user\":\"bob\"}";
        String single = "token='" + "b".repeat(500_000) + "' next";
        String bare = "token=" + "c".repeat(500_000) + " next";
        Throwable[] failure = new Throwable[1];
        String[] out = new String[4];
        Thread small = new Thread(null, () -> {
            try {
                out[0] = masker.mask(plain);
                out[1] = masker.mask(escapes);
                out[2] = masker.mask(single);
                out[3] = masker.mask(bare);
            } catch (Throwable t) {
                failure[0] = t;
            }
        }, "small-stack", 256 * 1024);
        small.start();
        small.join();
        assertEquals(null, failure[0]);
        assertEquals("{\"access_token\":\"***\",\"user\":\"bob\"}", out[0]);
        assertEquals("{\"access_token\":\"***\",\"user\":\"bob\"}", out[1]);
        assertEquals("token='***' next", out[2]);
        assertEquals("token=*** next", out[3]);
    }

    @Test
    void masksAValueWhoseClosingQuoteWasCutOff() {
        assertEquals("{\"refreshToken\":\"***\"", masker.mask("{\"refreshToken\":\"abcdefghij"));
        assertEquals("{\"refreshToken\":\"***\"\nnext line", masker.mask("{\"refreshToken\":\"abcdef…(120 more chars)\nnext line"));
        assertEquals("{\\\"password\\\":\\\"***\\\"", masker.mask("{\\\"password\\\":\\\"abc"));
    }

    @Test
    void neverReachesAcrossALineBreak() {
        assertEquals("token:\n\t│ body", masker.mask("token:\n\t│ body"));
        assertEquals("password=\nnext=1", masker.mask("password=\nnext=1"));
    }

    @Test
    void leavesExceptionNamesAndNullAlone() {
        assertEquals("InvalidBearerTokenException: Jwt expired", masker.mask("InvalidBearerTokenException: Jwt expired"));
        assertEquals("SecretStoreError: unavailable", masker.mask("SecretStoreError: unavailable"));
        assertEquals("{\"token\":null,\"id\":1}", masker.mask("{\"token\":null,\"id\":1}"));
    }

    @Test
    void masksCookies() {
        assertEquals("Cookie: ***", masker.mask("Cookie: SESSION=abc123"));
        assertEquals("Set-Cookie: ***; Path=/", masker.mask("Set-Cookie: SESSION=abc123; Path=/"));
    }

    /** Looking for a key inside a word by regex backtracks over the rest of the word: quadratic time. */
    @Test
    void longWordsMadeOfKeysStayFast() {
        String word = "token".repeat(100_000);
        long start = System.nanoTime();
        assertEquals("note: " + word + " end", masker.mask("note: " + word + " end"));
        assertEquals("{\"note\":\"" + word + "\"}", masker.mask("{\"note\":\"" + word + "\"}"));
        assertEquals(word.replace("token", "token-") + "=***", masker.mask(word.replace("token", "token-") + "=abc"));
        assertEquals("password".repeat(50_000) + ": ***", masker.mask("password".repeat(50_000) + ": abc"));
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(millis < 2_000, "took " + millis + "ms");
    }

    @Test
    void aKeyInsideALongerWordStillCounts() {
        assertEquals("clientSecret: ***, user: ann", masker.mask("clientSecret: s3cr3t, user: ann"));
        assertEquals("x-auth-token=***", masker.mask("x-auth-token=abc"));
        assertEquals("name=bob token=***", masker.mask("name=bob token=abc"));
        assertEquals("a=1 b=2 c=3", masker.mask("a=1 b=2 c=3"));
    }

    /** Pretty printers other than Jackson's put a value on the line after its key. */
    @Test
    void masksAQuotedValueOnTheNextLine() {
        assertEquals("{\"password\":\n  \"***\"}", masker.mask("{\"password\":\n  \"hunter2\"}"));
        assertEquals("{\"password\":\r\n  \"***\"}", masker.mask("{\"password\":\r\n  \"hunter2\"}"));
        assertEquals("{\"password\":\\n  \"***\"}", masker.mask("{\"password\":\\n  \"hunter2\"}"));
        assertEquals("token:\n\t│ body", masker.mask("token:\n\t│ body"), "only a quote on the next line counts");
    }

    @Test
    void masksOtherWaysOfWritingAKeyAndItsValue() {
        assertEquals("password => ***", masker.mask("password => hunter2"));
        assertEquals("user[password]=***", masker.mask("user[password]=hunter2"));
    }

    @Test
    void classNamesAtTheStartOfAMessageAreNotKeys() {
        assertEquals("CookieTokenResolver: no fvv_token cookie", masker.mask("CookieTokenResolver: no fvv_token cookie"));
        assertEquals("TokenService: refreshed", masker.mask("TokenService: refreshed"));
        assertEquals("ApiKey: ***", masker.mask("ApiKey: abc123"), "a capitalised key is still a key");
        assertEquals("AccessToken=***", masker.mask("AccessToken=abc123"));
    }

    /** A count or a flag named after a secret is not one. A value under the secret's own name always is. */
    @Test
    void numbersAndFlagsUnderDescriptiveKeysAreKept() {
        assertEquals("tokenCount=5 cookieConsent=true passwordRetries: 3",
                masker.mask("tokenCount=5 cookieConsent=true passwordRetries: 3"));
        assertEquals("password=*** refreshToken=*** pin_token: ***",
                masker.mask("password=12345 refreshToken=987654 pin_token: 1"));
        assertEquals("tokenHint=***", masker.mask("tokenHint=abc"), "anything else under such a key is masked");
        assertEquals("{\"secretCount\":2,\"clientSecret\":\"***\"}", masker.mask("{\"secretCount\":2,\"clientSecret\":\"x\"}"));
    }
}
