# Claim-check filter

This document is the target behavior. The current filter still stores `s3://` URLs and does not follow every rule below. Tests should be written against this document.

The filter runs inside a Kroxylicious proxy. Producers and consumers keep their existing clients and serializers. A client that reads the broker directly sees envelopes, not the original values.

## What is offloaded

On produce, a record value larger than `thresholdBytes` is uploaded to S3 and replaced with an envelope. The default threshold is 1 MiB (`1048576`). A value of exactly `thresholdBytes` stays in the record.

These stay in the Kafka record:

- The record key
- Headers
- The timestamp
- A null value, including a tombstone
- An empty value
- Idempotent and transactional batch fields: producer id, producer epoch, base sequence, and the compression type

The filter does not offload record keys.

## Envelope

An offloaded value is a big-endian buffer:

| Offset | Field | Size |
| --- | --- | --- |
| 0 | Magic `KRC1` | 4 |
| 4 | Version `1` | 1 |
| 5 | Original value size | 8 |
| 13 | SHA-256 of the original value | 32 |
| 45 | Object key length | 2 |
| 47 | Object key, UTF-8 | key length |

The bucket is filter configuration. It is not stored in the envelope.

A value that does not start with `KRC1` is a normal record. On fetch it is returned unchanged. A value that starts with `KRC1` but has a bad version, a truncated header, or a key that runs past the end fails the fetch.

## Object keys

The object key contains the topic and the hex SHA-256 of the original value. A produce retry of the same bytes writes the same object.

## Failures

A failed upload fails the produce. The proxy does not send the original value or an envelope.

A failed download fails the fetch. The proxy does not return the envelope as the record value.

## Retention

The filter does not delete objects. S3 lifecycle expiration must be at least as long as the Kafka topic retention. Topic compaction and retention delete the envelope from the log and leave the object in place.

## Configuration

`bucketName` and `region` are required. `thresholdBytes` defaults to `1048576`. `roleArn` and `roleSessionName` are optional. When `roleArn` is set, the filter assumes that role for S3 calls. Otherwise it uses the default AWS credential chain.

## Decisions

- [Offload record values only](adr/0001-offload-record-values-only.md)
- [Binary claim-check envelope](adr/0002-binary-claim-check-envelope.md)
- [Content-addressed object keys](adr/0003-content-addressed-object-keys.md)
- [Fail closed on storage errors](adr/0004-fail-closed-on-storage-errors.md)
- [Retain objects with topic retention](adr/0005-retain-objects-with-topic-retention.md)
