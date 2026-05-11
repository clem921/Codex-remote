# Codex Remote Android MVP

Application Android native Kotlin/Jetpack Compose pour piloter une instance Codex distante via Tailscale et un bridge WebSocket.

## Démarrage avec Tailscale

Sur la machine où Codex tourne :

```bash
tailscale ip -4

codex app-server \
  --listen ws://127.0.0.1:45213 \
  --ws-auth capability-token \
  --ws-token-file ./codex-ws-token.txt

cd bridge
npm install
MOBILE_TOKEN="un-token-long-et-secret" \
CODEX_WS="ws://127.0.0.1:45213" \
BRIDGE_HOST="0.0.0.0" \
BRIDGE_PORT="8080" \
npm start
```

Dans l'app Android :

```text
URL WebSocket : ws://ADRESSE_TAILSCALE:8080
Token mobile  : un-token-long-et-secret
```

## Générer l'APK

1. Va dans l'onglet **Actions**.
2. Lance **Build Android APK**.
3. Télécharge l'artifact **codex-remote-debug-apk**.
4. Installe `app-debug.apk` sur ton téléphone.

## Sécurité

Le bridge est conçu pour Tailscale/VPN. Pour une exposition Internet publique, utilise `wss://`, TLS, rotation de tokens et validation stricte des messages.
