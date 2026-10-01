# Yoin Apple Music developer-token service

Small Node.js service implementing the Android client's `GET /token` contract.
It issues ES256 JWTs with a one-hour lifetime, cached until five minutes before
expiry. The JWT identifies Yoin; it does not contain a subscriber's Music User
Token. No database or third-party runtime dependencies are required.

## Configure and run

Use Node 22 or newer. Keep Apple's downloaded `.p8` outside the repository,
readable only by the service account. Supply these environment variables through
the host's secret configuration:

- `APPLE_PRIVATE_KEY_PATH`: absolute path to the `.p8` file
- `APPLE_KEY_ID`: Apple's 10-character key ID
- `APPLE_TEAM_ID`: Apple's 10-character Team ID
- `PORT`: optional loopback port, defaults to 8788

Run `node server.mjs`. The service listens only on `127.0.0.1`.
Expose `/token` through the deployment host's HTTPS reverse proxy with request
rate limits. The Android Settings field takes that final HTTPS `/token` URL.
Do not log response bodies, developer tokens or private-key contents.
`GET /health` is a credential-free health probe; it is not an Apple API test.

Run `node --test server.test.mjs` for signature verification, rotation and HTTP
contract tests. These tests generate temporary keys in memory and do not use an
Apple account. Real verification still requires the registered MusicKit key and
an Apple API request.

Current status: Cloudflare Worker deployed with signing credentials stored as
Workers Secrets. The deployed `/token` response has been accepted by Apple
connectivity and catalog search endpoints (HTTP 200).

Apple configuration:
https://developer.apple.com/help/account/capabilities/create-a-media-identifier-and-private-key
https://developer.apple.com/documentation/applemusicapi/generating-developer-tokens

## Cloudflare deployment

`worker.mjs` and `wrangler.jsonc` implement the same `/token` response using
Cloudflare Secrets: `APPLE_PRIVATE_KEY`, `APPLE_KEY_ID`, `APPLE_TEAM_ID`.
The configured endpoint circuit breaker permits 300 requests per minute per
Cloudflare location; it is not per-user authentication or a global quota.
No private key or real token is included in the deployment bundle.

Run `npm ci`, `npm test`, and `npm run check` before deployment. Configure the
three Secrets through `wrangler secret bulk` using an input stream, then use
`wrangler deploy`. Never put the private key in command arguments or config files.
The repository can remain public: operators supply their own credentials and
endpoint. This endpoint is public and rate-limited, not authenticated; the
developer JWT can be obtained by callers, while the signing key stays private.
A developer JWT does not grant access to a subscriber account or subscription
playback; these require separate Apple user authorization.

2026-09-06 verification: the user's real MusicKit key successfully signed a
JWT accepted by Apple `/v1/test` and catalog song search (HTTP 200). The local
Node endpoint also returned a Token accepted by Apple. Six service tests, including a real local Workers runtime signature test, passed.
The initial deployment exposed a Node compatibility difference: `sign()` rejects
a `PrivateKeyObject` in options on Workers. Passing validated PEM fixes it; the
runtime regression test verifies the resulting ES256 signature independently.
Online `/health` and `/token` return 200; unsupported POST returns 405. The
deployed token passes Apple `/v1/test` and catalog search with HTTP 200. This does not verify subscriber access or
music playback. The real private key is retained outside the repository.
