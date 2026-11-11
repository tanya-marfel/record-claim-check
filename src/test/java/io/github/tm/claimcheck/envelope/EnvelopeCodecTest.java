package io.github.tm.claimcheck.envelope;


import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.github.tm.claimcheck.envelope.EnvelopeFixtures.ID_LENGTH_OFFSET;
import static io.github.tm.claimcheck.envelope.EnvelopeFixtures.digest;
import static io.github.tm.claimcheck.envelope.EnvelopeFixtures.validEncoded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class EnvelopeCodecTest {

    private static EnvelopeCodec codec;

    @BeforeAll
    static void init(){
        codec = new EnvelopeCodec();
    }

    @ParameterizedTest(name = "rejects storage id \"{0}\"")
    @ValueSource(strings = {"Orders", "a_b", "a/b", "a b", "ó", ""})
    void recordRejectsInvalidStorageId(String id) {
        assertThrows(InvalidEnvelopeException.class,
                () -> new ClaimCheckEnvelope(1, digest(), id));
    }

    @ParameterizedTest(name = "rejects length byte {0}")
    @ValueSource(ints = {0, 254, 255})
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
}
