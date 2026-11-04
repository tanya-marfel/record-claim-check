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
