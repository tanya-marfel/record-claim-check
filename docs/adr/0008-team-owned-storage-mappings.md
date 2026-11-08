# Team-owned storage mappings

Date: 2026-11-07

## Status

Accepted

## Context

Teams own topics on the platform. A team that expects large payloads on a topic provides a bucket for it. Topics whose messages stay small do not need the proxy and can be used without it. The proxy runs in front of Kafka on Kubernetes, possibly as a dedicated path so that claim-check traffic is kept apart from regular records.

The first design gave the whole filter one `bucketName`, one `region`, and one optional `roleArn`. That cannot serve many independent teams, each with its own bucket and its own access role.

## Decision

A team registers a `StorageMapping` for its topics. The resource lives in the team's namespace and carries:

- `topics`: topic names or glob patterns the mapping covers
- `bucketName` and `region`
- `roleArn`: the role the proxy assumes for this mapping (see [ADR 0009](0009-assume-one-role-per-storage-mapping.md))
- `externalId`: optional, used when assuming the role
- `keyPrefix`: optional, defaults to empty
- `thresholdBytes`: optional, defaults to the filter-wide threshold
- `state`: `active` or `readOnly`, defaults to `active`

The resource name is the storage ID that goes into the envelope.

At most one `active` mapping may cover a topic. New uploads use it. A `readOnly` mapping is used only to restore records written earlier. To move a topic to another bucket, add a new `active` mapping and mark the old one `readOnly`. Never delete a mapping while records that reference it are still within topic retention.

The filter reads mappings through a `MappingSource` interface. The first implementation reads a mounted file that contains the same fields. A Kubernetes watcher, or a small controller that writes that file, can be added behind the same interface. The filter holds an immutable in-memory snapshot and swaps it atomically on reload. Filter methods read only that snapshot and do no I/O to resolve a mapping. If the source cannot be read at startup, the proxy fails to start. If a reload fails, the proxy keeps the last good snapshot and reports the failure.

A namespace may register only topics it owns. The platform defines ownership, for example with a topic naming convention or an allowlist of topic patterns per namespace. It is enforced where mappings are admitted and again when the proxy builds its snapshot. A mapping for a topic the namespace does not own is ignored and reported. Without this rule, one team could register a mapping for another team's topic and receive its payloads in the wrong bucket.

If two `active` mappings cover the same topic, the proxy does not guess. Produce to that topic fails with the transient storage error from [ADR 0004](0004-fail-closed-on-storage-errors.md) until the conflict is removed.

A topic with no mapping is not offloaded. Produce passes it through unchanged and fetch returns values that do not start with `KRC1` unchanged. A fetched `KRC1` envelope whose mapping cannot be resolved fails closed and is never returned as the record value.

`maxValueBytes` stays a proxy-wide limit. A mapping cannot raise it.

## Consequences

Teams register storage for their own topics without a change to the proxy's own configuration. Bucket and role for every topic are explicit and can be audited. The proxy needs read access to the mapping source and permission to assume each mapping's role.

Deleting or editing a mapping can make existing records unreadable. `bucketName`, `region`, `roleArn`, and `keyPrefix` of a mapping that has been used must not change. Create a new mapping instead.

A mapping registered in error for a topic that already carries large values does not change records already written. A topic that has no mapping and receives a large value passes it to the broker, which applies its own size limit.

## Amendment 2026-11-08

`MappingSource` is a plugin interface, selected and configured in the filter configuration ([ADR 0010](0010-pluggable-mapping-source-and-object-store.md)). The file source takes its path from its own configuration. Nothing in the filter depends on Kubernetes: a source that reads Kubernetes resources is a separate implementation. The ownership rule, the one-active-mapping rule, and mapping validation stay in the filter and apply to every source.
