# Contributing to logchi

Thanks for taking the time. Bug reports, ideas and pull requests are all welcome.

## Reporting a bug

Open an [issue](https://github.com/rbaxtiyorov/logchi/issues) with:

- the logchi version, Spring Boot version and Java version
- your `logchi:` configuration
- what you expected and what happened, with the log lines or stack trace

For a security problem, don't open an issue. See [SECURITY.md](SECURITY.md).

## Suggesting a change

For anything larger than a small fix, open an issue first and describe what you want to do. It saves you from
writing code that can't be merged.

## Sending a pull request

1. Fork the repository and create a branch from `main`.
2. Make the change, with a test that fails without it.
3. Run the build. It needs Java 17 or newer and nothing else:

   ```bash
   ./mvnw verify
   ```

4. Open a pull request against `main` and say what the change does and why.

CI runs the tests on Java 17, 21 and 25 against Spring Boot 4.0 and 4.1, and builds the Spring Boot 3 starter on
every run. A pull request is merged once CI is green and the change has been reviewed.

## What a change needs

- **Both starters keep working.** The Spring code in `logchi-spring-common` is compiled twice, against Spring
  Boot 3 and Spring Boot 4, so it can only use what both have.
- **`logchi-core` depends only on SLF4J and Logback.** No Spring, no Jackson, no Lombok.
- **Logging never breaks the application.** A failure inside logchi must not throw into the caller.
- **Settings stay documented.** If you add or change a `logchi.*` key, update
  [docs/configuration.md](docs/configuration.md) and `spring-configuration-metadata.json`.

## License

By contributing, you agree that your contribution is licensed under the [Apache License 2.0](LICENSE).
