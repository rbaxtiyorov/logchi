# logchi

[![Maven Central](https://img.shields.io/maven-central/v/io.github.rbaxtiyorov/logchi-core)](https://central.sonatype.com/namespace/io.github.rbaxtiyorov)

File logging for Spring Boot that works the moment you add the dependency. Built on SLF4J and Logback, so
your existing `log.info(...)` calls and every library's logs flow through it unchanged.

## Highlights

**Slow and failing calls stand out.** One annotation logs every call of a bean. Entry and exit lines are DEBUG, so
production at INFO only shows the calls that need attention:

```java
@Service
@Logged(slowerThan = "500ms")
public class PaymentService {
    public Receipt charge(Order order, @Mask String cardNumber) { ... }
}
```

```
WARN  c.e.PaymentService : ← charge 611ms SLOW(>500ms) Receipt[id=42] durationMs=611
ERROR c.e.PaymentService : ✗ charge 1203ms IllegalStateException: gateway down
```

**Every line belongs to a request and a user.** One command finds a whole request, across live and compressed
files:

```
2026-11-10 14:36:21.683 INFO  [http-nio-8080-exec-6] [tid=e5f86c5f56ee116e uid=alice] c.e.OrderService : order created orderId=42
```

```bash
zgrep e5f86c5f56ee116e logs/*
```

**Secrets don't reach disk.** Passwords, tokens, JSON secret fields and card numbers are masked in messages and
stack traces alike. Line breaks in logged values are escaped, so user input can't fake extra log lines.

```java
log.info("login password={}", password);              // login password=***
throw new IllegalStateException("token=" + token);    // token=*** in the stack trace too
```

**Retention in plain durations.** Files stay uncompressed while recent so they're easy to read, are gzipped after
`hot`, and deleted after `cold`. Errors and audit trails can be kept longer than routine lines:

```yaml
logchi:
  file:
    retention: { hot: 1d, cold: 30d, max-total: 5GB }
  levels:
    error: { cold: 90d }                    # errors also get their own file
  channels:
    audit: { cold: 365d, format: json }     # Channels.get("audit").info("refund approved")
```

**Built for production.**
- Each pod writes its own files on a shared volume, and files left by replaced pods are still compressed and
  expired.
- File logging pauses before the disk fills.
- Under load, INFO and below are dropped first, while WARN and ERROR wait for space.

**No setup.** Add the dependency, and one line at startup says where logs go and how much disk they use:

```
logchi: dir=/app/logs instance=api-7d9f format=text hot=1d cold=30d cap=5GB used=1.2GB(24%) channels=[audit] levels=[error]
```

## Why not just Spring Boot's logging?

Spring Boot can already write a rolling, gzipped log file, which is enough for one application writing one file.
logchi handles what goes wrong after that in production: disks fill up, secrets end up in files, errors get lost
among routine lines, and several pods write to the same volume.

|  | Spring Boot's file logging | logchi |
|---|---|---|
| Turning it on | Set `logging.file.name`, otherwise logs only go to the console | Add the dependency. Files go to `./logs` |
| Retention | Rolled files kept 7 days by default, with an optional size cap | Ages measured from the last write: uncompressed while recent, gzipped after `hot`, deleted after `cold`. Each level and channel can have its own |
| Errors | Mixed into the main file | Also copied to their own file, kept 90 days by default |
| Secrets | Written as logged | Passwords, tokens, JSON secret fields and card numbers masked before they reach disk |
| Request context | Trace ids once Micrometer Tracing is added | Trace id and user on every line. An incoming `X-Request-Id` is reused and returned |
| Full disk | No special handling | File logging pauses below a free-space floor, and a warning reports how many lines were skipped |
| Many instances, one directory | Processes with the same file name write and roll the same file | Each instance writes its own files, and files left by replaced pods are still compressed and expired |
| Method logging | Written by hand | `@Logged` logs calls and results, flags slow calls and masks `@Mask` arguments |
| JSON | Built in from Boot 3.4 | Any Boot 3 or 4 version, chosen per file |

## Install

logchi is on Maven Central, so there is no repository or token to set up. Add the starter that matches your
Spring Boot version.

```xml
<!-- Spring Boot 3 -->
<dependency>
    <groupId>io.github.rbaxtiyorov</groupId>
    <artifactId>logchi-spring-boot3-starter</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- Spring Boot 4 -->
<dependency>
    <groupId>io.github.rbaxtiyorov</groupId>
    <artifactId>logchi-spring-boot4-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

That's all. Logs now go to `./logs`, and one line at startup says where they went and how much disk they use:

```
logchi: dir=/app/logs instance=api-7d9f format=text hot=1d cold=30d cap=5GB used=1.2GB(24%) channels=[] levels=[error]
```

Requires Java 17 or newer.

## Use

### Get a logger

In a Spring bean, or anywhere else:

```java
import io.github.rbaxtiyorov.logchi.spring.annotation.Log;
import org.slf4j.Logger;

@Service
public class OrderService {
    @Log private Logger log;                               // Spring beans
}

public class PriceMath {
    private static final Logger log = Loggers.get();       // any class, no PriceMath.class needed
}
```

Both give you a standard SLF4J logger. Lombok's `@Slf4j` works too. Lombok also has its own `@Log`, which is for
classes and uses `java.util.logging`, so if a field annotation is rejected, check that the import is logchi's.

Key-values are written as `orderId=42` in text and as real fields in JSON:

```java
log.atInfo().addKeyValue("orderId", id).addKeyValue("amount", total).log("order created");
```

### Log methods with `@Logged`

```java
@Service
@Logged(slowerThan = "500ms")     // every public method, or put it on one method
public class OrderService {
    public Order create(OrderRequest req, @Mask String cardNumber) { ... }
}
```

```
DEBUG c.e.OrderService : → create(req=OrderRequest[items=3], cardNumber=***)
DEBUG c.e.OrderService : ← create 213ms Order[id=42]
WARN  c.e.OrderService : ← create 611ms SLOW(>500ms) Order[id=42] durationMs=611
ERROR c.e.OrderService : ✗ create 1203ms IllegalStateException: gateway down
```

Entry and exit lines are DEBUG, so production at INFO stays quiet while slow calls and failures still appear.

| Option       | Meaning                                                              |
|--------------|----------------------------------------------------------------------|
| `slowerThan` | Past this duration the exit line becomes WARN and is tagged `SLOW`   |
| `level`      | Level of the entry and exit lines                                    |
| `onError`    | Level of the line logged when the method throws                      |
| `args`       | Log arguments                                                        |
| `result`     | Log the return value                                                 |
| `maxLength`  | Longest rendered value. Collections always appear as `[size=N]`      |

`@Logged` works through Spring proxies, with the same limits as `@Transactional`: the class must be a Spring
bean and not `final`, and a call from inside the same object (`this.foo()`) is not logged.

### Channels

A channel is a named log with its own file and retention:

```java
Channels.get("audit").info("refund approved");
```

```yaml
logchi:
  channels:
    audit: { cold: 365d, async: false, format: json }
```

## Configure

Everything is optional. These are the defaults.

```yaml
logchi:
  enabled: true
  dir: ./logs
  app-name: ${spring.application.name}
  instance-id:                     # empty: HOSTNAME, then the machine name
  stack-trace-lines: 30            # frames per exception; causes are always kept

  console:
    enabled: true                  # false removes console output entirely
    format: text                   # text | json
    color: auto                    # auto | always | never

  file:
    enabled: true
    format: text                   # text | json
    json-flavor: ecs               # ecs | logstash | compact
    extension: txt
    rotation:
      period: daily                # hourly | daily | none
      max-size: 100MB
    retention:
      hot: 1d                      # age since last write before gzip
      cold: 30d                    # age since last write before delete
      compress: true
      compress-level: 6
      max-total: 5GB               # oldest rolled files are deleted first
      min-free-disk: 1GB           # below this, file logging pauses
      scope: shared                # shared | own
      check-interval: 5m
    async:
      enabled: true
      queue-size: 8192
      drop-below: INFO             # when nearly full, drop this level and lower
      flush-timeout: 5s

  levels:                          # copy a level to its own file
    error: { cold: 90d }           # on by default; { enabled: false } to remove

  channels: {}

  mask:
    enabled: true
    use-defaults: true             # password, token, secret, authorization, cvv, ...
    cards: true                    # numbers that pass the card checksum
    keys: []                       # added to the defaults
    patterns: []                   # extra regular expressions
    replacement: "***"

  context:
    enabled: true
    trace-id: true
    header: X-Request-Id           # reused if present, returned in the response
    user-id: principal             # principal | header:<name> | none
    extra-headers: []              # X-Tenant-Id becomes tenantId

  logged:
    enabled: true
    level: DEBUG
    slow-level: WARN
    error-level: ERROR
    slower-than:                   # default for @Logged without its own
    max-arg-length: 500
```

Durations accept `ms`, `s`, `m`, `h`, `d`. Sizes accept `KB`, `MB`, `GB`, `TB`. A value logchi can't parse stops
startup with a message naming it.

Sinks in `levels` and `channels` accept `hot`, `cold`, `format`, `async` and `enabled`, and inherit the rest
from `file`. Channels also accept `additive: true` to copy their lines to the main log. Per-level retention needs
a separate file because part of a file can't be deleted, which is why a level entry creates one. `levels` doesn't
change what gets logged: use `logging.level.*` for that.

## What ends up on disk

```
logs/
  orders.api-7d9f.txt                     active file
  orders.api-7d9f.2026-11-08.0.txt        rolled, not yet compressed
  orders.api-7d9f.2026-11-07.0.txt.gz     compressed
  orders-error.api-7d9f.txt               level file
  orders-audit.api-7d9f.txt               channel file
  .logchi/                                lock files
```

Compressed files work with standard tools: `zgrep 7f3a9c logs/orders.*`.

## Running several instances

Each process writes only to files carrying its own instance id, which defaults to the pod name in Kubernetes. If
two processes end up with the same id, such as two JVMs on one machine, an OS file lock detects it and the later
one adds its pid. Instances never share a file.

With `scope: shared`, each instance also compresses and expires files left by instances that no longer exist,
which matters when Kubernetes replaces pods on a shared volume. A lock file stops instances duplicating that work.

File locks can be unreliable on network file systems. logchi stays safe there because every retention step can be
repeated harmlessly, and a dead instance's active file is only touched after an hour of silence. Containers need
a mounted volume, or the files disappear with the container.

## Good to know

- **Logging never takes the application down.** If the disk fills or a file can't be written, events are counted,
  the file is retried, and a warning reports the number lost.
- **Under heavy load** the queue drops INFO and below when nearly full. WARN and ERROR wait for space instead.
- **The max-total cap is oldest-first across all sinks**, so a tight cap can delete old audit files. Size it for
  your longest retention.
- **Masking is pattern-based.** It catches common shapes such as `password=x`, JSON fields and card numbers, but
  it can't recognize a secret with no recognizable key. Use `@Mask` and avoid logging secrets in the first place.
- **WebFlux** gets a trace id in the response and the Reactor context. To see it in log lines, add
  `io.micrometer:context-propagation` and set `spring.reactor.context-propagation=auto`.
- **A custom `logback-spring.xml` keeps working.** logchi replaces console appenders so lines don't print twice,
  and leaves all others alone.

## Project layout

| Module                        | Purpose                                                       |
|-------------------------------|---------------------------------------------------------------|
| `logchi-core`                 | Appenders, encoders, retention, masking. Needs only Logback.  |
| `logchi-spring-common`        | Spring source shared by both starters. Not a published jar.   |
| `logchi-spring-boot3-starter` | That source compiled against Spring Boot 3.                   |
| `logchi-spring-boot4-starter` | That source compiled against Spring Boot 4.                   |
| `samples/`                    | A small application for each Boot version.                    |

Without Spring, call `LogchiInstaller.install(loggerContext, settings)`, and `loggerContext.stop()` on exit so
queued events are flushed.

## License

[Apache License 2.0](LICENSE)
