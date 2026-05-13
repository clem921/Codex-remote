import http from "node:http";
import { WebSocketServer, WebSocket } from "ws";

const host = process.env.BRIDGE_HOST || "127.0.0.1";
const port = Number(process.env.BRIDGE_PORT || "8080");
const codexUrl = process.env.CODEX_WS || "ws://127.0.0.1:45213";
const mobileToken = process.env.MOBILE_TOKEN;
const codexCwd = process.env.CODEX_CWD;

if (!mobileToken) {
  console.error("Missing MOBILE_TOKEN environment variable.");
  process.exit(1);
}

function sendJson(socket, value) {
  if (socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(value));
  }
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

  let nextId = 1;
  let activeThreadId = null;
  const pendingById = new Map();
  const queuedPrompts = [];
  const codex = new WebSocket(codexUrl);

  function request(method, params = {}, meta = {}) {
    const id = nextId++;
    pendingById.set(id, { method, meta });
    codex.send(JSON.stringify({ jsonrpc: "2.0", id, method, params }));
    return id;
  }

  function notify(method, params = {}) {
    codex.send(JSON.stringify({ jsonrpc: "2.0", method, params }));
  }

  function startThread() {
    request("thread/start", codexCwd ? { cwd: codexCwd } : {}, { reason: "new_thread" });
  }

  function startTurn(text, requestedThreadId = null) {
    const targetThreadId = requestedThreadId || activeThreadId;
    if (!targetThreadId) {
      queuedPrompts.push(text);
      sendJson(mobile, { type: "bridge_status", status: "prompt_queued" });
      return;
    }

    request("turn/start", {
      threadId: targetThreadId,
      input: [{ type: "text", text }]
    }, { threadId: targetThreadId });
  }

  function flushQueue() {
    while (activeThreadId && queuedPrompts.length > 0) {
      startTurn(queuedPrompts.shift(), activeThreadId);
    }
  }

  codex.on("open", () => {
    sendJson(mobile, { type: "bridge_status", status: "codex_connected" });
    request("initialize", {
      clientInfo: {
        name: "codex_remote_android",
        version: "0.2.0"
      }
    });
  });

  codex.on("message", raw => {
    const text = raw.toString();
    sendJson(mobile, { type: "codex_raw", data: text });

    let msg;
    try {
      msg = JSON.parse(text);
    } catch {
      return;
    }

    if (msg.id !== undefined && pendingById.has(msg.id)) {
      const pending = pendingById.get(msg.id);
      pendingById.delete(msg.id);

      if (msg.error) {
        sendJson(mobile, { type: "bridge_error", message: `${pending.method}: ${msg.error.message || JSON.stringify(msg.error)}` });
        return;
      }

      if (pending.method === "initialize") {
        notify("initialized");
        sendJson(mobile, { type: "bridge_status", status: "codex_initialized" });
        startThread();
        return;
      }

      if (pending.method === "thread/start") {
        activeThreadId = msg.result?.thread?.id || msg.result?.id || msg.result?.threadId || activeThreadId;
        sendJson(mobile, { type: "bridge_status", status: "thread_started", threadId: activeThreadId });
        flushQueue();
        return;
      }

      if (pending.method === "turn/start") {
        sendJson(mobile, { type: "bridge_status", status: "turn_started", threadId: pending.meta.threadId || activeThreadId });
      }
    }
  });

  codex.on("error", error => {
    sendJson(mobile, { type: "bridge_error", message: error.message });
  });

  codex.on("close", (code, reason) => {
    sendJson(mobile, { type: "bridge_status", status: "codex_closed", code, reason: reason.toString() });
  });

  mobile.on("message", raw => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      sendJson(mobile, { type: "bridge_error", message: "Invalid JSON from Android" });
      return;
    }

    if (msg.type === "hello") {
      sendJson(mobile, { type: "bridge_status", status: activeThreadId ? "ready" : "connecting" });
      return;
    }

    if (msg.type === "new_thread") {
      startThread();
      return;
    }

    if (msg.type === "select_thread" && typeof msg.threadId === "string") {
      activeThreadId = msg.threadId;
      sendJson(mobile, { type: "bridge_status", status: "thread_selected", threadId: activeThreadId });
      return;
    }

    if (msg.type === "user_prompt" && typeof msg.text === "string") {
      const targetThreadId = typeof msg.threadId === "string" ? msg.threadId : activeThreadId;
      startTurn(msg.text, targetThreadId);
      return;
    }

    sendJson(mobile, { type: "bridge_error", message: `Unsupported message: ${msg.type || "unknown"}` });
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
