# Security policy

## Supported versions

Security fixes are released for the latest published version.

## Reporting a vulnerability

Please report it privately, not in a public issue.

1. Open the [Security tab](https://github.com/rbaxtiyorov/logchi/security) of this repository.
2. Click **Report a vulnerability**.
3. Describe the problem, the version affected and how to reproduce it.

You will get a reply within 7 days. Once a fix is released, the report is published with credit to you, unless
you prefer otherwise.

## What counts

Examples of problems worth reporting:

- a secret that reaches a log file or the console despite masking that should have caught it
- log forging: input that produces extra or altered log lines
- a way for request data, such as headers, to write outside the log folder or exhaust the disk
- a way to crash or block the application through logging

Masking is pattern-based and can't recognize a secret that has no recognizable key. That limit is
[documented](docs/configuration.md#mask) and is not a vulnerability by itself.
