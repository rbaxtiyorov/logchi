# logchi configuration

Everything logchi does is controlled under `logchi:` in your `application.yml`. Every key is optional. With no
configuration at all you get the defaults on this page.

- [Everything at a glance](#everything-at-a-glance)
- [How settings are read](#how-settings-are-read)
- [General](#general): where files go and what they are called
- [Console](#console)
- [File](#file): format, [rotation](#rotation), [retention](#retention), [async writing](#async-writing)
- [Levels](#levels): a file per level
- [Channels](#channels): named logs such as an audit trail
- [Mask](#mask): keeping secrets out
- [Context](#context): request id, user and headers on every line
- [Logged](#logged): defaults for `@Logged`
- [File names](#file-names)
- [Ready-made setups](#ready-made-setups)
- [Messages you may see](#messages-you-may-see)

## Everything at a glance

Every key with its default value:

```yaml
logchi:
  enabled: true
  dir: ./logs
  app-name: ${spring.application.name}
  instance-id:                     # empty: host or pod name
  stack-trace-lines: 30
  multiline-loggers: []

  console:
    enabled: true
    format: text                   # text | json
    color: auto                    # auto | always | never

  file:
    enabled: true
    format: text                   # text | json
    json-flavor: ecs               # ecs | logstash | compact
    extension: txt
    permissions:                   # empty: decided by the system
    rotation:
      period: daily                # hourly | daily | none
      max-size: 100MB
    retention:
      hot: 1d                      # then gzip
      cold: 30d                    # then delete
      compress: true
      compress-level: 6
      max-total: 5GB
      min-free-disk: 1GB
      scope: shared                # shared | own
      check-interval: 5m
    async:
      enabled: true
      queue-size: 8192
      drop-below: INFO
      flush-timeout: 5s

  levels:
    error: { cold: 90d }

  channels: {}

  mask:
    enabled: true
    use-defaults: true
    cards: true
    keys: []
    patterns: []
    replacement: "***"

  context:
    enabled: true
    trace-id: true
    header: X-Request-Id
    user-id: principal             # principal | header:<name> | none
    extra-headers: []
    order: -2147483638             # highest precedence + 10

  logged:
    enabled: true
    level: DEBUG
    slow-level: WARN
    error-level: ERROR
    slower-than:
    max-arg-length: 500
```

## How settings are read

**From anywhere Spring Boot reads configuration.** `application.yml`, `application.properties`, profile files
such as `application-prod.yml`, environment variables and command-line arguments all work.

| In `application.yml` | Environment variable | Command line |
|---|---|---|
| `logchi.dir` | `LOGCHI_DIR` | `--logchi.dir=/var/log/orders` |
| `logchi.file.retention.cold` | `LOGCHI_FILE_RETENTION_COLD` | `--logchi.file.retention.cold=14d` |
| `logchi.file.retention.min-free-disk` | `LOGCHI_FILE_RETENTION_MINFREEDISK` | `--logchi.file.retention.min-free-disk=2GB` |
| `logchi.mask.keys` | `LOGCHI_MASK_KEYS=ssn,iban` | `--logchi.mask.keys=ssn,iban` |

For an environment variable, write the key in upper case, turn dots into underscores and drop the dashes.

**Once, at startup.** Changing a value needs a restart. logchi starts before any bean exists so it can capture
startup logs, which means a file loaded with `@PropertySource` on one of your own classes is read too late. Use
the sources above.

**Durations** are a whole number and a unit: `500ms`, `2s`, `5m`, `12h`, `30d`. A number without a unit is
milliseconds.

**Sizes** are a whole number and a unit: `512KB`, `100MB`, `5GB`, `1TB`. A number without a unit is bytes, and
1KB is 1024 bytes.

**Mistakes stop startup**, with a message that names the bad value, for example
`Invalid duration 'whenever'. Examples: 500ms, 2s, 5m, 12h, 30d`. An unknown option such as `format: xml` does
the same.

**Your IDE knows these keys.** IDEs with Spring support autocomplete and describe every key in
`application.yml`.

## General

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | Master switch for the whole library |
| `dir` | `./logs` | Folder the log files are written to |
| `app-name` | `spring.application.name`, else `app` | First part of every file name |
| `instance-id` | host or pod name | Second part of every file name, unique per running instance |
| `stack-trace-lines` | `30` | Most stack frames printed per exception |
| `multiline-loggers` | `[]` | Loggers whose messages keep their line breaks |

**`enabled: false`** turns everything off: no files, Spring Boot's normal console output, no request context, and
`@Logged` does nothing. `@Log` fields are still filled in, so your code keeps working. To stop only the files,
use [`file.enabled: false`](#file) instead.

**`dir`** is resolved against the directory the app was started from, and created if it doesn't exist. If logchi
can't write there, the app still starts and keeps logging to the console. It prints
`logchi: cannot write <file> in <dir>` to standard error and retries every second.

**`app-name`** and **`instance-id`** may contain letters, digits, `-` and `_`. Any other character becomes `_`,
so `orders.api` gives files starting with `orders_api`.

- If you rename the app, files written under the old name are no longer cleaned up. Delete them by hand.
- With `instance-id` empty, logchi uses the `HOSTNAME` environment variable, which is the pod name in
  Kubernetes, and otherwise the machine's host name.
- Two processes can't share an id in one folder. If the id is already in use by a live process, the later one
  adds its process id, for example `api-7d9f-48213`.
- Set it yourself when you want a readable, stable name: `instance-id: blue`.
- **In Docker, set it.** `HOSTNAME` there is the container id, which changes every time the container is
  recreated. Each deploy would start a new set of files, and the old live file would wait an hour before
  it is rolled and cleaned up.
- If logchi can't create its lock file in `<dir>/.logchi`, it can't tell whether the id is free. It then adds
  the process id to be safe and says so on standard error.

**`stack-trace-lines`** applies to each exception separately. Every `Caused by` and `Suppressed` section gets the
same allowance, so the root cause is never cut off. Hidden frames are summarized as `... 64 more`. `0` prints
every frame.

**`multiline-loggers`**: normally a message is always written on one line. Line breaks inside it are written as
the two characters `\n`, so text that came from a user can never pass for a separate log line. For loggers
that write multi-line messages on purpose, such as a request logger that draws a box, list them here:

```yaml
logchi:
  multiline-loggers:
    - com.example.web.HttpLoggingFilter
```

```
2026-11-10 17:04:59.637+05:00 INFO  [http-nio-8080-exec-2] [tid=cdccedcf8da5674c] c.e.w.HttpLoggingFilter : ┌─ REQ GET /orders
	│ body: {"item":"book"}
	└─ 200 OK 12ms
```

- Every continuation line starts with a tab. A line that starts a new event never does, so forged lines are
  still easy to tell apart.
- A name also covers the loggers below it: `com.example.web` covers every class in that package.
- This applies to text output, on the console and in files. JSON always keeps a message in one field.

## Console

| Key | Default | Values | What it does |
|---|---|---|---|
| `console.enabled` | `true` | `true`, `false` | Whether anything is printed to the console |
| `console.format` | `text` | `text`, `json` | Line format on the console |
| `console.color` | `auto` | `auto`, `always`, `never` | Colored levels and logger names |

- **`enabled: false`** silences the console completely. Use it when only the files matter.
- **`format: json`** is for platforms that collect container output. It uses the flavor set in
  [`file.json-flavor`](#file).
- **`color: auto`** colors only a real terminal. Colors are off when output is piped or redirected, when the
  `NO_COLOR` environment variable is set, or when `TERM` is `dumb`. IDE run windows don't count as terminals, so
  use `always` in your development profile to get colors there.

logchi replaces the console output Spring Boot sets up, so each line prints once. `logging.pattern.console`
no longer applies. If something parses your console lines, such as a log shipper's pattern, check it against
the layout:

```
<date> <time><offset> <LEVEL> [<thread>] [<context>] <logger> : <message> <key=value ...>
```

## File

| Key | Default | Values | What it does |
|---|---|---|---|
| `file.enabled` | `true` | `true`, `false` | Whether log files are written |
| `file.format` | `text` | `text`, `json` | Line format in the files |
| `file.json-flavor` | `ecs` | `ecs`, `logstash`, `compact` | Field names used when the format is `json` |
| `file.extension` | `txt` | letters and digits | File extension, without the dot |
| `file.permissions` | none | `"640"`, `rw-r-----` | Who may read the log files |

- **`enabled: false`** leaves only the console. Nothing is created in `dir`.
- **`extension`**: characters other than letters, digits, `-` and `_` become `_`. If you change it, files written with the old extension are no longer cleaned up. Delete them
  by hand.
- **`permissions`**: by default the operating system decides who may read new files, usually everyone on the
  machine. When logs hold personal data, set `permissions: "640"` (owner and group) or `"600"` (owner only).
  The log folder gets the matching rights when logchi creates it: `750` for `640`. Existing files keep what
  they have. It has no effect on Windows.
  - Write the number in quotes. YAML reads an unquoted `0640` as a different number.
  - The owner must keep read and write, or startup stops.
  - In a container, check which group the process runs as before giving the group access.

**JSON flavors.** Each event is one JSON object on one line. The flavor only changes the field names:

| | `ecs` | `logstash` | `compact` |
|---|---|---|---|
| Time | `@timestamp` | `@timestamp` | `ts` (epoch milliseconds) |
| Level | `log.level` | `level`, `level_value` | `lvl` |
| Logger | `log.logger` | `logger_name` | `log` |
| Message | `message` | `message` | `msg` |
| Thread | `process.thread.name` | `thread_name` | `th` |
| Application | `service.name` | `service` | `svc` |
| Instance | `service.instance.id` | `instance` | `inst` |
| Request id | `trace.id` | `traceId` | `traceId` |
| User | `user.id` | `userId` | `userId` |
| Exception | `error.type`, `error.message`, `error.stack_trace` | `stack_trace` | `err` |

Values you attach with `addKeyValue(...)` and headers from [`context.extra-headers`](#context) become fields of
their own. If one has the same name as a built-in field, it is written with a `kv_` prefix.

### Rotation

Rotation decides when the live file is closed, renamed and replaced by a fresh one.

| Key | Default | Values | What it does |
|---|---|---|---|
| `file.rotation.period` | `daily` | `hourly`, `daily`, `none` | Start a new file every day or every hour |
| `file.rotation.max-size` | `100MB` | a size | Also start a new file when the next line would pass this size |

- Both rules apply together. A busy day produces several files for that day, numbered `0`, `1`, `2`.
- Days and hours follow the server's time zone. Timestamps on each line carry their offset, such as `+05:00`
  or `Z`.
- A file is renamed when the first line after the boundary is written, so a quiet app's file changes name with
  its next line.
- `max-size: 0` turns off rolling by size. `period: none` turns off rolling by time.
- Don't turn off both. A file that never rolls is never compressed or deleted.

### Retention

Retention decides what happens to files after they roll. The live file is never compressed or deleted.

| Key | Default | What it does |
|---|---|---|
| `file.retention.hot` | `1d` | How long a rolled file stays plain text before it is gzipped |
| `file.retention.cold` | `30d` | How long a rolled file is kept before it is deleted |
| `file.retention.compress` | `true` | Whether files are gzipped at all |
| `file.retention.compress-level` | `6` | `1` is fastest, `9` is smallest. Other values stop startup |
| `file.retention.max-total` | `5GB` | Size limit for all of this app's log files in the folder |
| `file.retention.min-free-disk` | `1GB` | Free disk space below which file logging pauses |
| `file.retention.scope` | `shared` | Whether this instance also cleans up after other instances |
| `file.retention.check-interval` | `5m` | How often retention runs |

**`hot` and `cold`** both count from the last line written to the file, not from when it was created or
compressed.

- With the defaults, a file whose last line was written on Monday is gzipped on Tuesday and deleted 30 days after
  Monday.
- If `cold` is shorter than `hot`, files are deleted without ever being compressed.
- There is no "keep forever" value. Use a long one, such as `cold: 3650d`.
- [Levels](#levels) and [channels](#channels) can each have their own `hot` and `cold`.

**`max-total`** adds up every logchi file of this app in the folder, live files included. When the total passes
the limit, the oldest rolled files are deleted first, whichever level or channel they belong to, until it fits.

- A tight limit can therefore delete audit or error files before their `cold` is reached. Size it for your
  longest retention.
- It is checked every `check-interval`, so the folder can briefly go over between checks.
- `0` removes the limit.

**`min-free-disk`** protects the rest of the machine. The free space of the disk holding `dir` is checked every
5 seconds.

- Below the floor, file logging pauses and the console keeps working. A warning says so, and once a minute
  another reports how many lines were not written.
- When space comes back, file logging resumes by itself.
- `0` turns the check off.

**`scope`** matters when several instances write to the same folder.

- `shared`: each instance compresses and deletes rolled files from every instance of this app, and takes over
  the live file of an instance that has been gone for an hour. Instances take turns, so the work isn't done
  twice. This is what cleans up after replaced Kubernetes pods.
- `own`: each instance only touches its own files. Files left by an instance that never comes back stay
  forever, and `max-total` applies to each instance separately.

**`check-interval`** is how often the checks above run. The first run is 5 seconds after startup, and the
shortest interval is one second.

### Async writing

By default a log call puts the line on a queue and returns. A background thread writes it to the file.

| Key | Default | What it does |
|---|---|---|
| `file.async.enabled` | `true` | Write through a queue instead of on the calling thread |
| `file.async.queue-size` | `8192` | Lines each file's queue can hold |
| `file.async.drop-below` | `INFO` | When the queue is nearly full, drop lines at this level and lower |
| `file.async.flush-timeout` | `5s` | How long shutdown waits for the queue to empty |

- Each file has its own queue.
- "Nearly full" means less than a fifth of the queue is free. From then on, lines at `drop-below` and lower are
  dropped and counted. Once a minute a warning reports how many.
- WARN and ERROR are never dropped, whatever `drop-below` says. The calling thread waits for space instead.
- `drop-below` accepts `TRACE`, `DEBUG` or `INFO`. Anything else stops startup.
- `queue-size` must be 16 or more.
- `enabled: false` makes every log call write to disk itself. Nothing is queued or dropped, and callers wait for
  the disk.

## Levels

`levels` gives a level its own file, in addition to the main one. That lets you keep important lines longer
than routine ones.

```yaml
logchi:
  levels:
    error: { cold: 365d }      # orders-error.<instance>.txt
    warn:  { cold: 60d }       # orders-warn.<instance>.txt
```

- Level names are `trace`, `debug`, `info`, `warn` and `error`.
- A level file holds lines of exactly that level. The `warn` file has no errors in it.
- The lines are copies. They stay in the main file as well.
- The file appears with the first line of that level.
- `error` is on by default, kept 90 days. Adding other levels doesn't remove it. To remove it:
  `error: { enabled: false }`.
- `levels` doesn't change what gets logged. Use Spring Boot's `logging.level.*` for that.

Each entry accepts these options, and inherits the rest from [`file`](#file):

| Option | Default | What it does |
|---|---|---|
| `enabled` | `true` | `false` removes this file |
| `hot` | `file.retention.hot` | Time before this file's rolled copies are gzipped |
| `cold` | `file.retention.cold` | Time before they are deleted |
| `format` | `file.format` | `text` or `json` for this file |
| `async` | `file.async.enabled` | `false` writes each line before the log call returns |

## Channels

A channel is a named log with its own file and its own retention, for lines that don't belong in the main log,
such as an audit trail.

```yaml
logchi:
  channels:
    audit:    { cold: 365d, format: json, async: false }
    payments: { cold: 90d, additive: true }
```

```java
import io.github.rbaxtiyorov.logchi.core.Channels;

Channels.get("audit").info("refund approved orderId={}", id);
```

- The file is `<app>-<channel>.<instance>.txt`, for example `orders-audit.api-7d9f.txt`.
- A channel accepts the same options as a [level](#levels), plus `additive`, `console` and `loggers`.
- **`additive: false`** (the default): the lines go to the channel's file and stay off the console and out
  of the main file.
- **`additive: true`**: the lines go to the channel's file and everywhere else too.
- **`console: true`** keeps the lines on the console as well, while still leaving them out of the main file.
- [Level files](#levels) still receive a channel's lines of that level: an ERROR written to a channel is
  also in the error file.
- A channel can't have the name of a level you keep a file for, such as `error`.
- `Channels.get("x")` for a channel you haven't declared writes to the main log.
- To change what a channel lets through, set its level like any logger:
  `logging.level.logchi.channel.audit: WARN`.

**Sending an existing logger to a channel.** You don't have to change code to use a channel. `loggers` moves
everything an existing logger writes into the channel's file:

```yaml
logchi:
  channels:
    http:
      cold: 7d                 # request bodies are kept a week, everything else longer
      console: true            # still printed, for whoever watches the console
      loggers:
        - com.example.web.HttpLoggingFilter
```

A name also covers the loggers below it. The lines leave the main file, unless the channel is `additive`.

## Mask

Masking replaces secrets with `***` before a line is written, on the console and in every file. It covers
messages, exception messages, stack traces, attached key-values and request context.

| Key | Default | What it does |
|---|---|---|
| `mask.enabled` | `true` | `false` turns all masking off |
| `mask.use-defaults` | `true` | Mask the built-in list of secret keys |
| `mask.cards` | `true` | Mask payment card numbers |
| `mask.keys` | `[]` | Your own secret keys, added to the built-in list |
| `mask.patterns` | `[]` | Regular expressions. Everything they match is replaced |
| `mask.replacement` | `***` | The text written in place of a secret |

**Built-in keys:** `password`, `passwd`, `pwd`, `secret`, `token`, `authorization`, `api-key`, `apikey`,
`api_key`, `cardnumber`, `card_number`, `cvv`, `cvc`, `cookie`.

A key matches if it *contains* one of these words, in any letter case. So `accessToken`, `x-auth-token` and
`clientSecret` are all caught. Two exceptions keep useful text readable: a number or `true`/`false` under a
key that only describes a secret, such as `tokenCount=5`, is kept, and a class name at the start of a message,
such as `TokenService: refreshed`, is not a key.

| You log | What is written |
|---|---|
| `login password=hunter2` | `login password=***` |
| `{"accessToken":"eyJhbGciOi"}` | `{"accessToken":"***"}` |
| `Authorization: Bearer eyJhbGciOi` | `Authorization: ***` |
| `clientSecret: s3cr3t, user: ann` | `clientSecret: ***, user: ann` |
| `paid with 4242 4242 4242 4242` | `paid with ***` |
| `.addKeyValue("apiKey", key)` | `apiKey=***` |
| `{"refreshToken":"eyJhbG` (cut off) | `{"refreshToken":"***"` |
| `InvalidTokenException: expired` | unchanged: an exception name is not a key |
| `token is eyJhbGciOi` | `token is eyJhbGciOi` (not masked) |

A secret whose closing quote is missing is masked up to the end of its line.

The last row shows the limit. A value is recognized by the key in front of it, written as `key=value`,
`key: value` or a JSON field. A secret with no key can't be recognized, so don't log secrets on purpose.

**`keys`**: add the names your own secrets go by.

```yaml
logchi:
  mask:
    keys: [ssn, iban]
```

Because matching is "contains", a short word also matches longer keys: `pin` would mask `shipping=express`
too. Prefer specific words.

**`patterns`**: for secrets you can describe by shape. Each is a Java regular expression, and the whole match is
replaced. Use single quotes in YAML so backslashes are kept.

```yaml
logchi:
  mask:
    patterns: ['\b\d{3}-\d{2}-\d{4}\b']      # 123-45-6789 becomes ***
```

**`cards`** masks numbers of 14 to 19 digits, with or without spaces or dashes, that pass the card checksum.
About one in ten random numbers of that length passes it by chance. If your logs carry other long numbers,
such as national id numbers or account numbers, turn this off, or a tenth of them will be masked.

**`use-defaults: false`** keeps only your own `keys`.

For arguments of `@Logged` methods, there is also the `@Mask` annotation: `pay(long orderId, @Mask String card)`.

## Context

In a web application, logchi adds who and what to every line logged while a request is handled:

```
INFO  [tid=cdccedcf8da5674c uid=alice tenantId=acme] c.e.OrderService : order created
```

| Key | Default | What it does |
|---|---|---|
| `context.enabled` | `true` | `false` turns all of this off |
| `context.trace-id` | `true` | Give every request an id (`tid`) |
| `context.header` | `X-Request-Id` | Header the id is read from and returned in |
| `context.user-id` | `principal` | Where the user (`uid`) comes from |
| `context.extra-headers` | `[]` | Request headers to add to every line |
| `context.order` | highest precedence + 10 | Where logchi's request filter runs among your filters |

**Request id.** Every response carries the id in the `X-Request-Id` header, so a user or another service can
quote it.

- If the request already has that header, its value is reused. This lets one id follow a call across services.
- An incoming value is only accepted if it is at most 64 letters, digits, `.`, `_` or `-`. Anything else is
  replaced with a new id.
- If Micrometer Tracing is on, its trace id is used.
- If a filter of your own, running before logchi's, already put an id in the response header, logchi uses
  that id instead of making a second one.
- The id follows work onto `@Async` threads, unless you define your own `TaskDecorator` bean.
- A request handled asynchronously by the server keeps its id and user when it continues on another thread.

**`user-id`** has three settings:

| Value | The user is |
|---|---|
| `principal` | The logged-in user's name, from Spring Security or the server's own login |
| `header:X-User-Id` | The value of that request header. Use any header name |
| `none` | Not logged |

Only use a header behind a gateway that sets it. Otherwise a caller can put any name there.

The user is known once Spring Security has checked the request. Lines logged before that, and requests that
are rejected with 401, have no `uid`. From then on it stays until the request ends, so a filter of your own
that logs after the request was handled, such as a request logger, has it too.

**`order`** is a number: lower runs earlier. logchi's filter must run before any filter of yours that should
have the request id on its lines. The default puts it ahead of Spring Security and almost everything else. If
one of your filters uses that same position, give yours a higher number or lower this one.

**When a request fails**, the request id and user are also on the error line the server itself writes
(`Servlet.service() ... threw exception`).

**`extra-headers`** copies request headers onto every line. The name drops a leading `X-` and becomes camel case:

| Header | On the line |
|---|---|
| `X-Tenant-Id` | `tenantId=acme` |
| `X-Correlation-Id` | `correlationId=7f3a` |
| `Accept-Language` | `acceptLanguage=en` |

Header values have control characters removed and are cut at 128 characters, so a caller can't forge log lines.

**WebFlux** applications get the request id only: it is returned in the response and placed in the Reactor
context. To see it on log lines, add `io.micrometer:context-propagation` and set
`spring.reactor.context-propagation=auto`. `user-id` and `extra-headers` apply to servlet applications.

## Logged

These are the defaults for every `@Logged` in the application. An annotation can override most of them for
itself.

| Key | Default | Override on the annotation | What it does |
|---|---|---|---|
| `logged.enabled` | `true` | | `false` makes every `@Logged` do nothing |
| `logged.level` | `DEBUG` | `level` | Level of the entry (`→`) and exit (`←`) lines |
| `logged.slow-level` | `WARN` | | Level of the exit line when the call was slow |
| `logged.error-level` | `ERROR` | `onError` | Level of the line when the method throws (`✗`) |
| `logged.slower-than` | none | `slowerThan` | How long a call may take before it counts as slow |
| `logged.max-arg-length` | `500` | `maxLength` | Longest argument or result, in characters |

- Levels are `TRACE`, `DEBUG`, `INFO`, `WARN` or `ERROR`. Anything else stops startup.
- With `slower-than` empty, nothing is flagged as slow unless an annotation sets its own `slowerThan`.
- A longer value is cut and ends with the number of characters left out, such as `…(+1200)`. `0` means no limit.
- Collections, maps and arrays are logged as their size, such as `ArrayList[size=3]`, never their contents.

```yaml
logchi:
  logged:
    slower-than: 500ms       # every @Logged method slower than this is logged at WARN
```

```java
@Logged(slowerThan = "2s", level = Logged.Level.INFO, result = false)
public Report build(ReportRequest request) { ... }
```

Because entry and exit lines are DEBUG by default, you see them only where DEBUG is on, for example with
`logging.level.com.example: DEBUG`. Slow calls and failures show at any level.

## File names

```
<app>[-<level or channel>].<instance>[.<date>.<number>].<extension>[.gz]
```

```
logs/
├── orders.api-7d9f.txt                     live
├── orders.api-7d9f.2026-11-08.0.txt        rolled, not yet compressed
├── orders.api-7d9f.2026-11-08.1.txt        second file of that day
├── orders.api-7d9f.2026-11-07.0.txt.gz     compressed
├── orders-error.api-7d9f.txt               level file
├── orders-audit.api-7d9f.txt               channel file
└── .logchi/                                lock files, leave them alone
```

With `rotation.period: hourly` the date includes the hour: `orders.api-7d9f.2026-11-08-14.0.txt`.

## Ready-made setups

**Local development.** Colors in the IDE, files in the build folder where `mvn clean` removes them:

```yaml
logging:
  level:
    com.example: DEBUG              # shows @Logged entry and exit lines
logchi:
  dir: target/logs
  console:
    color: always
  file:
    retention: { hot: 1h, cold: 2d, max-total: 200MB }
```

**One server.** Long memory for errors and audit, a firm cap on disk:

```yaml
logchi:
  dir: /var/log/orders
  file:
    retention: { hot: 1d, cold: 30d, max-total: 20GB, min-free-disk: 2GB }
  levels:
    error: { cold: 180d }
  channels:
    audit: { cold: 365d, async: false }
```

**Kubernetes, pods sharing a volume.** Mount the volume and point logchi at it. The defaults do the rest:

```yaml
logchi:
  dir: /var/log/orders
```

**JSON for a log shipper** such as Filebeat or Promtail reading the files:

```yaml
logchi:
  file:
    format: json
    json-flavor: ecs
    extension: log
```

**Containers whose output is collected**, with no files at all:

```yaml
logchi:
  console:
    format: json
  file:
    enabled: false
```

## Messages you may see

| Message | Meaning |
|---|---|
| `logchi: dir=/app/logs instance=api-7d9f format=text hot=1d …` | Normal. Printed once at startup with the settings in effect and the disk space used |
| `Invalid duration '…'. Examples: 500ms, 2s, 5m, 12h, 30d` | A duration is misspelled. Startup stops |
| `Invalid size '…'. Examples: 512KB, 100MB, 5GB` | A size is misspelled. Startup stops |
| `Unknown level in logchi.levels: …` | A name under `levels` isn't a log level. Startup stops |
| `Invalid @Logged on …` | A `slowerThan` on an annotation is misspelled. Startup stops |
| `Invalid logchi.logged.level '…'` | A level under `logged` is misspelled. Startup stops |
| `Invalid compress-level '…'` | Use 1 to 9. Startup stops |
| `Invalid file permissions '…'` | Use a form such as `640` or `rw-r-----`. Startup stops |
| `logchi: cannot write … in …` | The folder isn't writable or the disk is full. The app keeps running and logchi retries every second |
| `logchi: free disk … is below min-free-disk …, pausing file logs` | The disk is nearly full. The console keeps working |
| `logchi: free disk recovered …, resuming file logs` | Space came back |
| `logchi: … events could not be written to disk` | Lines lost while paused or while the folder was unwritable |
| `logchi: dropped … low-level events because the log queue was full` | The app logged faster than the disk could write. See [async writing](#async-writing) |
| `logchi: … was removed or replaced, reopening it` | Someone deleted or replaced the live file. logchi started a new one |
| `logchi: cannot lock instance …` | The `.logchi` folder isn't writable. File names get the process id added |
| `Invalid queue-size '…'`, `Invalid drop-below '…'` | See [async writing](#async-writing). Startup stops |
| `logchi: disabled, Logback is not the active SLF4J provider` | The app uses another logging backend, such as Log4j2. logchi needs Logback |
