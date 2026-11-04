# Content-addressed object keys

Date: 2026-11-03

## Status

Accepted

## Context

The broker assigns the log offset after the proxy has already handled the produce request. The object key cannot contain that offset. A unique id generated for each attempt leaves an extra object behind when the producer retries the same batch.

## Decision

Include the topic and the hex SHA-256 of the original value in the object key. A retry of the same bytes overwrites that object.

## Consequences

Identical values on one topic share one object. The proxy does not need the assigned offset to build the key. Two different values do not overwrite each other.
