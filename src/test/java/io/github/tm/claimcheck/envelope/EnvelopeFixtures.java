/*
 * Copyright 2026 Tanya Marfel
 *
 * Licensed under the Apache Software License version 2.0, available at
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.tm.claimcheck.envelope;

/**
 * Shared test data for the envelope tests.
 */
final class EnvelopeFixtures {

    /** Index of the storage ID length byte: magic 4 + version 1 + size 8 + digest 32. */
    static final int ID_LENGTH_OFFSET = 4 + 1 + 8 + 32;

    private static final EnvelopeCodec CODEC = new EnvelopeCodec();

    private EnvelopeFixtures() {
    }

    /**
     * A fixed 32-byte digest of distinct values (1 to 32). It returns a new array on every call so a
     * test that changes it cannot affect another test.
     */
    static byte[] digest() {
        byte[] digest = new byte[32];
        for (int i = 0; i < digest.length; i++) {
            digest[i] = (byte) (i + 1);
        }
        return digest;
    }

    /** The encoded bytes of a valid envelope for the given storage ID. */
    static byte[] validEncoded(String storageId) {
        return CODEC.encode(new ClaimCheckEnvelope(10, digest(), storageId));
    }
}
