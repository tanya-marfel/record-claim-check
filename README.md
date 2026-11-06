# record-claim-check

A claim check filter for Apache Kafka® built with Kroxylicious. The goal is to store large record values in Amazon S3 and pass references through Kafka, then restore the original values before delivering records to consumers.

## Status

The project is in early development. A produce and fetch prototype is present. It still stores `s3://` URLs, uses date-and-UUID object keys, rewrites records with a flattening `MemoryRecordsBuilder`, and has no tests. That behavior is not what will ship.

Target behavior is defined in [docs/filter.md](docs/filter.md). Accepted decisions are in [docs/adr](docs/adr). Until the prototype is replaced, `./gradlew check` only compiles the current sources.

## Intended behavior

The filter will run in a Kroxylicious proxy between Kafka clients and brokers:

1. On produce, store a record value larger than `thresholdBytes` in S3 and replace it with a binary envelope.
2. Write the record containing that envelope to Kafka. Keys, headers, timestamps, tombstones, and producer fields stay on the record.
3. On fetch, retrieve the object from S3, check it against the envelope, and replace the envelope with the original value before returning the record to the consumer.

Producers and consumers will connect through the proxy. Offloading and restoration are intended to require no changes to application code or serializers. A client that reads the broker directly sees envelopes.

A failed upload fails the produce. A failed download fails the fetch. The filter does not delete objects; S3 lifecycle expiration must cover topic retention.

The envelope layout, object key format, storage bounds, failure codes, and configuration are defined in [docs/filter.md](docs/filter.md).

## Development

Use JDK 21 and the Gradle wrapper included in the repository. A separate Gradle installation is not required. The first build requires network access to download Gradle and dependencies.

Run the following commands from the repository root. On Windows, use `gradlew.bat` in place of `./gradlew`.

Build the library and run checks:

```sh
./gradlew build
```

Run tests:

```sh
./gradlew test
```

Run all configured verification tasks:

```sh
./gradlew check
```

There are currently no test sources, so a successful check does not yet verify filter behavior. Kafka, S3, and AWS credentials are not required for the current build setup.

The build uses JUnit Jupiter and includes Kroxylicious filter and integration test support. Add production sources under `src/main/java`, tests under `src/test/java`, and test resources under `src/test/resources`.

Dependency versions are maintained in `gradle/libs.versions.toml`; dependencies are declared in `build.gradle.kts`.

## Contributing

Keep changes focused and include tests for new behavior and bug fixes. Run `./gradlew check` before submitting code changes, and use Conventional Commits for commit messages, for example `feat(filter): add record value offloading`.

See [AGENTS.md](AGENTS.md) for repository conventions, testing requirements, and security guidelines. Never commit credentials or record payloads containing sensitive data.

Pull requests use the [PR template](.github/pull_request_template.md). Describe the change, report validation results, and note any compatibility or operational impact. GitHub inserts the template into the web form once it is on the default branch; the author fills in the details. When asking Cursor to create a PR, the instructions in `AGENTS.md` direct it to fill the template from the branch diff and observed check results.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
