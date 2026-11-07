package com.nuclearhack.steno

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // уведомление с прогрессом обработки (Android 13+ спрашивает разрешение)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            StenoTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Screen(vm) }
            }
        }
    }

    /** Во время записи экран не гаснет: без foreground-сервиса микрофона фоновая запись глушится системой. */
    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

private enum class Mode { Home, Recording, Processing, Result }

/** [мм:сс] (или ч:мм:сс) для подписей реплик и плеера. */
private fun clock(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

private fun eta(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(1)
    return if (s < 60) "≈ $s с" else "≈ ${s / 60} мин ${s % 60} с"
}

@Composable
private fun icon(id: Int): Painter = painterResource(id)

@OptIn(ExperimentalMaterial3Api::class)
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
    var summaryOpen by remember { mutableStateOf(false) }

    LaunchedEffect(st.message) {
        st.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); vm.messageShown() }
    }
    LaunchedEffect(rec.recording) { (ctx as? MainActivity)?.keepScreenOn(rec.recording) }

    val mode = when {
        rec.recording -> Mode.Recording
        st.busy -> Mode.Processing
        st.fileName != null && st.rtf != null -> Mode.Result
        else -> Mode.Home
    }
    AnimatedContent(mode, transitionSpec = { fadeIn(tween(280)) togetherWith fadeOut(tween(180)) }, label = "mode") { m ->
        when (m) {
            Mode.Home -> HomeScreen(
                st,
                onImport = { pick.launch(arrayOf("audio/*", "video/*", "application/ogg")) },
                onRecord = {
                    if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startRecording()
                    else micPerm.launch(Manifest.permission.RECORD_AUDIO)
                },
                onLang = vm::setLang,
                onSpeakers = vm::setNumSpeakers,
            )
            Mode.Recording -> RecordingScreen(rec, onStop = vm::stopRecording)
            Mode.Processing -> ProcessingScreen(st)
            Mode.Result -> ResultScreen(
                vm, st, pl,
                onRename = { renaming = it },
                onExportJson = { save.launch(vm.exportName()) },
                onExportTxt = { saveTxt.launch(vm.exportTxtName()) },
                onSummary = {
                    if (st.summary == null && !st.summarizing) vm.summarize()
                    summaryOpen = true
                },
                onNew = vm::newTranscript,
            )
        }
    }

    if (summaryOpen) {
        ModalBottomSheet(onDismissRequest = { summaryOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            SummarySheet(st, onExport = { saveSummary.launch(vm.summaryName()) }, onRetry = vm::summarize)
        }
    }

    renaming?.let { id ->
        var text by remember(id) { mutableStateOf(st.speakerNames[id] ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = null },
            icon = { Avatar(id, st.speakerNames, 48.dp) },
            title = { Text("Имя для ${TranscriptText.speakerName(id, emptyMap())}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, singleLine = true,
                        placeholder = { Text("Например, Анна") }, shape = RoundedCornerShape(16.dp),
                    )
                    Text("Меняет подписи в приложении и TXT. В JSON остаётся $id.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

// ---------------- общие элементы ----------------

@Composable
fun Logo(size: Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(LocalStenoExtra.current.brand)) {
        Icon(icon(R.drawable.ic_launcher_foreground), null, tint = Color.White, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun Avatar(id: String, names: Map<String, String>, size: Dp, onClick: (() -> Unit)? = null) {
    val custom = names[id]?.trim()?.takeIf { it.isNotEmpty() }
    val label = custom?.first()?.uppercase() ?: id.substringAfter('_')
    Box(
        Modifier.size(size).clip(CircleShape).background(speakerColor(id))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = LocalStenoExtra.current.onSpeaker, style = MaterialTheme.typography.titleMedium.copy(fontSize = (size.value * 0.42f).sp))
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(options: List<Pair<String, T>>, selected: T, enabled: Boolean, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (label, v) ->
            SegmentedButton(
                selected = v == selected, onClick = { onSelect(v) }, enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {}, label = { Text(label, maxLines = 1) },
            )
        }
    }
}

// ---------------- стартовый экран ----------------

@Composable
private fun HomeScreen(st: UiState, onImport: () -> Unit, onRecord: () -> Unit, onLang: (String) -> Unit, onSpeakers: (Int) -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Logo(56.dp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Нейро-\nстенографист", style = MaterialTheme.typography.displaySmall)
            Text(
                "Расшифровка разговоров по ролям: кто, когда и что сказал.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ActionCard(
                Modifier.weight(1f), "Импортировать файл", "MP3, WAV, M4A, OGG, видео", R.drawable.ic_upload,
                MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, !st.working, onImport,
            )
            ActionCard(
                Modifier.weight(1f), "Записать", "С микрофона телефона", R.drawable.ic_mic,
                MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary, !st.working, onRecord,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Язык записи")
            Segmented(listOf("Авто" to "auto", "RU" to "ru", "EN" to "en"), st.lang, !st.working, onLang)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Число спикеров")
            Segmented(listOf("Авто" to -1, "2" to 2, "3" to 3, "4" to 4, "5" to 5), st.numSpeakers, !st.working, onSpeakers)
        }
    }
}

@Composable
private fun ActionCard(
    modifier: Modifier, title: String, subtitle: String, iconRes: Int,
    container: Color, content: Color, enabled: Boolean, onClick: () -> Unit,
) {
    Card(
        onClick = onClick, enabled = enabled, modifier = modifier.height(176.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp, pressedElevation = 6.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(content.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(icon(iconRes), null, Modifier.size(26.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, lineHeight = 22.sp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.85f))
        }
    }
}

// ---------------- запись ----------------

@Composable
private fun RecordingScreen(rec: RecState, onStop: () -> Unit) {
    val coral = MaterialTheme.colorScheme.tertiary
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        1f, 1.12f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse",
    )
    val level by animateFloatAsState(rec.level.coerceIn(0f, 1f), tween(100), label = "level")
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(180.dp).scale(pulse + level * 0.25f).clip(CircleShape).background(coral.copy(alpha = 0.16f)))
            Box(Modifier.size(112.dp).clip(CircleShape).background(coral), contentAlignment = Alignment.Center) {
                Icon(icon(R.drawable.ic_mic), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onTertiary)
            }
        }
        Spacer(Modifier.height(32.dp))
        Text(clock(rec.ms), style = MaterialTheme.typography.displaySmall.copy(fontSize = 44.sp, lineHeight = 52.sp))
        Spacer(Modifier.height(8.dp))
        Text(
            "Идёт запись. Экран не погаснет, пока она идёт.\n«Стоп» — сохранить и расшифровать.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onStop, modifier = Modifier.height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = coral, contentColor = MaterialTheme.colorScheme.onTertiary),
            contentPadding = PaddingValues(horizontal = 28.dp),
        ) {
            Icon(icon(R.drawable.ic_stop), null)
            Spacer(Modifier.width(8.dp))
            Text("Стоп", style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ---------------- обработка ----------------

private val stages = listOf("Декодирование аудио" to 0f, "Определение говорящих" to 0.1f, "Распознавание речи" to 0.5f)

@Composable
private fun ProcessingScreen(st: UiState) {
    // Во время обработки — минимум перерисовок: прогресс без анимации (приходит ≤4 раз/с),
    // ETA пересчитывается раз в секунду.
    val started = remember(st.fileName) { SystemClock.elapsedRealtime() }
    val progress = st.progress
    val remaining by produceState<Long?>(null, st.fileName) {
        while (true) {
            delay(1000)
            val p = AppState.value.progress
            val elapsed = SystemClock.elapsedRealtime() - started
            value = if (p >= 0.03f && elapsed > 2000) (elapsed * (1 - p) / p).toLong() else null
        }
    }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Logo(44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Обработка", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(st.fileName ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    stages.forEachIndexed { i, (label, from) ->
                        val to = stages.getOrNull(i + 1)?.second ?: 1.01f
                        StageRow(i + 1, label, done = st.progress >= to, active = st.progress >= from && st.progress < to)
                    }
                }
                LinearProgressIndicator(
                    progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round, gapSize = 4.dp, drawStopIndicator = {},
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${(st.progress * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
                    Text(
                        remaining?.let { "осталось ${eta(it)}" } ?: "оцениваем время…",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Можно свернуть приложение — обработка продолжится, прогресс виден в уведомлении.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun StageRow(n: Int, label: String, done: Boolean, active: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when {
                done -> Box(Modifier.fillMaxSize().clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
                    Icon(icon(R.drawable.ic_check), null, Modifier.size(18.dp), tint = cs.onPrimary)
                }
                // статичный индикатор: бесконечная анимация перерисовывает экран и отнимает CPU у пайплайна
                active -> Box(
                    Modifier.fillMaxSize().clip(CircleShape).background(cs.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(10.dp).clip(CircleShape).background(cs.primary)) }
                else -> Box(
                    Modifier.fillMaxSize().clip(CircleShape).background(cs.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) { Text("$n", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label, style = MaterialTheme.typography.bodyLarge,
            color = if (done || active) cs.onSurface else cs.onSurfaceVariant,
        )
    }
}

// ---------------- расшифровка ----------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ResultScreen(
    vm: MainViewModel, st: UiState, pl: PlayerState,
    onRename: (String) -> Unit, onExportJson: () -> Unit, onExportTxt: () -> Unit, onSummary: () -> Unit, onNew: () -> Unit,
) {
    val sorted = remember(st.utterances) { st.utterances.sortedBy { it.startMs } }
    val speakers = remember(sorted) { sorted.map { it.speakerId }.distinct() }
    val current = if (pl.ready && (pl.playing || pl.positionMs > 0)) TranscriptText.currentIndex(sorted, pl.positionMs) else -1
    val list = rememberLazyListState()
    val header = 1 // число элементов LazyColumn перед репликами
    LaunchedEffect(current) {
        if (current >= 0 && pl.playing) {
            val visible = list.layoutInfo.visibleItemsInfo.map { it.index }
            if (current + header !in visible.dropLast(1)) list.animateScrollToItem(current + header)
        }
    }
    val seen = remember(st.fileName) { HashSet<String>() }
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { Box(Modifier.padding(start = 12.dp, end = 4.dp)) { Logo(36.dp) } },
                title = {
                    Column {
                        Text(st.fileName ?: "", style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${sorted.size} реплик · спикеров: ${speakers.size}" + (st.detectedLang?.let { " · ${it.uppercase()}" } ?: ""),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(icon(R.drawable.ic_more), "Меню") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(16.dp)) {
                            val item = @Composable { label: String, ic: Int, enabled: Boolean, action: () -> Unit ->
                                DropdownMenuItem(
                                    text = { Text(label) }, leadingIcon = { Icon(icon(ic), null) }, enabled = enabled,
                                    onClick = { menu = false; action() },
                                )
                            }
                            item("Экспорт JSON", R.drawable.ic_json, sorted.isNotEmpty(), onExportJson)
                            item("Экспорт TXT", R.drawable.ic_text, sorted.isNotEmpty(), onExportTxt)
                            item("Резюме (LLM)", R.drawable.ic_summary, sorted.isNotEmpty(), onSummary)
                            HorizontalDivider()
                            item("Новая расшифровка", R.drawable.ic_add, !st.summarizing, onNew)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        bottomBar = { if (pl.ready) PlayerBar(pl, vm.player) },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(), state = list,
            contentPadding = PaddingValues(top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (sorted.isEmpty()) {
                        Text("Речь не обнаружена", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            speakers.forEach { id -> SpeakerChip(id, st.speakerNames) { onRename(id) } }
                        }
                        Text(
                            "Нажмите на имя, чтобы переименовать; на реплику — чтобы прослушать." + (st.rtf?.let { " RTF ${"%.2f".format(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AnimatedVisibility(st.summarizing || st.summary != null) {
                        SummaryTeaser(st, onSummary)
                    }
                }
            }
            itemsIndexed(sorted, key = { i, u -> "${u.startMs}_$i" }) { i, u ->
                val key = "${u.startMs}_$i"
                val fresh = remember(key) { seen.add(key) }
                val appear = remember(key) { Animatable(if (fresh) 0f else 1f) }
                LaunchedEffect(key) {
                    if (appear.value < 1f) {
                        if (i < 10) delay(i * 45L)
                        appear.animateTo(1f, tween(320))
                    }
                }
                Utterance(
                    u, st.speakerNames, active = i == current, playable = pl.ready,
                    modifier = Modifier.graphicsLayer { alpha = appear.value; translationY = (1 - appear.value) * 24.dp.toPx() },
                    onRename = { onRename(u.speakerId) },
                    onPlay = { vm.player.seekTo(u.startMs, andPlay = true) },
                )
            }
        }
    }
}

@Composable
private fun SpeakerChip(id: String, names: Map<String, String>, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(id, names, 26.dp)
            Spacer(Modifier.width(8.dp))
            Text(TranscriptText.speakerName(id, names), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Utterance(
    u: Utterance, names: Map<String, String>, active: Boolean, playable: Boolean,
    modifier: Modifier, onRename: () -> Unit, onPlay: () -> Unit,
) {
    val color = speakerColor(u.speakerId)
    val base = MaterialTheme.colorScheme.surfaceContainerLow
    val bubble by animateColorAsState(if (active) color.copy(alpha = 0.18f).compositeOver(base) else base, tween(250), label = "bubble")
    val border by animateColorAsState(if (active) color else Color.Transparent, tween(250), label = "border")
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Avatar(u.speakerId, names, 40.dp, onClick = onRename)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    TranscriptText.speakerName(u.speakerId, names), style = MaterialTheme.typography.labelLarge, color = color,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(8.dp)).clickable(onClick = onRename).padding(vertical = 2.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("[${clock(u.startMs)}]", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Surface(
                onClick = onPlay, enabled = playable,
                shape = RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp),
                color = bubble, border = BorderStroke(1.5.dp, border),
            ) {
                Text(u.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
}

@Composable
private fun SummaryTeaser(st: UiState, onOpen: () -> Unit) {
    Card(
        onClick = onOpen, shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon(R.drawable.ic_summary), null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Резюме", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (st.summarizing) "${ProcessingService.summaryStageLabel(st.summaryStage).replaceFirstChar { it.uppercase() }}… ${(st.summaryProgress * 100).toInt()}%"
                    else st.summary?.summary ?: "",
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SummarySheet(st: UiState, onExport: () -> Unit, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Резюме", style = MaterialTheme.typography.headlineSmall)
        Text("Составлено локальной языковой моделью по расшифровке.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val s = st.summary
        when {
            st.summarizing -> {
                val p by animateFloatAsState(st.summaryProgress, tween(400), label = "sp")
                Text(
                    "${ProcessingService.summaryStageLabel(st.summaryStage).replaceFirstChar { it.uppercase() }}… ${(st.summaryProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyLarge,
                )
                LinearProgressIndicator(
                    progress = { p }, modifier = Modifier.fillMaxWidth().height(8.dp),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round, gapSize = 4.dp, drawStopIndicator = {},
                )
            }
            s != null -> {
                SummaryBlock("Кратко", listOf(s.summary), bullets = false)
                if (s.decisions.isNotEmpty()) SummaryBlock("Решения", s.decisions)
                if (s.tasks.isNotEmpty()) SummaryBlock("Задачи", s.tasks)
                if (st.summaryJson != null) FilledTonalButton(onClick = onExport, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(icon(R.drawable.ic_json), null)
                    Spacer(Modifier.width(8.dp))
                    Text("Экспорт резюме")
                }
            }
            else -> FilledTonalButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Составить резюме") }
        }
    }
}

@Composable
private fun SummaryBlock(title: String, items: List<String>, bullets: Boolean = true) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            items.forEach { Text(if (bullets) "• $it" else it, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
private fun PlayerBar(pl: PlayerState, player: Player) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val dur = pl.durationMs.coerceAtLeast(1)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = { player.toggle() }, modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                AnimatedContent(pl.playing, transitionSpec = { (fadeIn(tween(150)) + scaleIn(initialScale = 0.7f)) togetherWith fadeOut(tween(100)) }, label = "pp") { playing ->
                    Icon(icon(if (playing) R.drawable.ic_pause else R.drawable.ic_play), if (playing) "Пауза" else "Воспроизвести", Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Slider(
                    value = dragging ?: (pl.positionMs.toFloat() / dur).coerceIn(0f, 1f),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { player.seekTo((it * dur).toLong()) }; dragging = null },
                    modifier = Modifier.height(28.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val pos = dragging?.let { (it * dur).toLong() } ?: pl.positionMs
                    Text(clock(pos), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(clock(pl.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
