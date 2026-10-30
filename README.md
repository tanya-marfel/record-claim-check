# record-claim-check

A claim check filter for Apache Kafka® built with Kroxylicious. The goal is to store large record values in Amazon S3 and pass references through Kafka, then restore the original values before delivering records to consumers.

## Status

The project is in early development. The repository contains the Gradle build and dependencies for Kroxylicious, the AWS SDK for Java, and JUnit. The filter implementation and tests have not been added yet.

Configuration, deployment instructions, and compatibility guarantees will be documented as the implementation takes shape.

## Intended behavior

The filter will run in a Kroxylicious proxy between Kafka clients and brokers:

1. On produce, store a large record value in S3 and replace it with a reference to the stored object.
2. Write the record containing that reference to Kafka.
3. On fetch, retrieve the object from S3 and replace the reference with the original value before returning the record to the consumer.

Producers and consumers will connect through the proxy. Offloading and restoration are intended to require no changes to application code or serializers.

The reference format, offload threshold, failure handling, and object retention policy are still to be defined.

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
