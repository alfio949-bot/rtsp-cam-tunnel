# RTSP Cam Tunnel

App Android (Kotlin) per visualizzare lo streaming RTSP di telecamere IP con:

1. **Tentativo automatico anonimo** — la app invia OPTIONS + DESCRIBE all'URL RTSP
   della camera (porta 554 di default) **senza credenziali**.
2. **Accesso condizionale**
   - risposta `200 OK` → il flusso parte subito;
   - risposta `401 Unauthorized` → il tentativo automatico si interrompe.
3. **Fallback credenziali** — su 401 apre un dialog Username/Password.
4. **Connessione autenticata** — l'URL viene rigenerato come
   `rtsp://utente:password@ip:porta/` (credenziali URL-encoded, autenticazione
   Basic/Digest gestita da media3); credenziali errate → messaggio d'errore.
5. **Mascheramento dell'IP del dispositivo** — tutto il traffico (handshake RTSP
   **e** flusso RTP, forzato su TCP interleaved) passa per un proxy
   **SOCKS5** o **HTTP CONNECT** configurabile. La camera vede solo l'IP del proxy.
   Con la casella «Modalità privacy obbligatoria» attiva l'app **rifiuta** di
   connettersi senza proxy.

## Permessi (AndroidManifest.xml)
- `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`
- `networkSecurityConfig` con `cleartextTrafficPermitted="true"` (RTSP non è HTTPS)

## Dipendenze (Gradle)
- `androidx.media3:media3-exoplayer`, `media3-rtsp`, `media3-ui` (player + RTSP + PlayerView)
- `kotlinx-coroutines-android` (probe su thread IO)
- AndroidX base (appcompat, material, constraintlayout)

## Come funziona il tunnel
- `TunnelSocketFactory` (javax.net) iniettata in `RtspProbe` **e** in
  `RtspMediaSource.Factory().setSocketFactory(...)`, con `setForceUseRtpTcp(true)`
  così anche i dati RTP viaggiano sullo stesso socket TCP del tunnel: nessun
  traffico può bypassare il proxy.
- SOCKS5: `java.net.Socket(Proxy.Type.SOCKS, ...)`; gli endpoint vengono passati
  **non risolti** (`InetSocketAddress.createUnresolved`) così la risoluzione DNS
  avviene sul proxy (niente DNS leak).
- HTTP CONNECT: `HttpConnectTunnelSocket` intercetta `connect()`, apre il tunnel
  con `CONNECT host:porta HTTP/1.1` e verifica la risposta `200`.

## Serve un proxy
La app non include un server proxy: serve un proprio server (es. VPS):
- `ssh -D 1080 utente@vps` (crea un SOCKS5 dinamico sulla porta 1080)
- dante/3proxy/microsocks su una VPS
- Piattaforme proxy aziendali (SOCKS5 o HTTP CONNECT)

## Build
`gradle :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
(firmato con il keystore di debug, installabile su qualsiasi Android).
