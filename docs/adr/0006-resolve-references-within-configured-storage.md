# Resolve references within configured storage

Date: 2026-11-04

## Status

Accepted

## Context

The envelope carries an object key supplied by a previous produce, or by a client that wrote to the broker without the proxy. That key is untrusted. If the filter used the key as an arbitrary locator, a record could point at another topic's object, another prefix, or a key outside the claim-check area of the bucket.

## Decision

Resolve every download against the configured `bucketName` and optional `keyPrefix` only. Ignore any bucket, endpoint, or URL that appears in a record value.

Reject a key that is empty, contains `..`, starts with `/`, is not valid UTF-8, or does not start with `{keyPrefix}{topic}/` for the topic being read. The SHA-256 hex in the key must match the digest field in the envelope.

## Consequences

A consumer of topic A cannot restore an object stored under topic B, even when both topics share a bucket. A crafted envelope cannot steer the proxy at another bucket or host. Operators can share one bucket across topics if each topic's keys stay under `{keyPrefix}{topic}/`.

## Amendment 2026-11-07

Bucket, region, role, and key prefix come only from a storage mapping ([ADR 0008](0008-team-owned-storage-mappings.md)). "Configured storage" now means the mappings registered for the proxy, not a single filter-wide bucket.

The record supplies only its storage ID. The ID must name a mapping that covers the topic being read. An `active` or a `readOnly` mapping qualifies. The object key is derived from the mapping, the topic, and the digest, so the proxy accepts no key from a record and the key rejection rules above no longer apply. Ignoring any bucket, endpoint, or URL in a record value still applies.
