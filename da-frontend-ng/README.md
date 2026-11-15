# Data-Ape Frontend NG

## Status and architecture

This is a side-by-side copy of the legacy Angular frontend, adapted for `da-server-ng`. The legacy frontend is unchanged. Angular 13 and the existing component/module structure are intentionally retained for this first increment; the framework upgrade and standalone components are separate migration work.

The connection form accepts the full standard MongoDB URI, including credentials, and sends it only in the JSON body of `POST /connect`. `DaMongoService` uses the session established by the backend for collection discovery, reads and writes. Editing and deletion are exposed only when the backend issues a write token; the backend remains the enforcement point. Edits use complete canonical Extended JSON documents and reject stale replacements. Deletion is transactional by default; the explicitly configured standalone archive-first mode is non-atomic and errors may require verification of both collections. Nonempty string and BSON ObjectId `_id` values are supported for editing and deletion.

Both frontend and backend must be reachable through the same browser origin so the session cookie accompanies subsequent requests. The Angular development server proxies `/connect`, `/tables`, `/data`, `/document`, `/save`, `/delete`, and `/config` to `127.0.0.1:4100`; it must run on the same host as the backend. If writes are enabled, configure the backend's `dataape.write.origin` to match the browser origin exactly. A web server or reverse proxy must similarly serve the frontend and forward these paths to the backend. The backend binds to loopback by default; a proxy in a separate container needs an explicitly configured wider bind address.

The UI defaults to dark mode. Expand Settings in the footer to select Light or Dark; the preference is stored in the browser's local storage for this origin.

The service worker configuration is inherited from the legacy app. Do not deploy both frontends to the same origin and scope without planning and testing service-worker updates and rollback.

## Dependencies and development

The lockfile contains Angular 13.2.2, TypeScript 4.5.5 and `angular-split` 13.1.0. Use a Node/npm version supported by Angular 13 (Node 14.15+ or 16.10+, within those major versions). Framework and dependency upgrades are deferred.

```bash
cd da-frontend-ng
npm ci
npm start
```

The development server defaults to `http://localhost:4200`; use `npm start -- --host 0.0.0.0` only when intentionally exposing it on the LAN. Start `da-server-ng` separately on its default port and configure `mongo.allowed.hosts` to include the URI's exact MongoDB seed host and port. The backend requires a database in the URI and does not support `mongodb+srv://`. Consult the backend README for its configuration and limitations.

## Tests and build

```bash
npm test -- --watch=false --browsers=ChromeHeadless --ts-config=tsconfig.connection-spec.json --include=src/app/services/da-mongo.service.spec.ts --include=src/app/da-data/da-data.component.spec.ts --include=src/app/services/da-theme.service.spec.ts
npm run build
```

The focused tests check the connection request, failure handling, write token and write-control visibility. Their separate TypeScript configuration avoids compiling unrelated legacy specs. A Chrome-compatible browser is required for Karma. Build output is in `dist/data-ape`.

This development port has not been approved for production. HTTP on a LAN exposes the submitted credentials in transit; use an appropriately configured TLS proxy if protection is required. Authentication, egress enforcement and backend production readiness remain separate tasks. Do not place real credentials in configuration, logs, tests or documentation.
