# Local IM Mock Server

Simple Java/Netty mock server for local Android IM client testing.

## Run

```powershell
cd D:\Desktop\engine\IM\mock-server
mvn -q test
mvn -q exec:java
```

## Endpoints

- HTTP login: `POST http://127.0.0.1:8080/login`
- HTTP register: `POST http://127.0.0.1:8080/register`
- WebSocket: `ws://127.0.0.1:8080/ws`

Android emulator should use:

- HTTP: `http://10.0.2.2:8080`
- WebSocket: `ws://10.0.2.2:8080/ws`
