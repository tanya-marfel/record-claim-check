# Assume one role per storage mapping

Date: 2026-11-07

## Status

Accepted

## Context

Access to each team's bucket is managed with IAM roles. The proxy's own identity must not hold blanket access to every bucket. The proxy's role is trusted by many team roles, so a mapping that names another team's role would make the proxy act with that role on the wrong team's behalf. This is a confused-deputy risk.

## Decision

Every mapping carries a required `roleArn`. The proxy assumes that role for the mapping's uploads and downloads. It does not fall back to its own identity for a team bucket. A mapping without a valid `roleArn` is rejected.

The proxy's own identity, from the default AWS credential chain or a workload identity, is used only to call `sts:AssumeRole`.

The role grants `s3:PutObject` and `s3:GetObject` on `arn:aws:s3:::{bucket}/{keyPrefix}*` and nothing more. It does not grant `s3:DeleteObject`. Granting `s3:ListBucket` on the bucket, limited to the key prefix with an `s3:prefix` condition, is recommended: without it S3 returns 403 for a missing object instead of 404, and the filter cannot tell a missing object from a permission failure.

The role's trust policy trusts the proxy's identity. When the mapping sets `externalId`, the trust policy requires the same `sts:ExternalId`. The platform should require an external ID for every mapping. The ownership rule in [ADR 0008](0008-team-owned-storage-mappings.md) remains the primary control and the external ID is a second one.

Validate a mapping before use:

- `roleArn` has the form `arn:aws:iam::{12-digit account}:role/{path and name}`.
- `bucketName` follows S3 bucket naming rules.
- `region` is a known region.

Send the account id from `roleArn` as the expected bucket owner on every S3 call, so a call fails if the bucket belongs to another account. This assumes the bucket is owned by the account that owns the role.

Set the role session name to `claim-check-{topic}`, limited to the characters STS allows and 64 characters, so CloudTrail shows which topic triggered each call. Do not put payload data, object keys, or credentials in it.

Keep one assume-role credentials provider per distinct role and external ID. Create it on first use, keep providers in a size-bounded cache, close evicted providers, and close all of them from `FilterFactory.close`. Credentials are refreshed by the SDK provider and are never logged.

Test and development setups may override the S3 endpoint. Production uses TLS with certificate verification.

## Consequences

A compromised mapping can reach only what its role allows. The proxy's own identity can only assume roles and holds no bucket permissions. It is recommended to limit its `sts:AssumeRole` permission to roles under a dedicated path, for example `role/claim-check/*`, so it cannot assume unrelated roles.

An STS or role failure affects only the topics of that mapping. Each mapping role needs a trust policy and a permission policy, which the owning team maintains.
