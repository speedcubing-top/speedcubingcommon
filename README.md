# speedcubingCommon

used by scServer, scProxy

## How to (Build from Source)

### Requirements

- JDK 21
- Maven

### Steps

1. Clone this repository:
   ```bash
   git clone https://github.com/speedcubing-top/speedcubingCommon.git
   cd speedcubingCommon
   ```
2. Run the build script:
   ```bash
   ./build.sh
   ```
3. After building, you'll find the JAR located at:
   ```
   ./target/speedcubingCommon-1.0-SNAPSHOT.jar
   ```

## Runtime Config

`ServerConfig` normally reads `/storage/server.json`, but it also supports
container-friendly environment config:

1. `SC_SERVER_CONFIG_JSON` if present. This must contain the full JSON config.
2. `SC_CONFIG_SOURCE=env`, which builds config from env vars.
3. The configured file path, if readable.
4. Environment fallback if the file is missing or unreadable.

Env mode uses these primary variables:

```text
SC_DB_URL                 optional full JDBC URL; `%db%` is replaced per DB
SC_DB_HOST                default mariadb
SC_DB_PORT                default 3306
SC_DB_USER                default cubing
SC_DB_PASSWORD            default cubing
SC_DB_CONNECTION_TIMEOUT  default 30000
SC_DB_VALIDATION_TIMEOUT  default 5000
SC_DB_MAX_LIFETIME        default 1800000
SC_DB_MAX_POOL_SIZE       default 10
SC_DB_MIN_IDLE            default 2
SC_REDIS_HOST             default redis
SC_REDIS_PORT             default 6379
SC_REDIS_PASSWORD         default cubing-redis
SC_BROADCAST_MESSAGE      default Welcome to speedcubing.top!
```

The local `docker-stacks/sc-minecraft` stack uses `SC_CONFIG_SOURCE=env` and
does not mount `/storage/server.json`.
