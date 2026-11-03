# Offload record values only

Date: 2026-11-03

## Status

Accepted

## Context

Kafka record keys, headers, and timestamps are used for partitioning, compaction, joins, and ordering. A tombstone is a null value. Moving any of those fields to S3 would change consumer behavior.

## Decision

Offload a record value only when its size is greater than `thresholdBytes`. Leave the key, headers, timestamp, null values, tombstones, and empty values in the Kafka record. Preserve the batch compression type and the idempotent or transactional producer id, epoch, and base sequence.

## Consequences

Consumers that use the proxy see the original record metadata. The broker stores an envelope in place of a large value. Values at or below the threshold, including empty values, are unchanged.
