# Content-addressed object keys

Date: 2026-11-03

## Status

Accepted

## Context

The broker assigns the log offset after the proxy has already handled the produce request. The object key cannot contain that offset. A unique id generated for each attempt leaves an extra object behind when the producer retries the same batch.

## Decision

Include the topic and the hex SHA-256 of the original value in the object key. A retry of the same bytes overwrites that object.

## Consequences

Identical values on one topic share one object. The proxy does not need the assigned offset to build the key. Two different values do not overwrite each other.

## Amendment 2026-11-04

The object key is `{keyPrefix}{topic}/{sha256hex}`. `keyPrefix` is optional filter configuration and defaults to empty. `{sha256hex}` is the lowercase hex SHA-256 of the original value, 64 characters. A date or UUID must not appear in the key: both create a new object on retry.

## Amendment 2026-11-07

`keyPrefix` belongs to the topic's storage mapping ([ADR 0008](0008-team-owned-storage-mappings.md)), not to the whole filter. The proxy derives the key as `{keyPrefix}{topic}/{sha256hex}` from the mapping, the topic, and the digest. A key is never read from a record.

The `keyPrefix` of a mapping that has been used must not change. Objects stored under the old prefix could no longer be found.
