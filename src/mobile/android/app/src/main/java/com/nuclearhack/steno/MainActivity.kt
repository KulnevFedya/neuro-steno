package com.nuclearhack.steno

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // уведомление с прогрессом обработки (Android 13+ спрашивает разрешение)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { Screen(vm) }
            }
        }
    }

    /** Во время записи экран не гаснет: без foreground-сервиса микрофона фоновая запись глушится системой. */
    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

private val speakerColors = listOf(
    Color(0xFF1565C0), Color(0xFFC62828), Color(0xFF2E7D32), Color(0xFF6A1B9A),
    Color(0xFFEF6C00), Color(0xFF00838F), Color(0xFF4E342E), Color(0xFFAD1457),
)

private fun speakerColor(id: String) =
    speakerColors[((id.substringAfter('_').toIntOrNull() ?: 1) - 1).mod(speakerColors.size)]

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Screen(vm: MainViewModel) {
    val st by vm.state.collectAsStateWithLifecycle()
    val pl by vm.player.state.collectAsStateWithLifecycle()
    val rec by vm.rec.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.transcribe(uri) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) vm.export(uri) }
    val saveTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> if (uri != null) vm.exportTxt(uri) }
    val saveSummary = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) vm.exportSummary(uri) }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.startRecording() else Toast.makeText(ctx, "Нужен доступ к микрофону", Toast.LENGTH_SHORT).show()
    }
    var renaming by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(st.message) {
        st.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); vm.messageShown() }
    }
    LaunchedEffect(rec.recording) { (ctx as? MainActivity)?.keepScreenOn(rec.recording) }

    val sorted = remember(st.utterances) { st.utterances.sortedBy { it.startMs } }
    val current = if (pl.ready && (pl.playing || pl.positionMs > 0)) TranscriptText.currentIndex(sorted, pl.positionMs) else -1
    val list = rememberLazyListState()
    val header = 1 // число элементов LazyColumn перед репликами
    LaunchedEffect(current) {
        if (current >= 0 && pl.playing) {
            val visible = list.layoutInfo.visibleItemsInfo.map { it.index }
            if (current + header !in visible.dropLast(1)) list.animateScrollToItem(current + header)
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Нейро-стенографист", style = MaterialTheme.typography.headlineSmall)
                    Text("Всё на устройстве, без интернета", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pick.launch(arrayOf("audio/*", "video/*", "application/ogg")) }, enabled = !st.working && !rec.recording) {
                            Text("Импорт аудио")
                        }
                        if (rec.recording) {
                            Button(onClick = { vm.stopRecording() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                                Text("■ Стоп ${TranscriptText.time(rec.ms).substringBeforeLast('.')}")
                            }
                        } else {
                            OutlinedButton(onClick = {
                                if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startRecording()
                                else micPerm.launch(Manifest.permission.RECORD_AUDIO)
                            }, enabled = !st.working) { Text("● Запись") }
                        }
                    }
                    if (rec.recording) {
                        LinearProgressIndicator(progress = { rec.level.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
                        Text("Идёт запись с микрофона. «Стоп» — сохранить WAV и расшифровать.", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Язык записи", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((label, l) in listOf("Русский" to "ru", "English" to "en")) {
                            FilterChip(selected = st.lang == l, onClick = { vm.setLang(l) }, enabled = !st.working && !rec.recording, label = { Text(label) })
                        }
                    }
                    Text("Число спикеров", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((label, n) in listOf("Авто" to -1, "2" to 2, "3" to 3, "4" to 4, "5" to 5)) {
                            FilterChip(selected = st.numSpeakers == n, onClick = { vm.setNumSpeakers(n) }, enabled = !st.working, label = { Text(label) })
                        }
                    }
                    st.fileName?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                    if (st.busy) {
                        LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
                        val stage = when {
                            st.progress < 0.1f -> "Декодирование"
                            st.progress < 0.5f -> "Определение говорящих"
                            else -> "Распознавание речи"
                        }
                        Text("$stage… ${(st.progress * 100).toInt()}%  (можно свернуть приложение)", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!st.busy && st.fileName != null && st.error == null) {
                        val spk = sorted.map { it.speakerId }.distinct()
                        if (sorted.isEmpty()) Text("Речь не обнаружена", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Text(
                            "${sorted.size} реплик · спикеров: ${spk.size}" + (st.rtf?.let { " · RTF ${"%.2f".format(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (sorted.isNotEmpty() && !st.busy) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { save.launch(vm.exportName()) }) { Text("JSON") }
                            FilledTonalButton(onClick = { saveTxt.launch(vm.exportTxtName()) }) { Text("TXT") }
                            FilledTonalButton(onClick = { vm.summarize() }, enabled = !st.working) { Text("Резюме (LLM)") }
                            if (st.summaryJson != null) FilledTonalButton(onClick = { saveSummary.launch(vm.summaryName()) }) { Text("Экспорт резюме") }
                        }
                        Text("Спикеры (нажмите, чтобы переименовать)", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (id in sorted.map { it.speakerId }.distinct()) {
                                AssistChip(
                                    onClick = { renaming = id },
                                    label = { Text(TranscriptText.speakerName(id, st.speakerNames)) },
                                    leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(speakerColor(id))) },
                                )
                            }
                        }
                    }
                    if (st.summarizing) {
                        val label = ProcessingService.summaryStageLabel(st.summaryStage).replaceFirstChar { it.uppercase() }
                        LinearProgressIndicator(progress = { st.summaryProgress }, modifier = Modifier.fillMaxWidth())
                        Text("Резюме: $label… ${(st.summaryProgress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                    st.summary?.let { s -> SummaryCard(s) }
                }
            }
            itemsIndexed(sorted, key = { i, u -> "${u.startMs}_$i" }) { i, u ->
                val active = i == current
                val color = speakerColor(u.speakerId)
                Card(
                    Modifier.padding(horizontal = 12.dp).fillMaxWidth().clickable(enabled = pl.ready) { vm.player.seekTo(u.startMs, andPlay = true) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (active) color.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(Modifier.padding(10.dp)) {
                        Box(Modifier.width(4.dp).size(width = 4.dp, height = 36.dp).clip(RoundedCornerShape(2.dp)).background(color))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "${TranscriptText.speakerName(u.speakerId, st.speakerNames)} · ${TranscriptText.time(u.startMs)} – ${TranscriptText.time(u.endMs)}",
                                style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold,
                            )
                            Text(u.text, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal)
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(12.dp)) }
        }
        if (pl.ready) PlayerBar(pl, vm.player)
    }

    renaming?.let { id ->
        var text by remember(id) { mutableStateOf(st.speakerNames[id] ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Имя для ${TranscriptText.speakerName(id, emptyMap())}") },
            text = {
                Column {
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text("Например, Анна") })
                    Text("Меняет подписи в приложении и TXT. В JSON остаётся $id.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { vm.renameSpeaker(id, text); renaming = null }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Отмена") } },
        )
    }

    st.error?.let { e ->
        AlertDialog(
            onDismissRequest = { vm.errorShown() },
            title = { Text("Не получилось") },
            text = { Text(e) },
            confirmButton = { TextButton(onClick = { vm.errorShown() }) { Text("OK") } },
        )
    }
}

@Composable
private fun SummaryCard(s: SummaryLogic.Summary) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
}

@Composable
private fun PlayerBar(pl: PlayerState, player: Player) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { player.toggle() }, shape = CircleShape) { Text(if (pl.playing) "❚❚" else "▶") }
            Spacer(Modifier.width(8.dp))
            val dur = pl.durationMs.coerceAtLeast(1)
            Slider(
                value = dragging ?: (pl.positionMs.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { player.seekTo((it * dur).toLong()) }; dragging = null },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${TranscriptText.time(dragging?.let { (it * dur).toLong() } ?: pl.positionMs).substringBeforeLast('.')} / ${TranscriptText.time(pl.durationMs).substringBeforeLast('.')}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
