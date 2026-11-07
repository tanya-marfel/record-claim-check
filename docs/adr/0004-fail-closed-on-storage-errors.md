# Fail closed on storage errors

Date: 2026-11-03

## Status

Accepted

## Context

If the proxy sends a large value after a failed upload, the broker stores the payload the filter was meant to remove. If the proxy returns an envelope after a failed download, the consumer treats the reference as the original value.

## Decision

A failed upload fails the produce. The produce request carries neither the original value nor an envelope. A failed download fails the fetch. The fetch response does not contain the envelope in place of the value.

## Consequences

Producers observe a failed produce when S3 is unavailable. Consumers observe a failed fetch instead of a silent substitution. The proxy does not acknowledge offload before the upload succeeds.

## Amendment 2026-11-04

Signal the failure with a Kafka error. Do not complete the filter stage exceptionally unless the response cannot be rewritten; an exceptional stage closes the connection and is reserved for that case.

On produce, do not forward the request. When `acks` is not 0, return a short-circuit `ProduceResponse` with a per-partition error. When `acks` is 0, close the connection; that produce has no response.

On fetch, do not return the envelope or a partial restore. Set the error on the affected partition and clear that partition's records. Other partitions in the same fetch may succeed.

Use `KAFKA_STORAGE_ERROR` when the object store is unavailable, times out, denies access, or the object is missing. Use `INVALID_RECORD` when the envelope is truncated, has an unknown version, fails the digest or size check, or names an object outside the configured storage bounds. Error messages must not include payloads, object keys, or credentials.

## Amendment 2026-11-07

This amendment replaces the paragraph above that names `KAFKA_STORAGE_ERROR` and `INVALID_RECORD`. The 2026-11-04 rules for not forwarding the request, `acks=0`, and per-partition failure on fetch still apply.

Checked against kafka-clients 4.3.1:

- `KAFKA_STORAGE_ERROR` is retriable but is an `InvalidMetadataException`. Clients refresh metadata each time they see it, so a storage outage would make every client repeat metadata requests through the proxy.
- The Java consumer accepts only a fixed set of partition error codes on fetch. Any other code, including `INVALID_RECORD` and `REQUEST_TIMED_OUT`, makes `poll()` throw `IllegalStateException`.

Produce and fetch therefore use different codes.

On produce:

- Use `REQUEST_TIMED_OUT` when the store is unavailable, times out, throttles, or denies access, or when the topic's mapping is in conflict. It is retriable and does not refresh metadata, so the producer retries until its delivery timeout.
- Use `MESSAGE_TOO_LARGE` when the value is larger than `maxValueBytes`. It is not retriable.

On fetch:

- Use `UNKNOWN_SERVER_ERROR` when the store is unavailable, times out, throttles, or denies access, or when no mapping with the envelope's storage ID is known. The consumer logs a warning and fetches again. It does not refresh metadata and the application sees no exception, so report these through proxy metrics and logs. If the role has no `s3:ListBucket`, a missing object also appears as an access error and is reported this way ([ADR 0009](0009-assume-one-role-per-storage-mapping.md)).
- Use `CORRUPT_MESSAGE` when the envelope is malformed, the digest or size does not match, the object is missing (404), or the storage ID names a mapping that does not cover the topic. The Java consumer throws a `KafkaException` from `poll()`, so the application learns that the record cannot be restored.
- Do not use `INVALID_RECORD`, `REQUEST_TIMED_OUT`, or `KAFKA_STORAGE_ERROR` on fetch.

This was verified for the Java producer and consumer in kafka-clients 4.3.1 by inspecting the exception hierarchy and the consumer's fetch error handling. Other clients were not verified. Integration tests with the real Java producer and consumer must pin this behavior.
