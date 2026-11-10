/*
 * Copyright 2026 Tanya Marfel
 *
 * Licensed under the Apache Software License version 2.0, available at
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.tm.claimcheck.envelope;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

import static io.github.tm.claimcheck.envelope.EnvelopeCodec.DIGEST_SIZE;
import static io.github.tm.claimcheck.envelope.EnvelopeCodec.MAX_STORAGE_ID_LENGTH;

public record ClaimCheckEnvelope(long originalSize, byte[] sha256, String storageId) {
    private static final Pattern STORAGE_ID = Pattern.compile("[a-z0-9.-]{1," + MAX_STORAGE_ID_LENGTH + "}");

    public ClaimCheckEnvelope {
        if (originalSize < 0) {
            throw new InvalidEnvelopeException("originalSize must not be negative");
        }

        validateSha256(sha256);

        validateStorageId(storageId);

        sha256 = sha256.clone();
    }

    private static void validateSha256(byte[] sha256) {
        Objects.requireNonNull(sha256, "sha256");

        if (sha256.length != DIGEST_SIZE) {
            throw new InvalidEnvelopeException("sha256 must be 32 bytes");
        }
    }

    private static void validateStorageId(String storageId) {
        Objects.requireNonNull(storageId, "storageId");

        if (!STORAGE_ID.matcher(storageId).matches()) {
            throw new InvalidEnvelopeException("invalid storageId");
        }
    }

    @Override
    public byte[] sha256() {
        return sha256.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ClaimCheckEnvelope e
                && originalSize == e.originalSize
                && Arrays.equals(sha256, e.sha256)
                && storageId.equals(e.storageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(originalSize, Arrays.hashCode(sha256), storageId);
    }

    @Override
    public String toString() {
        return "ClaimCheckEnvelope[originalSize=" + originalSize + "]";
    }
}
