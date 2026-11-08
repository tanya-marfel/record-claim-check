# Pluggable mapping source and object store

Date: 2026-11-08

## Status

Accepted

## Context

The filter is meant to be usable beyond one platform. ADR 0008 puts mappings behind a `MappingSource` and ADR 0007 puts storage behind an `ObjectStore`, but neither says how someone else supplies an implementation. A deployment that keeps mappings in a file, a database, or Kubernetes resources should not need a change to the filter, and the filter must not carry a Kubernetes client or a second cloud SDK for those who do not use them.

Kroxylicious has a plugin mechanism for this. A filter's config record names a plugin implementation with `@PluginImplName` and holds that implementation's own configuration with `@PluginImplConfig`. The factory gets the instance from `FilterFactoryContext.pluginInstance`. An implementation declares its config type with `@Plugin(configType = ...)` ([plugin API](https://kroxylicious.io/documentation/0.25.0/javadoc/io/kroxylicious/proxy/plugin/package-summary.html)).

## Decision

Make two plugin interfaces, in a package of their own, `io.github.tm.claimcheck.spi`:

- `MappingSource` supplies the list of storage mappings and notifies the filter when it changes. The filter builds and swaps its snapshot ([ADR 0008](0008-team-owned-storage-mappings.md)).
- `ObjectStore` uploads and downloads objects for a resolved target ([ADR 0007](0007-shared-nonblocking-object-store.md)).

The filter configuration names each one and carries its configuration:

```yaml
filterDefinitions:
  - name: claim-check
    type: ClaimCheck
    config:
      mappingSource: FileMappingSource
      mappingSourceConfig:
        path: /etc/claim-check/mappings.yaml
      objectStore: S3ObjectStore
      objectStoreConfig: {}      # S3 store settings go here, for example a test-only endpointOverride
      topicOwnership: [...]
      thresholdBytes: 1048576
      maxValueBytes: 16777216
```

Both names are required. The project ships `FileMappingSource`, `S3ObjectStore`, and an in-memory `ObjectStore` for tests. Settings that belong to one implementation move into its own config: `mappingFile` becomes the file source's `path`, and `endpointOverride` and `pathStyleAccess` become S3 store settings. `thresholdBytes`, `maxValueBytes`, and `topicOwnership` stay filter-wide.

The following are not pluggable and no implementation can change them:

- the envelope layout and its verification of size and digest ([ADR 0002](0002-binary-claim-check-envelope.md))
- object key derivation ([ADR 0003](0003-content-addressed-object-keys.md))
- the ownership rule, the one-active-mapping rule, and the mapping validation of ADR 0008
- the error codes and fail-closed behavior ([ADR 0004](0004-fail-closed-on-storage-errors.md))
- the size and concurrency bounds
- that the filter never deletes objects ([ADR 0005](0005-retain-objects-with-topic-retention.md))

An implementation must meet this contract:

- Operations return `CompletionStage` and never block the calling thread.
- A store reports failure with a small fixed set of outcomes: not found, access denied, throttled or unavailable, timeout, and other. The filter maps them to the codes of ADR 0004. A store cannot choose an error code.
- A store never returns bytes that are not the stored object. The filter still checks size and digest.
- An implementation does not log payloads, credentials, signed URLs, or its own configuration values.
- Resources are created when the implementation is initialized and released when it is closed. The factory closes it from `FilterFactory.close`.

The mapping schema stays as ADR 0008 and ADR 0009 define it. `bucketName`, `region`, and `roleArn` are fields of an S3 mapping. Supporting a store that needs other fields, such as another cloud, requires a change to the mapping schema that this decision does not design.

Implementations that need heavy dependencies live in separate modules and artifacts that depend only on the `spi` package. A source that reads Kubernetes resources is one such module. The core artifact does not include a Kubernetes client.

## Consequences

Others can add a mapping source or an object store without changing the filter, and operators who need neither a CRD nor a Kubernetes client do not get one. The platform's own CRD source becomes one more implementation instead of a special case.

The `spi` interfaces become a public API. After a first release, changing them breaks every external implementation, so keep them small and mark them unstable until they have been used by more than the built-in implementations.

Configuration becomes more verbose, and the plugin names must resolve at proxy startup. A name that does not resolve fails startup with the list of known names.

An implementation's config is read from the proxy configuration file, so it may contain secrets. The filter does not print it, but the proxy's own configuration handling is outside this project.

How a plugin JAR and its dependencies reach the proxy is still open. Loading third-party JARs from a `classpath-plugins` directory was Alpha in the 0.21.0 developer guide, and this must be checked for the Kroxylicious version in use.
