/*
 * Copyright 2026 Tanya Marfel
 *
 * Licensed under the Apache Software License version 2.0, available at
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.tm.claimcheck.envelope;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.IntStream;

import static io.github.tm.claimcheck.envelope.EnvelopeFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

public class EnvelopeCodecTest {

    private static final byte[] ORDERS_ENVELOPE = {
            'K', 'R', 'C', '1',                              // 0-3    magic
            0x01,                                            // 4      version
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0A,  // 5-12   original size = 10, big-endian
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,  // 13-44  SHA-256 (the digest() values)
            0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10,
            0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
            0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
            0x06,                                            // 45     storage ID length = 6
            'o', 'r', 'd', 'e', 'r', 's'                     // 46-51  storage ID
    };

    private static EnvelopeCodec codec;

    @BeforeAll
    static void init() {
        codec = new EnvelopeCodec();
    }

    @Test
    void encodesDocumentedLayout() {
        var envelope = new ClaimCheckEnvelope(10, digest(), "orders");
        assertArrayEquals(ORDERS_ENVELOPE, codec.encode(envelope));
    }

    @Test
    void decodesDocumentedLayout() {
        var envelope = codec.decode(ORDERS_ENVELOPE).orElseThrow();
        assertEquals(new ClaimCheckEnvelope(10, digest(), "orders"), envelope);
    }

    @Test
    void decodeReturnsEmptyForNull() {
        assertTrue(codec.decode(null).isEmpty());
    }

    @Test
    void decodeReturnsEmptyForEmptyValue() {
        assertTrue(codec.decode(new byte[0]).isEmpty());
    }

    @ParameterizedTest(name = "treats \"{0}\" as an ordinary value")
    @ValueSource(strings = {"hello", "s3://bucket/key", "krc1", "KRC"})
    void decodeReturnsEmptyWhenMagicIsAbsent(String value) {
        assertTrue(codec.decode(value.getBytes(StandardCharsets.UTF_8)).isEmpty());
    }

    @ParameterizedTest(name = "rejects length byte {0}")
    @ValueSource(ints = {0, 5, 7, 254, 255})
    void decodeRejectsBadLengthByte(int length) {
        byte[] value = validEncoded("orders");
        value[ID_LENGTH_OFFSET] = (byte) length;
        assertThrows(InvalidEnvelopeException.class, () -> codec.decode(value));
    }

    @ParameterizedTest(name = "round trips a storage id of {0} characters")
    @ValueSource(ints = {1, 127, 128, 200, 253})
    void roundTripsStorageIdOfLength(int length) {
        var envelope = new ClaimCheckEnvelope(1, digest(), "a".repeat(length));
        assertEquals(envelope, codec.decode(codec.encode(envelope)).orElseThrow());
    }

    static IntStream truncatedLengths() {
        return IntStream.range(4, validEncoded("orders").length);
    }

    @ParameterizedTest(name = "rejects envelope cut to {0} bytes")
    @MethodSource("truncatedLengths")
    void decodeRejectsTruncation(int length) {
        byte[] cut = Arrays.copyOf(validEncoded("orders"), length);
        assertThrows(InvalidEnvelopeException.class, () -> codec.decode(cut));
    }

    @ParameterizedTest(name = "rejects version byte {0}")
    @ValueSource(ints = {0, 2, 0x80, 0xFF})
    void decodeRejectsUnknownVersion(int version) {
        byte[] value = ORDERS_ENVELOPE.clone();
        value[4] = (byte) version;
        assertThrows(InvalidEnvelopeException.class, () -> codec.decode(value));
    }

    @Test
    void decodeRejectsNegativeSize() {
        byte[] value = ORDERS_ENVELOPE.clone();
        value[5] = (byte) 0x80;
        assertThrows(InvalidEnvelopeException.class, () -> codec.decode(value));
    }

    @ParameterizedTest(name = "rejects {0} trailing byte(s)")
    @ValueSource(ints = {1, 5, 7})
    void decodeRejectsTrailingBytes(int addedLength) {
        byte[] value = Arrays.copyOf(ORDERS_ENVELOPE, ORDERS_ENVELOPE.length + addedLength);
        value[value.length - 1] = 'x';
        assertThrows(InvalidEnvelopeException.class, () -> codec.decode(value));
    }

    @Test
    void decodeRejectsMalformedId() {
        byte[] value = ORDERS_ENVELOPE.clone();
        value[46] = (byte) 0xFF;
        assertThrows(InvalidEnvelopeException.class, () -> codec.decode(value));
    }

    @Test
    void decodeRejectsBadChars() {
        byte[] value = ORDERS_ENVELOPE.clone();
        value[46] = 'A';
        assertThrows(InvalidEnvelopeException.class, () -> codec.decode(value));
    }

    @ParameterizedTest(name = "round trips original size {0}")
    @ValueSource(longs = {0L, 1L, 1_048_577L, Long.MAX_VALUE})
    void roundTripsOriginalSize(long size) {
        var envelope = new ClaimCheckEnvelope(size, digest(), "orders");
        assertEquals(envelope, codec.decode(codec.encode(envelope)).orElseThrow());
    }

    @ParameterizedTest(name = "rejects a storage id of {0} characters")
    @ValueSource(ints = {254, 300})
    void recordRejectsTooLongStorageId(int length) {
        assertThrows(InvalidEnvelopeException.class,
                () -> new ClaimCheckEnvelope(1, digest(), "a".repeat(length)));
    }

}
