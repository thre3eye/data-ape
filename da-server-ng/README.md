# Data-Ape Server NG

## Status and motivation

`da-server-ng` is a side-by-side, **read-only** port of the legacy Data-Ape backend, intended for development and the explicitly approved LAN deployment. It retains the recognizable `DataApeServer`, `MongoDriver`, and `JsonHelper` responsibilities and the legacy read response shapes while fixing the unfiltered-page bug. The legacy `da-server` and `da-frontend` are unchanged; a separate `da-frontend-ng` is available.

This is not ready for exposure to untrusted users. Application-level authentication, TLS, CSRF defenses, effective network egress enforcement, and production deployment controls remain open. Writes are always rejected.

## Architecture and dependencies

| Component | Responsibility |
| :--- | :--- |
| `DataApeServer` | Javalin routes, static-file configuration, HTTP sessions, configuration reload and shutdown. |
| `MongoDriver` | One MongoDB client and metadata cache per session, connection discovery, filtered reads, expiration and cleanup. |
| `JsonHelper` | Validates legacy selection, sorting and pagination; converts supported predicates to BSON. |

The service targets Java 21 and uses Javalin 7.2.4, MongoDB sync driver 5.14.0, Jackson 2.19.4, Commons BeanUtils 1.11.0 for properties-file loading, and the AlphaTrade `at-mongo-ng` query builder. The direct driver dependency overrides the 5.3.1 version used to build the current AlphaTrade NG snapshot; the resolved driver, core and BSON artifacts must remain version-aligned. `ATMongoWhereQuery` handles shared comparisons and quoted-text predicates; driver BSON filters handle existence and exclusion. The adapter creates MongoDB clients directly: `ATMongoDao` is bound to one database and exposes raw access outside its write guard, while `ATMongoConfig` can override URI pool/socket options. Neither is used as a generic session client factory.

The `1.0-SNAPSHOT` AlphaTrade dependencies and their parent metadata must be installed locally for development. Releases require immutable versions published to an approved internal registry and a recorded source revision. No Kafka or AlphaTrade services are required.

## HTTP and safety boundaries

* `POST /connect` accepts JSON `{"cstr":"mongodb://host:port/database"}` and returns `dbName`, `tables`, and `decimaldigits`. Standard `mongodb://` URIs require a database; SRV URIs are not supported. Credentials in the URL query string are rejected. The legacy frontend sends the URI there, so it cannot connect unchanged.
* `GET /tables/{database}` returns discovered collections and sampled field types. `GET /data/{database}/{table}` returns the legacy column-oriented data wrapper and accepts `select`, `sort`, `page`, `page_size`, and `count_total`.
* `PUT /save/{database}/{table}` and `DELETE /delete/{database}/{table}/{id}` return 403. No write-capability switch is implemented.
* The server binds to `127.0.0.1` by default. `server.bind.host` can explicitly override that address; the container configuration uses `0.0.0.0` to accept Docker-published traffic. Loopback alone is not authentication.
* `mongo.allowed.hosts` must list every exact URI seed host and port. It is not a security boundary against DNS rebinding or replica-set discovery of other hosts; enforce egress independently and do not assume it authorizes a user or database.
* Invalid filters are rejected rather than ignored. Pages are one-based, page size is limited to 200, and offsets must fit the MongoDB driver's integer API. `querySize=-1` means counting was disabled; `dataSize` reflects the fetched rows. Counts and reads are separate operations, not an atomic snapshot.
* Per-session locks protect clients from reconnect or expiry during reads. A candidate replaces an existing client only after discovery succeeds. These safeguards do not replace authentication or MongoDB role restrictions.

## Configuration and running

`DataApeServer` accepts one argument: a comma-separated list of properties files, with later files taking precedence. Configuration reloads every 30 seconds; static-file mappings and the listen port are selected at startup.

```properties
server.port=4100
mongo.allowed.hosts=127.0.0.1:27017
mongo.field.scan.limit=2500
session.timeout.interval=900
session.cleanup.interval=900
ui.decimaldigits=2
```

Only use explicitly approved isolated targets. Optional `server.static.files`/`server.static.path` and `server.config.files`/`server.config.path` configure external static directories and their hosted paths. Keep credentials out of configuration committed to source control.

The main class is `com.isoplane.dataape.ng.DataApeServer`. Maven's ordinary package output is a thin JAR, not a self-contained executable distribution; running it requires its runtime dependencies on the classpath.

## Container

Build the frontend and backend, copy runtime dependencies, then build the image from the Data-Ape repository root:

```bash
cd da-frontend-ng && npm ci && npm run build
cd ../da-server-ng
mvn clean package org.apache.maven.plugins:maven-dependency-plugin:3.2.0:copy-dependencies -DincludeScope=runtime
cd ..
docker build -f da-server-ng/Dockerfile -t da-server-ng:1.0.0-SNAPSHOT .
```

The image includes the NG frontend and Java 21 runtime. Its command reads the existing legacy properties file first and then `container.properties`, so the image's listener, static-file paths and allowed-target setting take precedence. `JAVA_TOOL_OPTIONS` supplies `da.mongo.allowed.hosts`; the checked-in Compose file sets it to `mongo-db:27017`. Adjust it for the intended MongoDB seed hosts without putting credentials in it. The existing Data-Ape configuration mount is read-only. Logs go to container output.

Compose does not publish a host port. Caddy reaches the service on `alphatrade_net` at its **container port** 4100, not the former host port 888. Add this site to the Caddy stack and redeploy that stack:

```caddyfile
http://ape.lan.isoplane.com {
    import lan_protected
    reverse_proxy data-ape-ng:4100 {
        header_up -Authorization
    }
}
```

The existing `lan_protected` policy exempts `192.168.8.188` from basic authentication. HTTP does not encrypt either basic-auth credentials or MongoDB connection strings; use only on a trusted network. Removing the host port prevents direct host-port bypass, but other containers on `alphatrade_net` can still reach Data-Ape directly. Caddy does not supply CSRF protection or MongoDB egress enforcement.

Existing browsers may retain an old service worker when reusing an origin; verify the UI after cutover and clear site data if an old frontend remains active. Retain the legacy container/image and its previous Compose definition for rollback. Portainer must be pointed to the updated Data-Ape repository file and redeployed separately; a redeploy of the old stack can restore the legacy service.

For the retained legacy container on this Docker host, an operator can roll back without deleting either container:

```bash
docker update --restart=no data-ape-ng
docker stop data-ape-ng
docker start data-ape
```

The retained legacy container currently has restart policy `no`; set the desired policy separately if needed. To reach it through Caddy after rollback, change the upstream to `data-ape:4100` and redeploy the Caddy stack. Portainer's Data-Ape stack definition must also be coordinated with the desired version, or a later redeploy may undo the rollback. The local image and uncommitted repository changes are not a durable release artifact.

## Build and verification

With the NG development artifacts installed and a JDK 21 or later:

```bash
cd da-server-ng
mvn test
mvn package
```

The integration tests are skipped unless `DA_NG_TEST_MONGO_HOST` is explicitly supplied. They do not provision or clean a database. Only use a disposable, isolated MongoDB instance containing synthetic database `da_ng_fixture`, collection `sample`, with exactly these documents: `{"_id":"one","name":"Alpha","score":10}` and `{"_id":"two","name":"Beta","score":20}`. Then run:

```bash
DA_NG_TEST_MONGO_HOST=127.0.0.1:27017 mvn -Dtest=MongoDriverIntegrationTest test
```

Substitute the approved fixture's host and port; never point this test at a shared or production database. The test exercises direct driver reads and the HTTP-to-Mongo path. It does not establish authentication, authorization, replica-set, failure-injection, full contract, or production-readiness coverage. Remaining migration and release gates are tracked in AlphaTrade ADR-39.
