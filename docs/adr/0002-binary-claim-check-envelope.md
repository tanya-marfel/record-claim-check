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
