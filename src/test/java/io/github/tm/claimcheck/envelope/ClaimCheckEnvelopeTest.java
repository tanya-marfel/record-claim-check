/*
 * Copyright 2026 Tanya Marfel
 *
 * Licensed under the Apache Software License version 2.0, available at
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.tm.claimcheck.envelope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.tm.claimcheck.envelope.EnvelopeFixtures.digest;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimCheckEnvelopeTest {

    @ParameterizedTest(name = "rejects a storage id of {0} characters")
    @ValueSource(ints = { 254, 300 })
    void recordRejectsTooLongStorageId(int length) {
        assertThrows(InvalidEnvelopeException.class,
                () -> new ClaimCheckEnvelope(1, digest(), "a".repeat(length)));
    }

    @ParameterizedTest(name = "rejects storage id \"{0}\"")
    @ValueSource(strings = { "Orders", "a_b", "a/b", "a b", "ó", "" })
    void recordRejectsInvalidStorageId(String id) {
        assertThrows(InvalidEnvelopeException.class,
                () -> new ClaimCheckEnvelope(1, digest(), id));
    }

    @ParameterizedTest(name = "accepts a storage id of {0} characters")
    @ValueSource(ints = { 1, 253 })
    void recordAcceptsStorageIdAtBounds(int length) {
        assertEquals(length, new ClaimCheckEnvelope(1, digest(), "a".repeat(length)).storageId().length());
    }

    @Test
    void recordRejectsNegativeSize() {
        assertThrows(InvalidEnvelopeException.class,
                () -> new ClaimCheckEnvelope(-1, digest(), "orders"));
    }

    @Test
    void recordAcceptsZeroSize() {
        assertEquals(0, new ClaimCheckEnvelope(0, digest(), "orders").originalSize());
    }

    @Test
    void recordRejectsNullDigest() {
        assertThrows(NullPointerException.class,
                () -> new ClaimCheckEnvelope(1, null, "orders"));
    }

    @ParameterizedTest(name = "rejects a digest of {0} bytes")
    @ValueSource(ints = { 0, 31, 33 })
    void recordRejectsWrongDigestLength(int length) {
        assertThrows(InvalidEnvelopeException.class,
                () -> new ClaimCheckEnvelope(1, new byte[length], "orders"));
    }

    @Test
    void recordRejectsNullStorageId() {
        assertThrows(NullPointerException.class,
                () -> new ClaimCheckEnvelope(1, digest(), null));
    }

    @Test
    void recordCopiesDigestOnConstruction() {
        byte[] input = digest();
        var envelope = new ClaimCheckEnvelope(1, input, "orders");

        input[0] = 99;

        assertArrayEquals(digest(), envelope.sha256());
    }

    @Test
    void recordCopiesDigestOnAccess() {
        var envelope = new ClaimCheckEnvelope(1, digest(), "orders");

        envelope.sha256()[0] = 99;

        assertArrayEquals(digest(), envelope.sha256());
    }

    @Test
    void equalsAndHashCodeUseDigestContent() {
        var a = new ClaimCheckEnvelope(1, digest(), "orders");
        var b = new ClaimCheckEnvelope(1, digest(), "orders");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differsWhenAnyComponentDiffers() {
        var base = new ClaimCheckEnvelope(1, digest(), "orders");
        byte[] otherDigest = digest();
        otherDigest[31] = 99;

        assertNotEquals(base, new ClaimCheckEnvelope(2, digest(), "orders"));
        assertNotEquals(base, new ClaimCheckEnvelope(1, otherDigest, "orders"));
        assertNotEquals(base, new ClaimCheckEnvelope(1, digest(), "payments"));
    }

    @Test
    void toStringDoesNotExposeDigestOrStorageId() {
        String text = new ClaimCheckEnvelope(1, digest(), "orders").toString();

        assertTrue(text.contains("originalSize=1"));
        assertFalse(text.contains("orders"));
        assertFalse(text.contains("sha256"));
    }
}
