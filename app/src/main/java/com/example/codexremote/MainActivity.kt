package com.example.codexremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CodexRemoteApp()
                }
            }
        }
    }
}

enum class AppTab(val label: String) {
    Chat("Chat"),
    Threads("Threads"),
    Settings("Réglages"),
    Logs("Logs")
}

@Composable
fun CodexRemoteApp(vm: CodexRemoteViewModel = viewModel()) {
    var selectedTab by remember { mutableStateOf(AppTab.Chat) }

    Column(modifier = Modifier.fillMaxSize()) {
        Header(vm = vm)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (selectedTab) {
                AppTab.Chat -> ChatScreen(vm)
                AppTab.Threads -> ThreadsScreen(vm)
                AppTab.Settings -> SettingsScreen(vm)
                AppTab.Logs -> LogsScreen(vm)
            }
        }

        NavigationBar {
            AppTab.values().forEach { tab ->
                NavigationBarItem(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    icon = { Text(tab.label.take(1)) },
                    label = { Text(tab.label) }
                )
            }
        }
    }
}

@Composable
fun Header(vm: CodexRemoteViewModel) {
    val status by vm.status.collectAsState()
    val threads by vm.threads.collectAsState()
    val selectedThreadId by vm.selectedThreadId.collectAsState()
    val currentThread = threads.firstOrNull { it.localId == selectedThreadId }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Codex Remote", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = currentThread?.title ?: "Aucun thread",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            StatusPill(status)
        }
        Divider()
    }
}

@Composable
fun StatusPill(status: ConnectionStatus) {
    val label = when (status) {
        ConnectionStatus.Disconnected -> "Déconnecté"
        ConnectionStatus.Connecting -> "Connexion"
        ConnectionStatus.Connected -> "Connecté"
        ConnectionStatus.Ready -> "Prêt"
        ConnectionStatus.Error -> "Erreur"
    }

    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun ChatScreen(vm: CodexRemoteViewModel) {
    val prompt by vm.prompt.collectAsState()
    val status by vm.status.collectAsState()
    val messages by vm.messages.collectAsState()
    val selectedThreadId by vm.selectedThreadId.collectAsState()
    val visibleMessages = messages.filter { it.threadLocalId == selectedThreadId }
    val connected = status == ConnectionStatus.Connected || status == ConnectionStatus.Ready

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (visibleMessages.isEmpty()) {
                item {
                    EmptyState(
                        title = "Aucun message",
                        subtitle = "Connecte le bridge, puis envoie un prompt à Codex."
                    )
                }
            }
            items(visibleMessages, key = { it.id }) { message ->
                MessageCard(message)
            }
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = vm::setPrompt,
            label = { Text("Prompt") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = vm::sendPrompt,
                enabled = connected && prompt.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                Text("Envoyer")
            }
            Button(onClick = vm::createThread) {
                Text("Nouveau")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
fun MessageCard(message: ChatMessage) {
    val label = when (message.role) {
        "user" -> "Moi"
        "assistant" -> "Codex"
        else -> "Système"
    }
    val time = remember(message.timestamp) {
        SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(message.timestamp))
    }
    val background = when (message.role) {
        "user" -> MaterialTheme.colorScheme.primaryContainer
        "assistant" -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.secondaryContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = background)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text(time, style = MaterialTheme.typography.labelSmall)
            }
            Text(message.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun ThreadsScreen(vm: CodexRemoteViewModel) {
    val threads by vm.threads.collectAsState()
    val selectedThreadId by vm.selectedThreadId.collectAsState()
    val messages by vm.messages.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(onClick = vm::createThread, modifier = Modifier.fillMaxWidth()) {
            Text("Créer un nouveau thread")
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(threads, key = { it.localId }) { thread ->
                val count = messages.count { it.threadLocalId == thread.localId }
                ThreadCard(
                    thread = thread,
                    selected = thread.localId == selectedThreadId,
                    count = count,
                    onClick = { vm.selectThread(thread.localId) }
                )
            }
        }
    }
}

@Composable
fun ThreadCard(thread: RemoteThread, selected: Boolean, count: Int, onClick: () -> Unit) {
    val updated = remember(thread.updatedAt) {
        SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date(thread.updatedAt))
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(thread.title, style = MaterialTheme.typography.titleMedium)
                Text("$count msg", style = MaterialTheme.typography.labelMedium)
            }
            Text("Mis à jour: $updated", style = MaterialTheme.typography.bodySmall)
            Text("Codex: ${thread.remoteId ?: "non associé"}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}

@Composable
fun SettingsScreen(vm: CodexRemoteViewModel) {
    val url by vm.url.collectAsState()
    val token by vm.token.collectAsState()
    val status by vm.status.collectAsState()
    val connected = status == ConnectionStatus.Connected || status == ConnectionStatus.Ready

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Connexion", style = MaterialTheme.typography.titleLarge)
        Text(
            "Ces réglages sont sauvegardés localement sur le téléphone.",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = url,
            onValueChange = vm::setUrl,
            label = { Text("URL WebSocket du bridge") },
            placeholder = { Text("ws://100.x.y.z:8080") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = token,
            onValueChange = vm::setToken,
            label = { Text("Token mobile") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )

        Button(onClick = vm::saveSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Sauvegarder les identifiants")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::connect, enabled = !connected, modifier = Modifier.weight(1f)) {
                Text("Connecter")
            }
            Button(onClick = vm::disconnect, enabled = connected, modifier = Modifier.weight(1f)) {
                Text("Déconnecter")
            }
        }

        Text(
            "Conseil: garde le bridge accessible seulement via Tailscale/VPN pour cette version.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun LogsScreen(vm: CodexRemoteViewModel) {
    val log by vm.log.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Journal brut", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = vm::clearLog) {
                Text("Effacer")
            }
        }
        Text(
            text = log.ifBlank { "Aucun log." },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(12.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun EmptyState(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
    }
}
