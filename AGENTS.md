# Agent guidelines

These instructions apply to the entire repository. Follow any additional instructions in a more specific `AGENTS.md` when working in its directory.

## Project context

`record-claim-check` is a claim check filter for Apache Kafka using Kroxylicious. It offloads large record values to S3 during produce and restores them during fetch, without requiring changes to producers or consumers.

- Use Java 21 and the checked-in Gradle wrapper (`./gradlew`; `gradlew.bat` on Windows).
- Keep dependency declarations in `build.gradle.kts` and dependency coordinates and versions in `gradle/libs.versions.toml`. Use the existing Kroxylicious and AWS SDK BOMs to align dependencies.
- Use JUnit Jupiter and the existing Kroxylicious test-support dependencies for tests.
- Follow the existing code style and package structure as implementation is added. Prefer small, focused changes and avoid unrelated refactoring.
- Preserve unrelated local changes. Do not commit generated output, caches, IDE metadata, or credentials.

## Conventional commits

When creating commits, use the Conventional Commits format:

```text
<type>[optional scope][!]: <description>
```

- Use `feat` for new functionality, `fix` for bug fixes, `docs` for documentation, `test` for test changes, `refactor` for behavior-preserving restructuring, `perf` for performance improvements, `build` for build/dependency changes, `ci` for automation, and `chore` for other maintenance.
- Use an optional scope that identifies the affected area, such as `s3`, `filter`, or `config`.
- Write a concise, imperative description without a trailing period, for example `feat(s3): add record value offloading` or `fix(filter): preserve tombstone records`.
- Mark breaking changes with `!` before the colon or a `BREAKING CHANGE:` footer. Explain the impact and required migration in the commit body or footer.
- Keep each commit focused on one logical change. Include its implementation, relevant tests, and documentation together.
- Use the same format for pull request titles when they will become squash commit messages.
- After completing a requested change and passing the required checks, create a Conventional Commit containing only that task's changes, unless the user explicitly asks to leave them uncommitted. Do not wait for a separate request to commit.
- Stage specific files or hunks and inspect the staged diff before committing. Preserve unrelated changes, including changes already staged by the user.
- If required checks fail or cannot run, report the blocker and leave the changes uncommitted rather than bypassing checks. Documentation-only changes require the checks described below, not a full build.
- Report the commit hash and validation results in the handoff. Push only when explicitly requested.

## Pull requests

- When asked to create or update a pull request, read `.github/pull_request_template.md` and use its headings for the description. Fill each section with actual details and remove the instructional comments.
- Inspect the complete branch diff against the intended PR base and the commits included in the PR. Describe the final change, not just the most recent commit or the conversation history. Do not describe uncommitted changes as part of the PR.
- Use a Conventional Commit title that describes the final scope. Keep the summary concise and explain the problem and resulting behavior before implementation details.
- Populate Validation with the commands actually run and their observed results for the proposed changes. Distinguish passing tests from skipped tasks or `NO-SOURCE`, and disclose checks that were not run and why. Do not invent coverage or reuse stale validation results.
- Describe relevant compatibility and operational changes, including configuration, reference formats, dependencies, permissions, and retention. Include migration instructions for breaking changes; use `No impact` when the section does not apply.
- Link issues only when their relationship to the change is established. Use closing keywords only when the PR resolves the issue.
- When creating a PR through a CLI or API, supply the completed title and body explicitly. For GitHub CLI, write the body to a temporary Markdown file and use `gh pr create --title ... --body-file ...`; `--fill` alone does not satisfy this workflow.
- After creating or updating the PR, inspect the saved title, body, base branch, and head branch to confirm they match the intended change. Preserve human-authored content when updating an existing description.

## Tests and verification

- Add automated tests for every new feature or behavior change. Test observable behavior rather than private implementation details.
- Add a regression test for each bug fix. When practical, demonstrate that the test fails before the fix and passes afterward.
- Cover the successful path, meaningful boundary cases, invalid input, and relevant failure behavior. Do not satisfy this requirement with assertions that merely duplicate the implementation.
- Put unit tests in `src/test/java` and fixtures in `src/test/resources`, following the production package structure. Keep tests deterministic and independent of execution order.
- Use isolated fakes or mocks for unit tests. Add integration tests when correctness depends on Kafka, Kroxylicious, or S3 interactions; use disposable local services or containers and document their prerequisites.
- Tests must not require production credentials, contact production resources, or depend on a developer's personal AWS account. Clean up resources created by integration tests.
- For claim check behavior, cover offload/restore round trips, threshold boundaries, null values (Kafka tombstones), empty values, preservation of record metadata, malformed references, missing S3 objects, and storage failures as relevant to the change.
- Test that failed uploads cannot produce dangling references and that failed restores cannot silently return incorrect payloads. Cover retries and duplicate operations when introducing those behaviors.
- Run focused tests while iterating, for example `./gradlew test --tests 'fully.qualified.TestClass'`, then run `./gradlew check` before handing off code changes. Run any separately configured integration-test tasks affected by the change as well.
- Never disable tests, weaken assertions, or suppress failures merely to make a build pass. Report unrelated failures separately.
- For documentation-only changes, inspect formatting and run `git diff --check`; a full build is unnecessary unless build behavior changed.
- In the handoff, state what changed, which checks ran, their results, and any checks that could not run and why. Do not claim validation that was not performed.

## Security and data handling

- Never hardcode or commit passwords, API keys, AWS access keys, session tokens, private keys, or other real credentials in source code, configuration, tests, fixtures, documentation, or commit messages.
- Obtain secrets through runtime configuration, an approved secret manager, or the AWS SDK credential provider chain. Prefer workload identities and short-lived credentials over long-lived static keys.
- Use obvious, nonfunctional placeholders in examples. Keep local secret files outside version control and add appropriate ignore rules if introducing them; ignore rules do not protect files already tracked by Git.
- Never print secrets, authorization headers, signed URLs, or record payloads in logs, exception messages, test output, or diagnostic dumps. Log only the minimum operational metadata needed, and redact sensitive fields.
- If a secret is discovered in tracked files or history, do not reproduce it. Notify the user, remove the exposure from the current change where appropriate, and flag the need for revocation or rotation. Do not rewrite shared Git history without explicit authorization.
- Apply least privilege to AWS permissions: restrict access to the required buckets, prefixes, and operations. Do not introduce public bucket access or broad wildcard permissions without a documented requirement.
- Require TLS with certificate verification for production S3 connections. Keep any HTTP endpoints needed for local tests explicitly limited to test/development configuration. Do not disable certificate verification to work around connection errors.
- Use S3 encryption at rest appropriate to the deployment and document any required bucket or KMS configuration. Treat offloaded records as having the same confidentiality requirements as their Kafka values.
- Treat Kafka values, headers, claim check references, and configuration as untrusted input. Validate formats, lengths, versions, and permitted destinations before acting on them.
- Do not let a record-supplied reference select arbitrary URLs, endpoints, buckets, or unrestricted object keys. Resolve references only within configured storage boundaries and enforce any applicable tenant isolation.
- Bound upload/download sizes, memory use, concurrency, timeouts, and retries. Avoid unbounded buffering, retry loops, and unsafe deserialization of untrusted data.
- Handle failures explicitly. Never acknowledge successful offloading before storage succeeds or silently discard data when storage or restoration fails.
- Do not delete offloaded objects without a defined retention policy that accounts for Kafka retention, replay, and multiple consumers.
- Prefer maintained libraries and standard security mechanisms over custom cryptography or authentication. Review new dependencies and upgrades for necessity, compatibility, and known vulnerabilities; document unresolved findings.
- Add negative tests for security-sensitive changes, including rejected references, invalid configuration, and permission failures where applicable.

## Documentation and completion

- Update the README or relevant documentation when changing configuration, setup, dependencies, public behavior, or operational requirements.
- Document new configuration defaults, validation rules, failure behavior, and compatibility implications. Include migration guidance for breaking changes to configuration or stored reference formats.
- Keep security requirements enforceable in code or infrastructure where possible; prose alone is not a substitute for validation, permissions, or tests.
