import http from "node:http";
import { WebSocketServer, WebSocket } from "ws";

const host = process.env.BRIDGE_HOST || "127.0.0.1";
const port = Number(process.env.BRIDGE_PORT || "8080");
const codexUrl = process.env.CODEX_WS || "ws://127.0.0.1:45213";
const mobileToken = process.env.MOBILE_TOKEN;

if (!mobileToken) {
  console.error("Missing MOBILE_TOKEN environment variable.");
  process.exit(1);
}

const server = http.createServer((req, res) => {
  res.writeHead(200, { "content-type": "text/plain" });
  res.end("Codex Remote Bridge OK\n");
});

const wss = new WebSocketServer({ server });

wss.on("connection", (mobile, req) => {
  const auth = req.headers.authorization || "";
  if (auth !== `Bearer ${mobileToken}`) {
    mobile.close(1008, "unauthorized");
    return;
  }

  console.log("Android client connected");
  const codex = new WebSocket(codexUrl);

  codex.on("open", () => {
    mobile.send(JSON.stringify({ type: "bridge_status", status: "codex_connected" }));
  });

  codex.on("message", message => {
    if (mobile.readyState === WebSocket.OPEN) {
      mobile.send(message.toString());
    }
  });

  codex.on("error", error => {
    if (mobile.readyState === WebSocket.OPEN) {
      mobile.send(JSON.stringify({ type: "bridge_error", message: error.message }));
    }
  });

  codex.on("close", (code, reason) => {
    if (mobile.readyState === WebSocket.OPEN) {
      mobile.send(JSON.stringify({
        type: "bridge_status",
        status: "codex_closed",
        code,
        reason: reason.toString()
      }));
    }
  });

  mobile.on("message", message => {
    if (codex.readyState === WebSocket.OPEN) {
      codex.send(message.toString());
    } else if (mobile.readyState === WebSocket.OPEN) {
      mobile.send(JSON.stringify({ type: "bridge_error", message: "Codex socket is not open" }));
    }
  });

  mobile.on("close", () => {
    console.log("Android client disconnected");
    codex.close(1000, "mobile disconnected");
  });
});

server.listen(port, host, () => {
  console.log(`Codex Remote Bridge listening on ${host}:${port}`);
  console.log(`Forwarding to ${codexUrl}`);
});
