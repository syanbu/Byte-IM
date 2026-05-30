# Local IM Mock Server

Simple Java/Netty mock server for local Android IM client testing.

## Run

```powershell
cd D:\Desktop\engine\IM\mock-server
mvn -q test
.\start-mock-server.ps1
```

On Windows, `start-mock-server.ps1` loads local OSS variables from
`.env.local.ps1` before starting the server. Keep real AccessKey values in
`.env.local.ps1`; use `.env.local.example.ps1` as the template.

## Endpoints

- HTTP login: `POST http://127.0.0.1:8080/login`
- HTTP register: `POST http://127.0.0.1:8080/register`
- WebSocket: `ws://127.0.0.1:8080/ws`

Register before login. Accounts are mainland China phone numbers.

Example:

```powershell
Invoke-RestMethod `
  -Uri "http://127.0.0.1:8080/register" `
  -Method Post `
  -ContentType "application/json" `
  -Body '{"phone":"13800113800","password":"123456"}'

Invoke-RestMethod `
  -Uri "http://127.0.0.1:8080/login" `
  -Method Post `
  -ContentType "application/json" `
  -Body '{"phone":"13800113800","password":"123456"}'
```

The server stores users in a local SQLite database at `mock-server/data/mock-im-users.sqlite`.
It stores the phone number, a random salt, and a PBKDF2-SHA256 password hash.
It does not store plaintext passwords.

The WebSocket mock server also stores:

- conversation `serverSeq` state in `mock-server/data/mock-im-sequences.sqlite`
- accepted chat messages plus receiver delivery state in `mock-server/data/mock-im-messages.sqlite`

That means sender duplicate-send idempotency and receiver offline redelivery now survive mock-server restart.

Successful login/register responses include a signed local mock access JWT,
`accessExpiresAt`, a refresh token, and `refreshExpiresAt`. Access tokens are
valid for 15 minutes; refresh tokens are valid for 7 days and are stored server
side only as SHA-256 hashes. WebSocket `AUTH` rejects legacy tokens, invalid
tokens, and expired access tokens.

Android emulator should use:

- HTTP: `http://10.0.2.2:8080`
- WebSocket: `ws://10.0.2.2:8080/ws`

USB-connected real devices can use adb reverse instead:

```powershell
adb reverse tcp:8080 tcp:8080
```

Then configure the Android app with:

- HTTP host: `127.0.0.1`
- Port: `8080`
