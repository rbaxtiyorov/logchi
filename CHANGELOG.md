# Changelog

## 0.1.2

### Added

- `logchi.logged.args` and `logchi.logged.result`: turn arguments and return values off for every `@Logged`
  at once. For applications whose methods handle personal data.
- `logchi.logged.quiet-exceptions` and `@Logged(quietOn = …)`: exceptions that are an expected outcome are
  logged at the normal level without a stack trace.

### Changed

- A call that passes `slower-than` and then throws is now flagged `SLOW` and logged at the slow level or
  higher. Before, only calls that returned were flagged.
- Text output no longer repeats the duration of a `@Logged` line as `durationMs=…`. JSON keeps the field.

## 0.1.1

Upgrade from 0.1.0 is recommended: the first fix below can stop an application from logging.

### Fixed first

- **A long quoted secret could crash masking.** A value of about 2000 characters or more after a secret key,
  such as a JWT in `"access_token":"…"`, overflowed the stack. The error reached the code that logged, the
  file writer stopped, and later WARN and ERROR calls could block. Masking no longer recurses, and a failure
  while rendering a line now becomes a marker line instead of an error.
- **Exception messages could forge log lines.** A line break inside an exception message started a new line
  that looked like a log entry. Continuation lines are now indented.
- A quoted secret whose closing quote was cut off is now masked.
- Masking time no longer grows with the square of a long word made of secret keys.
- A thread name with a line break could forge a log line.
- A quoted secret on the line after its key, `password => x` and `user[password]=x` are now masked.

### Added

- `logchi.multiline-loggers`: chosen loggers keep the line breaks in their messages. Continuation lines are
  indented with a tab, so they can't pass for a new log line.
- `logchi.channels.<name>.loggers`: send an existing logger to a channel's file by configuration, with no code
  change.
- `logchi.channels.<name>.console`: keep a channel's lines on the console while leaving them out of the main
  file.
- `logchi.context.order`: choose where logchi's request filter runs among your own filters.
- `logchi.file.permissions`: who may read the log files, such as `640`.

### Changed

- Timestamps in text output now carry their offset: `2026-11-10 17:04:59.637+05:00`.
- `cookie` joined the built-in secret keys. Exception names such as `InvalidTokenException: expired` are no
  longer treated as a key and a secret, and masking never reaches across a line break.

- The user (`uid`) now stays on the request until it ends, so a filter that logs after the request was handled
  sees it too.
- When a request fails, the request id and user are also on the error line the server itself writes.
- After a Logback reset, files are re-attached before the console, so no line reaches the console but misses
  the file. Every appender and every routed logger is now checked, not only the main one.
- After a crash or a full disk cut an event short, the next event starts on its own line.
- Rolled files keep counting upward after older ones are deleted, so name order stays time order.
- If the live file is deleted or replaced by someone else, logchi starts a new one within a second.
- If an application filter ahead of logchi's already issued a request id, logchi uses it.
- Class names at the start of a message, such as `TokenService: refreshed`, are no longer masked, and neither
  is a number or flag under a key that only describes a secret, such as `tokenCount=5`.
- Errors written to a channel are now copied to the error file like any other error.
- A message is masked once per event, not once per file, and lines without digits or key words skip the
  scans.
- Asynchronous requests keep their request id and user when they continue on another thread.
- `@Log` now works in test slices such as `@WebMvcTest`.
- An application that sets `spring.aop.proxy-target-class=false` keeps interface proxies.
- `file.permissions` also applies to the lock folder. A write that keeps failing moves on to a new file.
- A `queue-size` below 16 and a `drop-below` other than TRACE, DEBUG or INFO now stop startup.
- When the `.logchi` folder can't be written, retention still runs and file names get the process id.

### Fixed

- `logchi.enabled=false` no longer leaves `@Log` fields `null`.
- `logchi.file.enabled=false` no longer creates a `.logchi` folder.
- A `compress-level` outside 1 to 9 now stops startup. Before, it silently stopped files from being compressed
  and deleted.
- A misspelled level under `logchi.logged` now stops startup. Before, it failed the first call of a `@Logged`
  method.

### Verified

- Request id and user with Spring Security as an OAuth2 resource server (JWT), on Spring Boot 3 and 4.

## 0.1.0

First release.
