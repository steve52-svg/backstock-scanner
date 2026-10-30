package com.frozenpull.scanner

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/** Lets the screen that's showing take the volume keys (and a headset button). */
object KeyBus {
    var handler: ((Int) -> Boolean)? = null
}

/** "Pull 2 of 3 · 1 partial, 1 full" -> something that sounds natural out loud. */
private fun spokenDetail(d: String): String {
    if (d.isBlank()) return ""
    val t = d.replace("·", ",")
    return if (Regex("partial", RegexOption.IGNORE_CASE).containsMatchIn(t) && Regex("full", RegexOption.IGNORE_CASE).containsMatchIn(t) && t.startsWith("Pull"))
        "$t. Start with the partial" else t
}

/**
 * Hands-free pulling: big buttons for gloves, the volume keys, and voice.
 * It reads each item out loud; say "pulled", "can't find", "next", "back" or "repeat".
 */
@Composable
fun HandsFreeScreen(state: AppState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val run = state.pullRun
    val clip = remember { ctx.getSystemService(ClipboardManager::class.java) }
    var status by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var listen by remember { mutableStateOf(false) }
    var talk by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf<String?>(null) }

    // Where each product sits, from the catalog (falls back to the list's section heading).
    fun whereOf(it: PullItem): String {
        val p = state.catalog.products.firstOrNull { p -> p.name.equals(it.name, ignoreCase = true) }
            ?: state.matcher.guessText(it.name).firstOrNull()?.takeIf { g -> g.score >= 0.85f }?.product
        return p?.place?.takeIf { s -> s.isNotBlank() } ?: it.section
    }
    fun sayItem(i: Int): String {
        val it = run.items.getOrNull(i) ?: return ""
        return listOf(it.name, spokenDetail(it.detail), whereOf(it).replace("·", ",")).filter { s -> s.isNotBlank() }.joinToString(". ")
    }

    val actRef = remember { mutableStateOf<(Cmd) -> Unit>({}) }
    val voice = remember { Voice(ctx, onCommand = { actRef.value(it) }, onStatus = { status = it }) }
    LaunchedEffect(Unit) { run.sync() }
    DisposableEffect(Unit) {
        voice.start()
        view.keepScreenOn = true
        onDispose { voice.shutdown(); view.keepScreenOn = false; KeyBus.handler = null }
    }

    fun finishLine() = "That's everything. ${run.pulled} pulled" + (if (run.cant > 0) ", ${run.cant} can't find." else ".")
    fun advance(prefix: String) {
        val nx = run.nextTodo(run.index)
        if (nx == null) { voice.speak("$prefix ${finishLine()}"); return }
        run.index = nx; run.save()
        voice.speak("$prefix Next: ${sayItem(nx)}")
    }
    fun act(c: Cmd) {
        if (run.items.isEmpty()) return
        val i = run.index
        when (c) {
            Cmd.PULLED -> { run.set(i, PullStatus.PULLED); advance("Pulled.") }
            Cmd.CANT -> { run.set(i, PullStatus.CANT); advance("Can't find, noted.") }
            Cmd.NEXT -> {
                val nx = run.nextTodo(i) ?: ((i + 1) % run.items.size)
                run.index = nx; run.save(); voice.speak(sayItem(nx))
            }
            Cmd.BACK -> { run.index = (i - 1).coerceAtLeast(0); run.save(); voice.speak(sayItem(run.index)) }
            Cmd.REPEAT -> voice.speak(if (run.left == 0) finishLine() else sayItem(i))
            Cmd.STOP -> { listen = false; voice.setListening(false); voice.speak("Voice off.") }
        }
    }
    actRef.value = ::act

    // Volume down = Pulled, volume up = Can't find. Works with gloves.
    DisposableEffect(Unit) {
        KeyBus.handler = { code ->
            when (code) {
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { actRef.value(Cmd.PULLED); true }
                KeyEvent.KEYCODE_VOLUME_UP -> { actRef.value(Cmd.CANT); true }
                else -> false
            }
        }
        onDispose { KeyBus.handler = null }
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { listen = true; voice.setListening(true) } else note = "Without the microphone, use the buttons or volume keys."
    }
    fun toggleListen() {
        if (listen) { listen = false; voice.setListening(false); return }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            listen = true; voice.setListening(true)
        } else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun pasteList() {
        val text = clip?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
        val n = run.load(text)
        note = if (n > 0) "Loaded from the pull list page: ${run.items.size} to pull." else "Nothing to load. On the pull list page tap Send to app first."
        if (n > 0 && run.items.isNotEmpty()) voice.speak("${run.items.size} items. First: ${sayItem(run.index)}")
    }
    fun copyResults() {
        clip?.setPrimaryClip(ClipData.newPlainText("Pull results", run.resultsText()))
        note = "Results copied. On the pull list page, tap Paste results from app."
    }

    val green = T.accent; val red = T.miss
    Column(Modifier.fillMaxSize().background(Bg).padding(10.dp)) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onBack) { Text("‹ Scanner", color = Ink) }
            FilterChip(selected = listen, onClick = { toggleListen() }, label = { Text(if (listen) "Voice: on" else "Voice: off") })
            FilterChip(selected = talk, onClick = { talk = !talk; voice.talk = talk }, label = { Text(if (talk) "Read aloud" else "Silent") })
            OutlinedButton(onClick = { pasteList() }) { Text("Load from page", color = Ink) }
            OutlinedButton(onClick = { copyResults() }, enabled = run.pulled + run.cant > 0) { Text("Copy results", color = Ink) }
            val warn = Color(0xFFFF8A80)
            OutlinedButton(onClick = { confirm = "restart" }, enabled = run.items.isNotEmpty()) { Text("Start over", color = Ink) }
            OutlinedButton(onClick = {
                // Cancel all: stop reading and listening right now and leave hands-free. The list and marks are kept.
                listen = false; voice.hush(); onBack()
            }) { Text("Cancel all", color = warn) }
            OutlinedButton(onClick = { confirm = "clear" }, enabled = run.items.isNotEmpty()) { Text("Clear all", color = warn) }
        }
        val line = listOf(note, if (listen) status else "").filter { it.isNotBlank() }.joinToString("  ·  ")
        Text(line.ifEmpty { "Volume down = Pulled · Volume up = Can't find · or turn on Voice and say Pulled, Can't find, Next, Back, Repeat" },
            color = Muted, fontSize = 13.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))

        if (run.items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Nothing on the pull list", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Mark items Need on the Pull list screen (‹ Scanner → Pull list), or on the pull list page and tap Send to app. Then come back here.",
                    color = Muted, fontSize = 16.sp)
            }
            return@Column
        }

        val i = run.index.coerceIn(0, run.items.lastIndex)
        val it = run.items[i]
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // The current item, big enough to read at arm's length.
            Column(Modifier.weight(1f).fillMaxHeight().background(Panel, RoundedCornerShape(14.dp)).padding(18.dp)) {
                Text("${i + 1} of ${run.items.size}  ·  ${run.left} left  ·  ${run.pulled} pulled" + if (run.cant > 0) "  ·  ${run.cant} can't find" else "",
                    color = Muted, fontSize = 15.sp)
                Spacer(Modifier.height(10.dp))
                if (run.left == 0) {
                    Text("All done", color = green, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text("${run.pulled} pulled" + (if (run.cant > 0) ", ${run.cant} can't find" else ""), color = Ink, fontSize = 22.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { copyResults() }) { Text("Copy results for the pull list page", fontSize = 16.sp) }
                } else {
                    Text(it.name, color = Ink, fontSize = 38.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    if (it.detail.isNotBlank()) Text(it.detail, color = if (it.detail.contains("artial")) T.skip else Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(whereOf(it), color = Muted, fontSize = 20.sp)
                    when (it.status) {
                        PullStatus.PULLED -> Text("✓ Pulled", color = green, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        PullStatus.CANT -> Text("✗ Can't find", color = red, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        PullStatus.TODO -> {}
                    }
                }
            }
            // Glove-size buttons.
            Column(Modifier.width(280.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { act(Cmd.PULLED) }, modifier = Modifier.fillMaxWidth().weight(1.4f), shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = green, contentColor = T.bg), enabled = run.left > 0,
                ) { Text("PULLED", fontSize = 30.sp, fontWeight = FontWeight.Black) }
                Button(
                    onClick = { act(Cmd.CANT) }, modifier = Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = red, contentColor = T.bg), enabled = run.left > 0,
                ) { Text("CAN'T FIND", fontSize = 24.sp, fontWeight = FontWeight.Black) }
                Row(Modifier.fillMaxWidth().weight(0.7f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { act(Cmd.BACK) }, modifier = Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(12.dp)) { Text("Back", color = Ink, fontSize = 16.sp) }
                    OutlinedButton(onClick = { act(Cmd.REPEAT) }, modifier = Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(12.dp)) { Text("Say it", color = Ink, fontSize = 16.sp) }
                    OutlinedButton(onClick = { act(Cmd.NEXT) }, modifier = Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(12.dp)) { Text("Skip", color = Ink, fontSize = 16.sp) }
                }
            }
        }
    }

    when (confirm) {
        "restart" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Start over?") },
            text = { Text("Every item goes back to not done and it starts again from the first one. The list stays.") },
            confirmButton = { TextButton(onClick = {
                confirm = null; run.restart(); note = "Started over."
                voice.speak("Starting over. ${run.items.size} items. First: ${sayItem(0)}")
            }) { Text("Start over") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
        "clear" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Clear all?") },
            text = { Text("Takes everything off the pull list, with its Pulled / Can't find marks. The stock list stays. Copy results first if the page still needs them.") },
            confirmButton = { TextButton(onClick = {
                confirm = null; listen = false; voice.hush(); run.clear(); note = "Pull list cleared."
            }) { Text("Clear all", color = Color(0xFFE5534B)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
