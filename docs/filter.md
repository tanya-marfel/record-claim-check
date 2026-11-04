# Claim-check filter

This document is the target behavior. Tests should be written against it. The current sources are a prototype and do not follow every rule below.

The filter runs inside a Kroxylicious proxy. Producers and consumers keep their existing clients and serializers. A client that reads the broker directly sees envelopes, not the original values.

## Current prototype

The classes under `src/main/java` already intercept produce and fetch, upload values over `thresholdBytes`, and download values that look like `s3://` or `https://` URLs. That prototype is not the accepted design.

| Area | Target | Current code |
| --- | --- | --- |
| Envelope | Binary `KRC1` buffer | UTF-8 `s3://bucket/key` |
| Object key | `{keyPrefix}{topic}/{sha256hex}` | `{topic}/{date}/{uuid}` |
| Produce retry | Overwrites the same object | Creates a new object |
| Fetch detection | Magic bytes `KRC1` | Prefix `s3://` or `https://` |
| Failed upload | Short-circuit produce error | Exception escapes the filter |
| Failed download | Partition error, no envelope | Catch and forward the envelope |
| Headers and producer fields | Copied with the record | Dropped on rewrite |
| Batches | Same batch membership and compression | Flattened; produce forced to `NONE` |
| S3 client | One per virtual cluster, closed by the factory | One per connection, never closed |
| I/O | Non-blocking `CompletionStage` | Synchronous `S3Client` on the filter thread |
| Tests | Unit and in-VM coverage | None |

A value that happens to start with `https://` is treated as a reference. Download still uses the configured bucket, so this is not an open URL fetch, but it is a false restore. The fetch path also swallows errors and returns the reference as the record value, which contradicts fail-closed.

## What is offloaded

On produce, a record value larger than `thresholdBytes` is uploaded to object storage and replaced with an envelope. The default threshold is 1 MiB (`1048576`). A value of exactly `thresholdBytes` stays in the record. A value larger than `maxValueBytes` fails the produce.

These stay in the Kafka record:

- The record key
- Headers
- The timestamp
- A null value, including a tombstone
- An empty value
- Control batches and transaction markers
- Idempotent and transactional batch fields: producer id, producer epoch, base sequence, and the compression type

The filter does not offload record keys. It does not rewrite APIs other than produce requests and fetch responses.

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

A value that does not start with `KRC1` is a normal record. On fetch it is returned unchanged. A value that starts with `KRC1` but has a bad version, a truncated header, a key that runs past the end, or a digest or size that does not match the downloaded object fails the fetch.

## Object keys

The object key is `{keyPrefix}{topic}/{sha256hex}`. `{sha256hex}` is the lowercase hex SHA-256 of the original value. `keyPrefix` is optional and defaults to empty.

A produce retry of the same bytes overwrites the same object. Identical values on one topic share one object.

On fetch the object key is untrusted. The filter downloads only from the configured `bucketName`. It rejects a key that is empty, contains `..`, starts with `/`, is not valid UTF-8, does not start with `{keyPrefix}{topic}/` for the topic being read, or whose SHA-256 hex does not match the envelope digest.

## Failures

A failed upload fails the produce. The proxy does not send the original value or an envelope to the broker.

- When `acks` is not 0, return a short-circuit `ProduceResponse` with a per-partition error.
- When `acks` is 0, close the connection. That produce has no response.

A failed download fails the fetch. The proxy does not return the envelope as the record value.

- Set the error on the affected partition and clear that partition's records.
- Other partitions in the same fetch may succeed.

Use `KAFKA_STORAGE_ERROR` when the store is unavailable, times out, denies access, or the object is missing. Use `INVALID_RECORD` when the envelope is malformed, fails the digest or size check, or names an object outside the configured storage bounds.

Do not complete the filter stage exceptionally unless the message cannot be rewritten. An exceptional stage closes the connection.

## Retention

The filter does not delete objects. S3 lifecycle expiration must be at least as long as the Kafka topic retention. When `keyPrefix` is set, apply the rule to that prefix. Topic compaction and retention delete the envelope from the log and leave the object in place.

Offloaded values have the same confidentiality requirements as Kafka values. Use bucket default encryption. The filter does not encrypt or decrypt payloads itself.

## Configuration

`bucketName` and `region` are required and must be non-blank. Invalid configuration fails proxy startup. `thresholdBytes <= 0`, `maxValueBytes < thresholdBytes`, or a blank `roleSessionName` when `roleArn` is set are invalid.

| Field | Required | Default | Purpose |
| --- | --- | --- | --- |
| `bucketName` | yes | | Bucket for all uploads and downloads |
| `region` | yes | | AWS region for the S3 and STS clients |
| `thresholdBytes` | no | `1048576` | Offload values strictly larger than this |
| `maxValueBytes` | no | `16777216` | Reject values larger than this (16 MiB) |
| `keyPrefix` | no | empty | Prefix prepended to every object key |
| `roleArn` | no | | STS role to assume for S3 calls |
| `roleSessionName` | no | | Session name when `roleArn` is set |
| `endpointOverride` | no | | HTTP(S) endpoint for MinIO or LocalStack. Test and development only |
| `pathStyleAccess` | no | `false` | Path-style addressing for that endpoint |

When `roleArn` is set, the filter assumes that role. Otherwise it uses the default AWS credential chain. Production S3 connections require TLS with certificate verification. `endpointOverride` may use HTTP only in test and development configuration.

## Runtime

Implement `ProduceRequestFilter` and `FetchResponseFilter`, not the generic request and response interfaces. Rewrite records with `RecordStream` from `kroxylicious-kafka-message-tools` so batches, headers, control records, and producer fields stay intact.

Create one `ObjectStore` in `FilterFactory.initialize`, pass it to every filter instance, and close it from `FilterFactory.close`. Filter methods must not block. Uploads and downloads return `CompletionStage`.

Log topic, partition, value size, and a short digest prefix. Do not log payloads, full object keys, URLs, credentials, or authorization headers.

## Operator notes

The proxy task needs `s3:PutObject` and `s3:GetObject` on `{bucket}/{keyPrefix}*`. It does not need `s3:DeleteObject`. Prefer a workload identity or the optional STS role over long-lived static keys.

Set the bucket lifecycle expiration to at least the longest topic retention that uses the filter. A shorter expiration makes fetch fail for records that are still in the log.

Clients that bypass the proxy see envelopes. Point every producer and consumer that should observe original values at the proxy bootstrap.

## Decisions

- [Offload record values only](adr/0001-offload-record-values-only.md)
- [Binary claim-check envelope](adr/0002-binary-claim-check-envelope.md)
- [Content-addressed object keys](adr/0003-content-addressed-object-keys.md)
- [Fail closed on storage errors](adr/0004-fail-closed-on-storage-errors.md)
- [Retain objects with topic retention](adr/0005-retain-objects-with-topic-retention.md)
- [Resolve references within configured storage](adr/0006-resolve-references-within-configured-storage.md)
- [Shared non-blocking object store](adr/0007-shared-nonblocking-object-store.md)
