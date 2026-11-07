# Binary claim-check envelope

Date: 2026-11-03

## Status

Accepted

## Context

A text reference such as `s3://bucket/key` cannot be distinguished from a normal payload that is itself a URL. Record values are arbitrary bytes, so a text prefix is not a reliable marker.

## Decision

Store a big-endian envelope that starts with the magic bytes `KRC1`, followed by version `1`, the original size, the SHA-256 of the original value, and the object key. Keep the bucket in filter configuration. On fetch, pass through values that do not start with `KRC1`. Fail the fetch when a buffer starts with `KRC1` but is truncated or uses an unknown version.

## Consequences

A normal payload is not downloaded from S3. A damaged envelope is not delivered as the record value. Changing the layout later requires a new version byte.

## Amendment 2026-11-04

After a successful download, compare the object length and SHA-256 with the envelope. A mismatch fails the fetch. The size and digest exist so a truncated, substituted, or corrupted object cannot be returned as the original value.

## Amendment 2026-11-07

Replace the object key in the layout with a storage ID. No record has been written in this layout, so version `1` is changed in place and no version `2` is introduced.

The layout is big-endian: magic `KRC1` (4 bytes), version `1` (1), original value size (8), SHA-256 of the original value (32), storage ID length (1, unsigned), and the storage ID (UTF-8, 1 to 253 bytes). The header before the storage ID is 46 bytes.

The storage ID is the name of a storage mapping ([ADR 0008](0008-team-owned-storage-mappings.md)): lowercase letters, digits, `-`, and `.`. The object key is not stored. It is derived from the mapping, the topic, and the digest ([ADR 0003](0003-content-addressed-object-keys.md)).

A value that starts with `KRC1` fails the fetch when its version is unknown, its header is truncated, its size is negative, its storage ID length is 0 or does not match the remaining bytes, or its storage ID is not valid.
