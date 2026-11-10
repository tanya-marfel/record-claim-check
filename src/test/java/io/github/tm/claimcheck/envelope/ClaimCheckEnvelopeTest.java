package io.github.tm.claimcheck.envelope;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.github.tm.claimcheck.envelope.EnvelopeFixtures.digest;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ClaimCheckEnvelopeTest {

    static Stream<Arguments> tooLongIds() {
        return Stream.of(
                Arguments.of("254 characters", "a".repeat(254)),
                Arguments.of("300 characters", "a".repeat(300)));
    }

    @ParameterizedTest(name = "rejects a storage id of {0} characters")
    @MethodSource("tooLongIds")
    void recordRejectsTooLongStorageId(String id) {
        assertThrows(InvalidEnvelopeException.class,
                () -> new ClaimCheckEnvelope(1, digest(), id));
    }
}
