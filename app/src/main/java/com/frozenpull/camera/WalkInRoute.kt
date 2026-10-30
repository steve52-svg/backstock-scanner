package com.frozenpull.camera

import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.util.concurrent.Executors

/** The Frozen Pull List page (claude.ai artifact). */
private const val PULL_LIST_URL = "https://claude.ai/artifact/3D84YCU9cmx5uKKJJwGdCi"

/** Live state shown on screen, updated from the frame analyzer. */
private data class LiveCheck(
    val level: Boolean = false,
    val steady: Boolean = false,
    val score: Float = 0f,
    val hint: Hint = Hint.NONE,
    val hasTarget: Boolean = false,
    val pitch: Float = 0f,
    val roll: Float = 0f,
    // Sweep mode
    val sweepProgress: Float = 0f,
    val sweepStill: Int = 0,
    val sweepLost: Boolean = false,
    val sweepFirst: Boolean = false,
) {
    /** Sweep: moved far enough since the last photo and stopped long enough for a sharp one. */
    val sweepReady: Boolean get() = level && !sweepLost && sweepStill >= 3 && sweepProgress >= 1f
    val matched: Boolean get() = !hasTarget || score >= 0.65f
    val ready: Boolean get() = level && steady && matched
}


/**
 * Follows the view as you walk along a shelf (frame to frame, on the analyzer thread)
 * and says when it has moved about two-thirds of a photo since the last shot,
 * so the next photo overlaps the last by about a third.
 */
private class SweepTracker {
    private var last: Thumb? = null
    private var total = 0f
    var stillFrames = 0; private set
    var lost = false; private set
    var first = true; private set
    /** Set from the screen: restart counting (true = the next photo is the row's first). */
    val reset = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)

    fun update(t: Thumb) {
        reset.getAndSet(null)?.let { f -> total = 0f; first = f; last = t; stillFrames = 0; lost = false; return }
        val prev = last
        last = t
        if (prev == null) return
        val s = Aligner.shift(prev, t)
        if (s.score < 0.4f) { lost = true; stillFrames = 0; return }
        lost = false
        total += s.dx
        stillFrames = if (kotlin.math.abs(s.dx) <= 1f && kotlin.math.abs(s.dy) <= 2f) stillFrames + 1 else 0
    }

    val progress: Float get() = if (first) 1f else kotlin.math.abs(total) / (Thumb.W * 0.62f)
}

/**
 * The walk-in photo route (what used to be the separate Walk-in Camera app):
 * guided, lined-up photos of every shelf, plus the Photos & settings screen.
 * Kept for the whole time the app is open, so leaving and coming back keeps your place.
 */
object RouteState {
    val spot = mutableStateOf<Spot?>(Route.first())
    val auto = mutableStateOf(true)
    /** True when the last route finished was the quick wall check (not the full morning route). */
    val checkDone = mutableStateOf(false)
}

@Composable
fun WalkInSection(onExit: () -> Unit, onRead: (List<Uri>, List<String>) -> Unit) {
    val ctx = LocalContext.current
    val store = remember { PhotoStore(ctx) }
    var screen by remember { mutableStateOf("camera") }
    // Reads a day's route photos in the scanner, in route order.
    val readDay: (String) -> Unit = { date ->
        // Wide shots and wall checks are for What's left? on the pull list page, not for reading labels.
        val shots = store.shots(date).filter { it.spot?.isWide != true }
        if (shots.isNotEmpty()) onRead(shots.map { it.uri }, shots.map { it.spot?.label ?: it.name })
    }
    if (screen == "camera") {
        GuidedCapture(store, RouteState.spot, RouteState.auto, onOpenPhotos = { screen = "photos" },
            onExit = onExit, onReadToday = { readDay(LocalDate.now().toString()) })
    } else {
        PhotosScreen(
            store, RouteState.auto.value, { RouteState.auto.value = it },
            onBack = { screen = "camera" },
            onRetake = { RouteState.spot.value = it; screen = "camera" },
            onReadDay = readDay,
        )
    }
}

@Composable
private fun GuidedCapture(
    store: PhotoStore,
    spotState: MutableState<Spot?>,
    autoState: MutableState<Boolean>,
    onOpenPhotos: () -> Unit,
    onExit: () -> Unit,
    onReadToday: () -> Unit,
) {
    val ctx = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val motion = remember { Motion(ctx) }
    val analysisExec = remember { Executors.newSingleThreadExecutor() }

    var spot by spotState
    var live by remember { mutableStateOf(LiveCheck()) }
    var autoShoot by autoState
    var askReset by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var lastSaved by remember { mutableStateOf("") }
    // The previous shot in this row: used for the overlap guide when there is no reference yet.
    var prevThumb by remember { mutableStateOf<Thumb?>(null) }
    var prevGhost by remember { mutableStateOf<Bitmap?>(null) }
    // Sweep: walk slowly along the row; a photo is taken each time the view has moved
    // about two-thirds of a frame and you pause.
    var sweeping by remember { mutableStateOf(false) }
    val sweepOn = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val tracker = remember { SweepTracker() }
    LaunchedEffect(sweeping) { sweepOn.set(sweeping) }
    val beeper = remember {
        try { android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 80) } catch (e: Exception) { null }
    }

    val refThumb = remember(spot) { spot?.let { store.referenceThumb(it) } }
    val refGhost = remember(spot) { spot?.let { store.referenceGhost(it) } }

    // Values the analyzer thread reads.
    val target = rememberUpdatedState(
        when {
            refThumb != null -> refThumb to Aligner.Mode.REFERENCE
            prevThumb != null -> prevThumb!! to Aligner.Mode.OVERLAP
            else -> null
        }
    )
    val latestThumb = remember { java.util.concurrent.atomic.AtomicReference<Thumb?>(null) }

    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }

    DisposableEffect(Unit) {
        motion.start()
        onDispose { motion.stop(); analysisExec.shutdown(); beeper?.release() }
    }

    LaunchedEffect(flash) {
        imageCapture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }

    // Camera setup: preview + still capture + a small analysis stream for line-up checks.
    LaunchedEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            var lastRun = 0L
            analysis.setAnalyzer(analysisExec) { image ->
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastRun >= 120) { // about 8 checks a second
                        lastRun = now
                        val thumb = Thumb.from(image)
                        latestThumb.set(thumb)
                        val check = if (sweepOn.get()) {
                            tracker.update(thumb)
                            LiveCheck(
                                level = motion.isLevel, steady = motion.isSteady, pitch = motion.pitch, roll = motion.roll,
                                sweepProgress = tracker.progress, sweepStill = tracker.stillFrames,
                                sweepLost = tracker.lost, sweepFirst = tracker.first,
                            )
                        } else {
                            val t = target.value
                            val m = t?.let { Aligner.match(thumb, it.first, it.second) }
                            LiveCheck(
                                level = motion.isLevel, steady = motion.isSteady,
                                score = m?.score ?: 0f, hint = m?.hint ?: Hint.NONE, hasTarget = t != null,
                                pitch = motion.pitch, roll = motion.roll,
                            )
                        }
                        ContextCompat.getMainExecutor(ctx).execute { live = check }
                    }
                } finally {
                    image.close()
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun shoot() {
        val s = spot ?: return
        if (busy) return
        busy = true
        val thumbAtShot = latestThumb.get()
        val ghostAtShot = previewView.bitmap
        store.deleteForSpot(s) // a retake replaces the spot's earlier photo instead of adding a copy
        imageCapture.takePicture(store.outputOptions(s), ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(out: ImageCapture.OutputFileResults) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    thumbAtShot?.let { store.saveReference(s, it, ghostAtShot) }
                    lastSaved = "Saved ${s.label}"
                    if (sweeping) {
                        // Stay on this row until you tap Row done; count from here for the next photo.
                        try { beeper?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120) } catch (e: Exception) { }
                        tracker.reset.set(false)
                        prevThumb = thumbAtShot; prevGhost = ghostAtShot
                        spot = s.copy(step = s.step + 1)
                        busy = false
                        return
                    }
                    val next = Route.next(s)
                    if (next == null) RouteState.checkDone.value = s.section.id.startsWith("c")
                    if (next != null && next.section == s.section) {
                        prevThumb = thumbAtShot; prevGhost = ghostAtShot
                    } else {
                        prevThumb = null; prevGhost = null
                    }
                    spot = next
                    busy = false
                }

                override fun onError(e: ImageCaptureException) {
                    lastSaved = "Couldn't save that photo. Try again."
                    busy = false
                }
            })
    }

    fun startSweep() {
        val s = spot ?: return
        // Sweeping a row from its start replaces today's photos of that row.
        if (s.step == 0) store.deleteSection(s.section)
        tracker.reset.set(s.step == 0 || prevThumb == null)
        prevThumb = null; prevGhost = null
        sweeping = true
        lastSaved = "Sweeping ${s.section.title.lowercase()}"
    }

    // Sweep: take the photo as soon as you've moved far enough and paused.
    LaunchedEffect(live, sweeping) {
        if (sweeping && live.sweepReady && spot != null && !busy) shoot()
    }

    // Auto-shoot once everything has stayed green for 0.7 s.
    LaunchedEffect(live.ready, autoShoot, spot) {
        if (autoShoot && !sweeping && live.ready && spot != null && !busy) {
            kotlinx.coroutines.delay(700)
            if (live.ready && !busy) shoot()
        }
    }

    val green = Color(0xFF2ECC71)
    val frameColor = when {
        sweeping && live.sweepLost -> Color(0xFFFF6E6E)
        sweeping && live.sweepProgress >= 1f -> if (live.sweepStill >= 1) green else Color(0xFFF5B841)
        sweeping -> Color.White.copy(alpha = 0.8f)
        live.ready -> green
        live.level && live.steady -> Color(0xFFF5B841)
        else -> Color.White.copy(alpha = 0.6f)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ previewView }, Modifier.fillMaxSize())

        // Ghost of last time's photo at this spot: line the live view up with it.
        if (sweeping) {
            // no ghost while sweeping
        } else if (refGhost != null) {
            Image(refGhost.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.3f)
        } else if (prevGhost != null) {
            // No reference yet: show the right third of the previous shot over the left third of the screen.
            val p = prevGhost!!
            val third = remember(p) { Bitmap.createBitmap(p, p.width * 2 / 3, 0, p.width / 3, p.height) }
            Image(third.asImageBitmap(), null, Modifier.fillMaxHeight().fillMaxWidth(1f / 3f), contentScale = ContentScale.Crop, alpha = 0.35f)
        }

        // The highlight frame: green when lined up.
        val strong = if (sweeping) live.sweepReady else live.ready
        Box(Modifier.fillMaxSize().padding(10.dp).border(if (strong) 8.dp else 3.dp, frameColor, RoundedCornerShape(14.dp)))

        // Level guide: a line that tilts with the phone and turns green when level.
        Box(
            Modifier.align(Alignment.Center).width(160.dp).height(3.dp)
                .graphicsRotation(live.roll)
                .background(if (live.level) green else Color.White.copy(alpha = 0.8f))
        )

        // Top: where to stand and what to do.
        Column(
            Modifier.align(Alignment.TopStart).padding(20.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp)).padding(12.dp)
        ) {
            val s = spot
            if (s == null && RouteState.checkDone.value) {
                Text("Walls checked", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("On the pull list page, tap What's left? and add the check photos from the gallery folder WalkIn › ${LocalDate.now()} (files cl = left shelf, cb = back wall, cr = right shelf, cf = front wall).", color = Color.White)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { openPullList(ctx) }) { Text("Open pull list") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { spot = Route.checkFirst(); prevThumb = null; prevGhost = null }) { Text("Check the walls again", color = Color.White) }
            } else if (s == null) {
                Text("Route done", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Add today's photos to the pull list page with Update walk-in (gallery folder WalkIn › ${LocalDate.now()}): the w files are the wide shots, the rest go section by section. Or read them here.", color = Color.White)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onReadToday) { Text("Read today's photos") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { openPullList(ctx) }) { Text("Open pull list", color = Color.White) }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { spot = Route.first(); prevThumb = null; prevGhost = null }) { Text("Start the route again", color = Color.White) }
            } else {
                Text(s.label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(s.section.direction, color = Color.White.copy(alpha = 0.85f))
                Spacer(Modifier.height(6.dp))
                if (sweeping) {
                    Text(sweepInstruction(live, s.section.direction), color = frameColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (!live.sweepFirst) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { live.sweepProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.width(220.dp).height(6.dp), color = frameColor,
                        )
                    }
                } else {
                    Text(instruction(live, refThumb != null, prevThumb != null), color = frameColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (lastSaved.isNotEmpty()) Text(lastSaved, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        }

        // Right side controls, in two columns so they fit on a sideways phone.
        Row(
            Modifier.align(Alignment.CenterEnd).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onExit) { Text("‹ Scanner", color = Color.White) }
                OutlinedButton(onClick = onReadToday) { Text("Read photos", color = Color.White) }
                OutlinedButton(onClick = onOpenPhotos) { Text("Photos", color = Color.White) }
                OutlinedButton(onClick = { openPullList(ctx) }) { Text("Pull list", color = Color.White) }
                OutlinedButton(onClick = {
                    sweeping = false; RouteState.checkDone.value = false
                    spot = Route.checkFirst(); prevThumb = null; prevGhost = null
                    lastSaved = "Quick check: one wide shot per wall"
                }) { Text("Check walls", color = Color.White) }
                OutlinedButton(onClick = { askReset = true }) { Text("Reset", color = Color.White) }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(76.dp).border(5.dp, frameColor, CircleShape).padding(8.dp)
                        .background(if (busy) Color.Gray else Color.White, CircleShape)
                ) {
                    TextButton(onClick = { shoot() }, enabled = spot != null && !busy, modifier = Modifier.fillMaxSize()) {}
                }
                Button(
                    onClick = { if (sweeping) sweeping = false else startSweep() }, enabled = spot != null,
                    colors = ButtonDefaults.buttonColors(containerColor = if (sweeping) Color(0xFFC62828) else Color(0xFFF5B841), contentColor = if (sweeping) Color.White else Color.Black),
                ) { Text(if (sweeping) "Stop sweep" else "Sweep", fontWeight = FontWeight.Bold) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = autoShoot, onClick = { autoShoot = !autoShoot }, label = { Text("Auto") }, enabled = !sweeping)
                    FilterChip(selected = flash, onClick = { flash = !flash }, label = { Text("Flash") })
                }
                OutlinedButton(onClick = {
                    sweeping = false
                    spot?.let { s -> spot = Route.nextSection(s); prevThumb = null; prevGhost = null }
                }, enabled = spot != null) { Text("Row done", color = Color.White) }
                OutlinedButton(onClick = {
                    spot?.let { s -> if (s.step > 0) spot = s.copy(step = s.step - 1) }
                }, enabled = (spot?.step ?: 0) > 0) { Text("Retake last", color = Color.White) }
            }
        }
    }

    if (askReset) ResetDialog(
        onKeep = { spot = Route.first(); prevThumb = null; prevGhost = null; askReset = false; lastSaved = "Route restarted" },
        onDelete = {
            val n = store.deleteDay(LocalDate.now().toString())
            spot = Route.first(); prevThumb = null; prevGhost = null; askReset = false
            lastSaved = "Deleted $n photos. Route restarted."
        },
        onCancel = { askReset = false },
    )
}

/** Opens the pull list page in the Claude app if installed, otherwise the browser. */
private fun openPullList(ctx: android.content.Context) {
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PULL_LIST_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun ResetDialog(onKeep: () -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) = AlertDialog(
    onDismissRequest = onCancel,
    title = { Text("Start the route over?") },
    text = { Text("Go back to Left shelf · top row, spot 1. Keep today's photos (each one is replaced as you reshoot it), or delete them all and start clean.") },
    confirmButton = { TextButton(onClick = onKeep) { Text("Start over") } },
    dismissButton = {
        Row {
            TextButton(onClick = onDelete) { Text("Delete today's photos", color = Color(0xFFC62828)) }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    },
)

private fun sweepInstruction(c: LiveCheck, direction: String): String = when {
    c.sweepLost -> "Too fast. Slow down"
    c.pitch > 6f -> "Tilt the top of the phone toward you"
    c.pitch < -6f -> "Tilt the top of the phone away from you"
    !c.level -> "Level the phone"
    c.sweepFirst -> if (c.sweepStill >= 1) "Hold still, first photo…" else "Aim at the start of the row and hold still"
    c.sweepProgress < 1f -> "Walk slowly: $direction"
    c.sweepStill < 1 -> "Stop and hold still"
    else -> "Hold still…"
}

private fun instruction(c: LiveCheck, hasRef: Boolean, hasPrev: Boolean): String = when {
    c.pitch > 6f -> "Tilt the top of the phone toward you"
    c.pitch < -6f -> "Tilt the top of the phone away from you"
    !c.level -> "Level the phone"
    !c.steady -> "Hold still"
    c.hasTarget && c.score < 0.35f -> if (hasRef) "Find the spot from last time" else "Overlap the last shot by about a third"
    c.hasTarget && !c.matched -> c.hint.text.ifEmpty { "Almost there" }
    c.ready && !hasRef && !hasPrev -> "Level and steady. Taking the photo…"
    c.ready -> "Lined up. Hold still…"
    else -> ""
}

/** Rotates a composable around its center by the given degrees. */
private fun Modifier.graphicsRotation(degrees: Float): Modifier = this.rotate(-degrees)
