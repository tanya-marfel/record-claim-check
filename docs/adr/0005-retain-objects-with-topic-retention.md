# Retain objects with topic retention

Date: 2026-11-03

## Status

Accepted

## Context

Kafka retention and compaction delete the log record that contains the envelope. They do not delete the S3 object. Deleting the object when the log drops the envelope can break a consumer that still has that record, including a replay inside the retention window.

## Decision

The filter does not delete objects. Operators set S3 lifecycle expiration to at least the topic retention time.

## Consequences

Objects can outlive the Kafka record. Replays and lagging consumers can still restore the value until the lifecycle rule expires the object. Expiration shorter than topic retention makes fetches fail for records that are still in the log.

## Amendment 2026-11-04

Apply the lifecycle rule to the configured `keyPrefix` when one is set, so other objects in the same bucket are not expired. Bucket default encryption and versioning are operator concerns; the filter does not delete, overwrite-delete, or encrypt objects itself. Content-addressed keys already overwrite an identical value in place.

## Amendment 2026-11-07

The team that owns a mapping's bucket owns its lifecycle rule. The platform documents the minimum: expiration at least as long as the longest retention of the mapping's topics, applied to the mapping's `keyPrefix`.

A `readOnly` mapping and its bucket must stay until the last record that references it has left the topic. Deleting a mapping, its bucket, or its role while such records exist makes them unreadable ([ADR 0008](0008-team-owned-storage-mappings.md)).
