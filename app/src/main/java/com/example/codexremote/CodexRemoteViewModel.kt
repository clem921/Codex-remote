package com.example.codexremote

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class CodexRemoteViewModel : ViewModel() {
    private val client = OkHttpClient()
    private var socket: WebSocket? = null

    private val _url = MutableStateFlow("ws://100.x.x.x:8080")
    val url: StateFlow<String> = _url

    private val _token = MutableStateFlow("")
    val token: StateFlow<String> = _token

    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _log = MutableStateFlow("Prêt. Configure l'URL Tailscale du bridge et ton token mobile.\n")
    val log: StateFlow<String> = _log

    fun setUrl(value: String) {
        _url.value = value
    }

    fun setToken(value: String) {
        _token.value = value
    }

    fun setPrompt(value: String) {
        _prompt.value = value
    }

    fun connect() {
        disconnect()

        val request = Request.Builder()
            .url(_url.value)
            .addHeader("Authorization", "Bearer ${_token.value}")
            .build()

        appendLog("Connexion à ${_url.value}...")

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connected.value = true
                appendLog("Connecté au bridge.")
                webSocket.send("{\"type\":\"hello\",\"client\":\"android\"}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                appendLog("← $text")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connected.value = false
                appendLog("Erreur: ${t.message ?: t.javaClass.simpleName}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connected.value = false
                appendLog("Déconnecté: $code $reason")
            }
        })
    }

    fun sendPrompt() {
        val text = _prompt.value.trim()
        if (text.isEmpty()) return

        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")

        val payload = "{\"type\":\"user_prompt\",\"text\":\"$escaped\"}"
        val sent = socket?.send(payload) == true
        if (sent) {
            appendLog("→ $payload")
            _prompt.value = ""
        } else {
            appendLog("Impossible d'envoyer: socket non connectée.")
        }
    }

    fun disconnect() {
        socket?.close(1000, "bye")
        socket = null
        _connected.value = false
    }

    private fun appendLog(line: String) {
        _log.value = _log.value + line + "\n"
    }

    override fun onCleared() {
        disconnect()
        client.dispatcher.executorService.shutdown()
        super.onCleared()
    }
}
