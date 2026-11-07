# Claim-check filter

This document is the target behavior. Tests should be written against it. The current sources are a prototype and do not follow every rule below.

The filter runs inside a Kroxylicious proxy. Producers and consumers keep their existing clients and serializers. A client that reads the broker directly sees envelopes, not the original values.

Teams own topics. A team that expects large payloads on a topic registers a storage mapping for it: a bucket and the role the proxy assumes to reach it. Topics whose messages stay small do not need the proxy.

## Current prototype

The classes under `src/main/java` already intercept produce and fetch, upload values over `thresholdBytes`, and download values that look like `s3://` or `https://` URLs. That prototype is not the accepted design.

| Area | Target | Current code |
| --- | --- | --- |
| Storage selection | Per-topic storage mapping with its own bucket and role | One global bucket, region, and role |
| Envelope | Binary `KRC1` buffer with a storage ID | UTF-8 `s3://bucket/key` |
| Object key | `{keyPrefix}{topic}/{sha256hex}`, derived by the proxy | `{topic}/{date}/{uuid}` |
| Produce retry | Overwrites the same object | Creates a new object |
| Fetch detection | Magic bytes `KRC1` | Prefix `s3://` or `https://` |
| Failed upload | Short-circuit produce error | Exception escapes the filter |
| Failed download | Partition error, no envelope | Catch and forward the envelope |
| Headers and producer fields | Copied with the record | Dropped on rewrite |
| Batches | Same batch membership and compression | Flattened; produce forced to `NONE` |
| S3 client | Per mapping, created by the factory and closed by it | One per connection, never closed |
| I/O | Non-blocking `CompletionStage` | Synchronous `S3Client` on the filter thread |
| Tests | Unit and in-VM coverage | None |

A value that happens to start with `https://` is treated as a reference. The fetch path also swallows errors and returns the reference as the record value, which contradicts fail-closed.

## Storage mappings

A storage mapping tells the proxy where the large values of a topic go and which role to use. A team registers it as a `StorageMapping` resource in its own namespace. The resource name is the storage ID that goes into the envelope.

```yaml
apiVersion: claimcheck.example.com/v1alpha1   # placeholder group and version
kind: StorageMapping
metadata:
  name: orders-large-payloads
  namespace: team-orders
spec:
  topics: ["orders.large"]
  bucketName: example-claim-checks-orders
  region: eu-west-1
  roleArn: arn:aws:iam::111122223333:role/claim-check/orders
  externalId: example-external-id-change-me
  keyPrefix: claim-checks/
  thresholdBytes: 1048576
  state: active
```

Rules:

- At most one `active` mapping may cover a topic. New uploads use it.
- A `readOnly` mapping is used only to restore records written earlier. To move a topic to another bucket, add a new `active` mapping and mark the old one `readOnly`.
- `bucketName`, `region`, `roleArn`, and `keyPrefix` of a mapping that has been used must not change. Create a new mapping instead.
- Do not delete a mapping, its bucket, or its role while records that reference it are within topic retention.
- A namespace may register only topics it owns. A mapping for a topic outside the namespace's allowed patterns is ignored and reported.
- If two `active` mappings cover one topic, produce to that topic fails until the conflict is removed.
- A topic with no mapping is not offloaded. Produce passes it through unchanged.

The filter reads mappings through a `MappingSource`. The first implementation reads a mounted YAML file that contains a list of these resources. A Kubernetes watcher, or a controller that writes the file, can replace it later without changing the filter. The proxy keeps an immutable snapshot and swaps it atomically on reload. Filter methods read only the snapshot. If the source cannot be read at startup, the proxy fails to start. If a reload fails, the proxy keeps the last good snapshot and reports the failure.

## What is offloaded

On produce, a record value larger than the mapping's `thresholdBytes` is uploaded to the mapping's bucket and replaced with an envelope. The default threshold is 1 MiB (`1048576`). A value of exactly `thresholdBytes` stays in the record. A value larger than `maxValueBytes` fails the produce.

These stay in the Kafka record:

- The record key
- Headers
- The timestamp
- A null value, including a tombstone
- An empty value
- Control batches and transaction markers
- Idempotent and transactional batch fields: producer id, producer epoch, base sequence, and the compression type

The filter does not offload record keys. It does not rewrite APIs other than produce requests and fetch responses. It does not touch topics that have no mapping.

## Envelope

An offloaded value is a big-endian buffer:

| Offset | Field | Size |
| --- | --- | --- |
| 0 | Magic `KRC1` | 4 |
| 4 | Version `1` | 1 |
| 5 | Original value size | 8 |
| 13 | SHA-256 of the original value | 32 |
| 45 | Storage ID length, unsigned | 1 |
| 46 | Storage ID, UTF-8 | ID length |

The storage ID is 1 to 253 characters: lowercase letters, digits, `-`, and `.`. It names a storage mapping. The bucket, region, role, and key prefix are not stored in the envelope, and neither is the object key. The proxy derives them from the mapping.

A value that does not start with `KRC1` is a normal record. On fetch it is returned unchanged. A value that starts with `KRC1` but has a bad version, a truncated header, a negative size, a storage ID length of 0 or one that does not match the remaining bytes, or an invalid storage ID fails the fetch. So does a digest or size that does not match the downloaded object.

## Object keys

The object key is `{keyPrefix}{topic}/{sha256hex}`. `{sha256hex}` is the lowercase hex SHA-256 of the original value. `keyPrefix` comes from the mapping and defaults to empty.

A produce retry of the same bytes overwrites the same object. Identical values on one topic share one object.

The proxy derives the key. A record never supplies one. On fetch the storage ID must name a mapping that covers the topic being read, whether `active` or `readOnly`. Any bucket, endpoint, or URL in a record value is ignored.

## Failures

A failed upload fails the produce. The proxy does not send the original value or an envelope to the broker.

- When `acks` is not 0, return a short-circuit `ProduceResponse` with a per-partition error.
- When `acks` is 0, close the connection. That produce has no response.

A failed download fails the fetch. The proxy does not return the envelope as the record value.

- Set the error on the affected partition and clear that partition's records.
- Other partitions in the same fetch may succeed.

The error codes differ between produce and fetch. They were checked against the Java clients in kafka-clients 4.3.1; see [ADR 0004](adr/0004-fail-closed-on-storage-errors.md).

On produce:

- `REQUEST_TIMED_OUT` when the store is unavailable, times out, throttles, or denies access, or when the topic's mapping is in conflict. It is retriable and does not make clients refresh metadata.
- `MESSAGE_TOO_LARGE` when the value is larger than `maxValueBytes`.

On fetch:

- `UNKNOWN_SERVER_ERROR` when the store is unavailable, times out, throttles, or denies access, or when no mapping with the envelope's storage ID is known. The consumer logs a warning and fetches again, and the application sees no error. Report these through proxy metrics and logs.
- `CORRUPT_MESSAGE` when the envelope is malformed, the digest or size does not match, the object is missing, or the storage ID names a mapping that does not cover the topic. The Java consumer raises an error to the application.
- Never use `INVALID_RECORD`, `REQUEST_TIMED_OUT`, or `KAFKA_STORAGE_ERROR` on fetch.

Error messages do not include payloads, object keys, or credentials. Do not complete the filter stage exceptionally unless the message cannot be rewritten. An exceptional stage closes the connection.

## Retention

The filter does not delete objects. The team that owns a mapping's bucket owns its lifecycle rule. Expiration must be at least as long as the longest retention of the mapping's topics, applied to the mapping's `keyPrefix`. Topic compaction and retention delete the envelope from the log and leave the object in place.

Offloaded values have the same confidentiality requirements as Kafka values. Use bucket default encryption. The filter does not encrypt or decrypt payloads itself.

## Configuration

Proxy-wide settings are part of the filter configuration. Bucket, region, and role are not: they belong to mappings.

| Field | Required | Default | Purpose |
| --- | --- | --- | --- |
| `mappingFile` | yes | | Path of the mounted file that lists storage mappings |
| `topicOwnership` | yes | | List of `namespace` and `topics` patterns that say which topics each namespace may register |
| `thresholdBytes` | no | `1048576` | Threshold for mappings that do not set their own |
| `maxValueBytes` | no | `16777216` | Reject values larger than this (16 MiB). A mapping cannot raise it |
| `endpointOverride` | no | | HTTP(S) endpoint for MinIO or LocalStack. Test and development only |
| `pathStyleAccess` | no | `false` | Path-style addressing for that endpoint. Test and development only |

Mapping fields:

| Field | Required | Default | Purpose |
| --- | --- | --- | --- |
| `topics` | yes | | Topic names or glob patterns the mapping covers |
| `bucketName` | yes | | Bucket for uploads and downloads |
| `region` | yes | | AWS region of the bucket |
| `roleArn` | yes | | Role the proxy assumes for this mapping |
| `externalId` | no | | External ID sent when assuming the role. The platform should require it |
| `keyPrefix` | no | empty | Prefix for every object key |
| `thresholdBytes` | no | filter-wide value | Offload values strictly larger than this |
| `state` | no | `active` | `active` or `readOnly` |

Invalid configuration fails proxy startup: a missing or unreadable `mappingFile`, `thresholdBytes <= 0`, or `maxValueBytes < thresholdBytes`. A mapping that is invalid is rejected and reported, and it does not stop other mappings from loading. A mapping is invalid when `bucketName`, `region`, or `roleArn` is blank or malformed, or when its `topics` are empty. `roleArn` must look like `arn:aws:iam::{12-digit account}:role/{path and name}`.

The proxy assumes each mapping's role using its own identity from the default AWS credential chain or a workload identity. It does not use its own identity for team buckets. Production S3 connections require TLS with certificate verification. `endpointOverride` may use HTTP only in test and development configuration.

## Runtime

Implement `ProduceRequestFilter` and `FetchResponseFilter`, not the generic request and response interfaces. Rewrite records with `RecordStream` from `kroxylicious-kafka-message-tools` so batches, headers, control records, and producer fields stay intact.

Resolve storage from the mapping snapshot. Create stores and credentials providers on first use, keep them in size-bounded caches, and close them when evicted and from `FilterFactory.close`. Filter methods must not block. Uploads and downloads return `CompletionStage`.

Log topic, partition, value size, and a short digest prefix. Do not log payloads, full object keys, URLs, credentials, or authorization headers.

## Operator notes

The proxy's own role needs `sts:AssumeRole` and nothing on team buckets. Limit it to roles under a dedicated path:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "sts:AssumeRole",
      "Resource": "arn:aws:iam::*:role/claim-check/*"
    }
  ]
}
```

Each team creates a role for its mapping. Its permission policy grants `s3:PutObject` and `s3:GetObject` on the bucket and prefix. It does not grant `s3:DeleteObject`. Add `s3:ListBucket` on the bucket, limited to the prefix, so a missing object returns 404 and not 403:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject"],
      "Resource": "arn:aws:s3:::example-claim-checks-orders/claim-checks/*"
    },
    {
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::example-claim-checks-orders",
      "Condition": { "StringLike": { "s3:prefix": "claim-checks/*" } }
    }
  ]
}
```

Its trust policy trusts the proxy and requires the mapping's external ID:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": { "AWS": "arn:aws:iam::111122223333:role/claim-check-proxy" },
      "Action": "sts:AssumeRole",
      "Condition": { "StringEquals": { "sts:ExternalId": "example-external-id-change-me" } }
    }
  ]
}
```

All account ids, role names, bucket names, and external IDs above are placeholders.

Set the bucket lifecycle expiration to at least the longest retention of the mapping's topics. A shorter expiration makes fetch fail for records that are still in the log.

Clients that bypass the proxy see envelopes. Point every producer and consumer that should observe original values at the proxy bootstrap.

## Decisions

- [Offload record values only](adr/0001-offload-record-values-only.md)
- [Binary claim-check envelope](adr/0002-binary-claim-check-envelope.md)
- [Content-addressed object keys](adr/0003-content-addressed-object-keys.md)
- [Fail closed on storage errors](adr/0004-fail-closed-on-storage-errors.md)
- [Retain objects with topic retention](adr/0005-retain-objects-with-topic-retention.md)
- [Resolve references within configured storage](adr/0006-resolve-references-within-configured-storage.md)
- [Shared non-blocking object store](adr/0007-shared-nonblocking-object-store.md)
- [Team-owned storage mappings](adr/0008-team-owned-storage-mappings.md)
- [Assume one role per storage mapping](adr/0009-assume-one-role-per-storage-mapping.md)
