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
