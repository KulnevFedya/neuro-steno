package com.nuclearhack.steno

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // уведомление с прогрессом обработки (Android 13+ спрашивает разрешение)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { Screen(vm) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(vm: MainViewModel) {
    val st by vm.state.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.transcribe(uri)
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(uri)
    }
    val saveSummary = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportSummary(uri)
    }
    LaunchedEffect(st.message) {
        st.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); vm.messageShown() }
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Нейро-стенографист", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pick.launch(arrayOf("audio/*", "video/*", "application/ogg")) }, enabled = !st.busy && !st.summarizing) {
                Text("Импорт аудио")
            }
            Button(onClick = { save.launch(vm.exportName()) }, enabled = !st.busy && st.utterances.isNotEmpty()) {
                Text("Экспорт JSON")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.summarize() }, enabled = !st.busy && !st.summarizing && st.utterances.isNotEmpty()) {
                Text("Резюме")
            }
            Button(onClick = { saveSummary.launch(vm.summaryName()) }, enabled = st.summaryJson != null) {
                Text("Экспорт резюме")
            }
        }
        if (st.summarizing) {
            val label = ProcessingService.summaryStageLabel(st.summaryStage).replaceFirstChar { it.uppercase() }
            LinearProgressIndicator(progress = { st.summaryProgress }, modifier = Modifier.fillMaxWidth())
            Text("$label… ${(st.summaryProgress * 100).toInt()}%")
        }
        st.summary?.let { s ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Резюме", style = MaterialTheme.typography.titleMedium)
                Text(s.summary)
                if (s.decisions.isNotEmpty()) {
                    Text("Решения", style = MaterialTheme.typography.labelLarge)
                    s.decisions.forEach { Text("• $it") }
                }
                if (s.tasks.isNotEmpty()) {
                    Text("Задачи", style = MaterialTheme.typography.labelLarge)
                    s.tasks.forEach { Text("• $it") }
                }
            }
        }
        Text("Число спикеров", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, n) in listOf("Авто" to -1, "2" to 2, "3" to 3, "4" to 4, "5" to 5)) {
                FilterChip(selected = st.numSpeakers == n, onClick = { vm.setNumSpeakers(n) }, enabled = !st.busy, label = { Text(label) })
            }
        }
        st.fileName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (st.busy) {
            LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
            Text("Расшифровка… ${(st.progress * 100).toInt()}%")
        }
        st.rtf?.let { Text("RTF: ${"%.3f".format(it)}", style = MaterialTheme.typography.bodySmall) }
        st.error?.let { Text("Ошибка: $it", color = MaterialTheme.colorScheme.error) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(st.utterances) { _, u ->
                Column {
                    Text(
                        "${u.speakerId} · ${fmt(u.startMs)} – ${fmt(u.endMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(u.text)
                }
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d.%d".format(s / 60, s % 60, (ms % 1000) / 100)
}
