package com.example.codexremote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class RemoteThread(
    val localId: String,
    val title: String,
    val remoteId: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val threadLocalId: String,
    val role: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ConnectionStatus {
    Disconnected,
    Connecting,
    Connected,
    Ready,
    Error
}

class CodexRemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("codex_remote_settings", Application.MODE_PRIVATE)
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private var socket: WebSocket? = null

    private val defaultThread = RemoteThread(localId = UUID.randomUUID().toString(), title = "Thread 1")

    private val _url = MutableStateFlow(prefs.getString("bridge_url", "ws://100.x.x.x:8080") ?: "ws://100.x.x.x:8080")
    val url: StateFlow<String> = _url

    private val _token = MutableStateFlow(prefs.getString("mobile_token", "") ?: "")
    val token: StateFlow<String> = _token

    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt

    private val _status = MutableStateFlow(ConnectionStatus.Disconnected)
    val status: StateFlow<ConnectionStatus> = _status

    private val _threads = MutableStateFlow(listOf(defaultThread))
    val threads: StateFlow<List<RemoteThread>> = _threads

    private val _selectedThreadId = MutableStateFlow(defaultThread.localId)
    val selectedThreadId: StateFlow<String> = _selectedThreadId

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _log = MutableStateFlow("Prêt. Configure le bridge dans Réglages.\n")
    val log: StateFlow<String> = _log

    val connected: Boolean
        get() = _status.value == ConnectionStatus.Connected || _status.value == ConnectionStatus.Ready

    fun setUrl(value: String) {
        _url.value = value.trim()
    }

    fun setToken(value: String) {
        _token.value = value.trim()
    }

    fun saveSettings() {
        prefs.edit()
            .putString("bridge_url", _url.value.trim())
            .putString("mobile_token", _token.value.trim())
            .apply()
        appendLog("Réglages sauvegardés.")
    }

    fun setPrompt(value: String) {
        _prompt.value = value
    }

    fun selectThread(localId: String) {
        _selectedThreadId.value = localId
        val remoteId = _threads.value.firstOrNull { it.localId == localId }?.remoteId
        if (connected && remoteId != null) {
            sendRaw("{\"type\":\"select_thread\",\"threadId\":\"${escapeJson(remoteId)}\"}")
        }
    }

    fun createThread() {
        val threadNumber = _threads.value.size + 1
        val thread = RemoteThread(localId = UUID.randomUUID().toString(), title = "Thread $threadNumber")
        _threads.value = listOf(thread) + _threads.value
        _selectedThreadId.value = thread.localId
        _messages.value = _messages.value + ChatMessage(
            threadLocalId = thread.localId,
            role = "system",
            text = "Nouveau thread local créé. Le bridge va demander un nouveau thread Codex."
        )
        if (connected) {
            sendRaw("{\"type\":\"new_thread\"}")
        }
    }

    fun connect() {
        disconnect()
        _status.value = ConnectionStatus.Connecting

        val request = Request.Builder()
            .url(_url.value)
            .addHeader("Authorization", "Bearer ${_token.value}")
            .build()

        appendLog("Connexion à ${_url.value}...")

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _status.value = ConnectionStatus.Connected
                appendLog("Connecté au bridge.")
                webSocket.send("{\"type\":\"hello\",\"client\":\"android\"}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncoming(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _status.value = ConnectionStatus.Error
                appendLog("Erreur: ${t.message ?: t.javaClass.simpleName}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _status.value = ConnectionStatus.Disconnected
                appendLog("Déconnecté: $code $reason")
            }
        })
    }

    fun sendPrompt() {
        val text = _prompt.value.trim()
        if (text.isEmpty()) return

        val localThreadId = _selectedThreadId.value
        val remoteThreadId = _threads.value.firstOrNull { it.localId == localThreadId }?.remoteId
        val remotePart = remoteThreadId?.let { ",\"threadId\":\"${escapeJson(it)}\"" } ?: ""
        val payload = "{\"type\":\"user_prompt\",\"text\":\"${escapeJson(text)}\"$remotePart}"

        if (sendRaw(payload)) {
            _messages.value = _messages.value + ChatMessage(
                threadLocalId = localThreadId,
                role = "user",
                text = text
            )
            _prompt.value = ""
            touchThread(localThreadId, text.take(42).ifBlank { null })
        } else {
            appendLog("Impossible d'envoyer: socket non connectée.")
        }
    }

    fun disconnect() {
        socket?.close(1000, "bye")
        socket = null
        _status.value = ConnectionStatus.Disconnected
    }

    fun clearLog() {
        _log.value = ""
    }

    private fun sendRaw(payload: String): Boolean {
        appendLog("→ $payload")
        return socket?.send(payload) == true
    }

    private fun handleIncoming(text: String) {
        appendLog("← $text")

        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val type = root.string("type")

        when (type) {
            "bridge_status" -> handleBridgeStatus(root)
            "bridge_error" -> {
                _status.value = ConnectionStatus.Error
                addSystemMessage("Erreur bridge: ${root.string("message") ?: text}")
            }
            "codex_raw" -> handleCodexRaw(root.string("data") ?: return)
            else -> handleCodexRaw(text)
        }
    }

    private fun handleBridgeStatus(root: JsonObject) {
        val statusText = root.string("status") ?: "status"
        appendLog("Status bridge: $statusText")
        if (statusText == "codex_initialized" || statusText == "thread_started" || statusText == "ready") {
            _status.value = ConnectionStatus.Ready
        }
        if (statusText == "thread_started") {
            val remoteId = root.string("threadId")
            if (remoteId != null) {
                bindCurrentThread(remoteId)
            }
        }
        addSystemMessage("Bridge: $statusText")
    }

    private fun handleCodexRaw(raw: String) {
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        if (element == null) {
            if (raw.isNotBlank()) addAssistantMessage(raw)
            return
        }

        val obj = element as? JsonObject
        if (obj != null) {
            val method = obj.string("method")
            if (method != null) appendLog("Méthode Codex: $method")

            val threadId = findFirstString(obj, setOf("threadId", "thread_id", "id"))
            if ((method?.contains("thread", ignoreCase = true) == true) && threadId != null) {
                bindCurrentThread(threadId)
            }
        }

        val extracted = extractReadableText(element)
            .map { it.trim() }
            .filter { it.length > 1 }
            .distinct()
            .joinToString("\n\n")

        if (extracted.isNotBlank()) {
            addAssistantMessage(extracted)
        }
    }

    private fun bindCurrentThread(remoteId: String) {
        val localId = _selectedThreadId.value
        _threads.value = _threads.value.map {
            if (it.localId == localId) it.copy(remoteId = remoteId, updatedAt = System.currentTimeMillis()) else it
        }
    }

    private fun touchThread(localId: String, titleCandidate: String?) {
        _threads.value = _threads.value.map {
            if (it.localId == localId) {
                val newTitle = if (it.title.startsWith("Thread ") && !titleCandidate.isNullOrBlank()) titleCandidate else it.title
                it.copy(title = newTitle, updatedAt = System.currentTimeMillis())
            } else it
        }.sortedByDescending { it.updatedAt }
    }

    private fun addAssistantMessage(text: String) {
        val localId = _selectedThreadId.value
        _messages.value = _messages.value + ChatMessage(
            threadLocalId = localId,
            role = "assistant",
            text = text
        )
        touchThread(localId, null)
    }

    private fun addSystemMessage(text: String) {
        _messages.value = _messages.value + ChatMessage(
            threadLocalId = _selectedThreadId.value,
            role = "system",
            text = text
        )
    }

    private fun appendLog(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date())
        _log.value = _log.value + "[$time] $line\n"
    }

    private fun extractReadableText(element: JsonElement): List<String> {
        val out = mutableListOf<String>()

        fun walk(e: JsonElement, key: String?) {
            when (e) {
                is JsonPrimitive -> {
                    val value = e.contentOrNull ?: return
                    if (e.booleanOrNull != null) return
                    val usefulKey = key in setOf("text", "message", "content", "delta", "output", "response", "summary")
                    if (usefulKey && value.isNotBlank()) out += value
                }
                is JsonArray -> e.forEach { walk(it, key) }
                is JsonObject -> e.forEach { (k, v) -> walk(v, k) }
            }
        }

        walk(element, null)
        return out
    }

    private fun findFirstString(obj: JsonObject, keys: Set<String>): String? {
        for ((k, v) in obj) {
            if (k in keys && v is JsonPrimitive) return v.contentOrNull
            if (v is JsonObject) {
                val nested = findFirstString(v, keys)
                if (nested != null) return nested
            }
            if (v is JsonArray) {
                v.forEach { item ->
                    if (item is JsonObject) {
                        val nested = findFirstString(item, keys)
                        if (nested != null) return nested
                    }
                }
            }
        }
        return null
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun escapeJson(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    override fun onCleared() {
        disconnect()
        client.dispatcher.executorService.shutdown()
        super.onCleared()
    }
}
