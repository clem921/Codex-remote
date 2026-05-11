package com.example.codexremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CodexRemoteScreen()
                }
            }
        }
    }
}

@Composable
fun CodexRemoteScreen(vm: CodexRemoteViewModel = viewModel()) {
    val url by vm.url.collectAsState()
    val token by vm.token.collectAsState()
    val prompt by vm.prompt.collectAsState()
    val connected by vm.connected.collectAsState()
    val log by vm.log.collectAsState()
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Codex Remote",
            style = MaterialTheme.typography.headlineMedium
        )

        OutlinedTextField(
            value = url,
            onValueChange = vm::setUrl,
            label = { Text("URL WebSocket du bridge") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = token,
            onValueChange = vm::setToken,
            label = { Text("Token mobile") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::connect, enabled = !connected) {
                Text("Connecter")
            }
            Button(onClick = vm::disconnect, enabled = connected) {
                Text("Déconnecter")
            }
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = vm::setPrompt,
            label = { Text("Prompt à envoyer à Codex") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )

        Button(onClick = vm::sendPrompt, enabled = connected && prompt.isNotBlank()) {
            Text("Envoyer")
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text("Journal brut", style = MaterialTheme.typography.titleMedium)
        Text(
            text = log,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scroll),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
