# Shared non-blocking object store

Date: 2026-11-04

## Status

Accepted

## Context

Kroxylicious creates one filter instance per client connection and invokes filter methods on a Netty event loop. A synchronous S3 call on that thread stalls every request on the connection. Creating an S3 client per connection wastes sockets and never closes them when the factory does not own the client.

Tests should not need AWS credentials or a network.

## Decision

Give the filter an `ObjectStore` with asynchronous `put` and `get` that return `CompletionStage`. Create one store in `FilterFactory.initialize`, pass it into every filter instance, and close it from `FilterFactory.close`.

Filter methods must not block. Return an incomplete `CompletionStage` while an upload or download is in flight. The in-memory implementation is for unit and in-VM tests. The S3 implementation uses the default credential chain, the optional STS role, and TLS with certificate verification. An HTTP endpoint override is test and development configuration only.

## Consequences

Connections share one client and one credential provider. Proxy shutdown releases those resources. Tests can inject a store that succeeds or fails without S3. A slow object store pauses the affected connection until the stage completes or times out, instead of blocking the event loop.

## Amendment 2026-11-07

Storage is chosen per topic. Add a `StorageResolver` that returns a target from the current mapping snapshot ([ADR 0008](0008-team-owned-storage-mappings.md)): bucket, region, role ARN, external ID, key prefix, and threshold. Filter methods only read that in-memory snapshot. `ObjectStore` operations take the resolved target.

The factory still creates the shared components once and closes them. Create the store for a target on first use, keep stores in a size-bounded cache, and close evicted ones. `FilterFactory.close` closes every store and credentials provider. When a reload removes a mapping, close its store after in-flight operations finish.

The default credential chain is used only to assume mapping roles ([ADR 0009](0009-assume-one-role-per-storage-mapping.md)). The in-memory store used in tests keys objects by bucket and object key, so tests can cover more than one target.

## Amendment 2026-11-08

`ObjectStore` is a plugin interface, selected and configured in the filter configuration ([ADR 0010](0010-pluggable-mapping-source-and-object-store.md)). The S3 implementation and the in-memory test implementation are the two that this project ships. Store failures are reported as a fixed set of outcomes that the filter maps to error codes.
