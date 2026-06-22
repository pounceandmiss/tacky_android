package com.example.tackyapk.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tackyapk.TackyClient
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Raw protocol console for development: type "<module> <method> [json-args]" to
 * issue a request and watch events/results stream in. Kept as a route behind the
 * Accounts screen.
 */
@Composable
fun ConsoleScreen(client: TackyClient, state: StateFlow<String>) {
    val backendState by state.collectAsStateWithLifecycle()
    val lines = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    val pretty = remember { GsonBuilder().setPrettyPrinting().create() }
    var input by remember { mutableStateOf("") }

    LaunchedEffect(client) {
        client.events.collect {
            lines.add("event  ${it.module}/${it.event}  ${pretty.toJson(it.args)}")
        }
    }

    fun call() {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return
        val head = trimmed.split(Regex("\\s+"), limit = 3)
        if (head.size < 2) {
            lines.add("usage: <module> <method> [json-args]")
            return
        }
        val module = head[0]
        val method = head[1]
        val args = try {
            if (head.size == 3) JsonParser.parseString(head[2]).asJsonObject else JsonObject()
        } catch (e: RuntimeException) {
            lines.add("bad json args: ${e.message}")
            return
        }
        lines.add("call   $module/$method  $args")
        scope.launch {
            try {
                val data = client.request(module, method, args)
                lines.add("result $module/$method  ${pretty.toJson(data)}")
            } catch (e: TackyClient.TackyError) {
                lines.add("error  $module/$method  ${e.message}")
            }
        }
        input = ""
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text("backend: $backendState", fontWeight = FontWeight.Bold)

        val scroll = rememberScrollState()
        Column(modifier = Modifier.weight(1f).verticalScroll(scroll)) {
            for (line in lines) {
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("<module> <method> [json-args]") },
            )
            Button(onClick = { call() }) { Text("call") }
        }
    }
}
