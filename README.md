# logchi

File logging for Spring Boot that works the moment you add the dependency. Built on SLF4J and Logback, so
your existing `log.info(...)` calls and every library's logs flow through it unchanged.

- Rotation by time and size, gzip compression, separate retention for hot and cold files
- Secrets masked before they reach disk
- Every line carries the request's trace id and user
- `@Log` and `@Logged` remove logging boilerplate
- Text or JSON output
- Safe with many instances sharing a directory

## Install

Build it into your local `~/.m2`:

```bash
./mvnw install -DskipTests
```

Then add the starter that matches your Spring Boot version.

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

## Releasing

### Maven Central

One-time setup:

1. Sign in at https://central.sonatype.com with the `rbaxtiyorov` GitHub account. The `io.github.rbaxtiyorov`
   namespace is verified automatically.
2. Create a GPG key and publish its public half: `gpg --full-generate-key`, then
   `gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>`.
3. Generate a user token in the Portal and add it to `~/.m2/settings.xml`:

   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>TOKEN_USERNAME</username>
         <password>TOKEN_PASSWORD</password>
       </server>
     </servers>
   </settings>
   ```

Each release:

```bash
./mvnw clean deploy -Prelease -DskipUpload -Dgpg.skip   # dry run: builds target/central-publishing/central-bundle.zip
./mvnw clean deploy -Prelease                           # signs, uploads and validates
```

The upload stays unpublished until it is published, or dropped, under Deployments in the Portal. A version on Maven
Central can never be changed or deleted, so bump `<version>` in every POM before each release. The samples are
never published.

### GitHub Packages

Pushing a tag like `v0.1.0` runs the CI workflow, which publishes to
`https://maven.pkg.github.com/rbaxtiyorov/logchi`. To consume it, add that URL as a repository and put a token with
`read:packages` in `~/.m2/settings.xml` under a matching server id.

## License

[Apache License 2.0](LICENSE)
