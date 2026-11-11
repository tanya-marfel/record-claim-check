/*
 * Copyright 2026 Tanya Marfel
 *
 * Licensed under the Apache Software License version 2.0, available at
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.tm.claimcheck.envelope;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

public class EnvelopeCodec {

    private static final byte[] MAGIC = {'K', 'R', 'C', '1'};
    private static final byte VERSION = 1;
    private static final int HEADER_SIZE = 4 + 1 + 8 + 32 + 1;
    protected static final int DIGEST_SIZE = 32;
    protected static final int MAX_STORAGE_ID_LENGTH = 253;

    public byte[] encode(ClaimCheckEnvelope envelope) {
        byte[] storageId = envelope.storageId().getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(HEADER_SIZE + storageId.length)
                .put(MAGIC).put(VERSION)
                .putLong(envelope.originalSize())
                .put(envelope.sha256())
                .put((byte) storageId.length)
                .put(storageId)
                .array();
    }

    public Optional<ClaimCheckEnvelope> decode(byte[] value) {
        if (value == null || !startsWithMagic(value)) {
            return Optional.empty();
        }
        if (value.length < HEADER_SIZE) {
            throw new InvalidEnvelopeException("truncated envelope");
        }
        var buf = ByteBuffer.wrap(value, MAGIC.length, value.length - MAGIC.length);
        if (buf.get() != VERSION) {
            throw new InvalidEnvelopeException("unsupported envelope version");
        }
        long size = buf.getLong();
        if (size < 0) {
            throw new InvalidEnvelopeException("invalid original size");
        }

        byte[] digest = new byte[DIGEST_SIZE];
        buf.get(digest);

        int storageIdLength = readStorageIdLength(buf);

        var storageId = readStorageId(storageIdLength, buf);

        return Optional.of(new ClaimCheckEnvelope(size, digest, storageId));
    }

    private String readStorageId(int keyLength, ByteBuffer buf) {
        var storageIdBytes = new byte[keyLength];                  // safe: keyLength == bytes left
        buf.get(storageIdBytes);

        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(storageIdBytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new InvalidEnvelopeException("storage id is not valid UTF-8", e);
        }
    }

    private int readStorageIdLength(ByteBuffer buf) {
        int keyLength = buf.get() & 0xFF;      // unsigned
        if (keyLength == 0 || keyLength > MAX_STORAGE_ID_LENGTH || keyLength != buf.remaining()) {
            throw new InvalidEnvelopeException("invalid storage id length");
        }
        return keyLength;
    }

    private static boolean startsWithMagic(byte[] value) {
        return value.length >= MAGIC.length
                && Arrays.equals(value, 0, MAGIC.length, MAGIC, 0, MAGIC.length);
    }

}
