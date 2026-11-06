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
