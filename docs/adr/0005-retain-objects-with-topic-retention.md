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
