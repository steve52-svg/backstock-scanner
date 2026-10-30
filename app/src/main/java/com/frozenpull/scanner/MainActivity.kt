package com.frozenpull.scanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = Holder.get(applicationContext)
        takeSharedList(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF2ECC71), secondary = Color(0xFFF5B041))) {
                CameraPermissionGate { AppRoot(state) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeSharedList(intent)
    }

    /** A pull list shared to the app (Share → Back Stock Scanner) opens straight into hands-free. */
    private fun takeSharedList(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val state = Holder.get(applicationContext)
        if (state.pullRun.load(text) > 0) state.openHandsFree = true
        setIntent(Intent(this, MainActivity::class.java))
    }

    // Volume keys (and a headset button) drive hands-free pulling while it's open.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val h = KeyBus.handler
        if (h != null && isHandsFreeKey(keyCode)) { if ((event?.repeatCount ?: 0) == 0) h(keyCode); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean =
        if (KeyBus.handler != null && isHandsFreeKey(keyCode)) true else super.onKeyUp(keyCode, event)

    private fun isHandsFreeKey(k: Int) = k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_UP ||
        k == KeyEvent.KEYCODE_HEADSETHOOK || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
}

/** Keeps the scan session alive for as long as the app is running. */
object Holder {
    private var state: AppState? = null
    fun get(ctx: Context): AppState = state ?: AppState(ctx).also { state = it }
}

const val APP_VERSION = "0.8"

val Sure = Color(0xFF2ECC71)
val Maybe = Color(0xFFF5B041)
val Unread = Color(0xFFB0BEC5)
val Bg = Color(0xFF111417)
val Panel = Color(0xFF1C2126)

fun levelColor(d: Detection) = when {
    d.confirmed || d.level == Level.SURE -> Sure
    d.level == Level.GUESS -> Maybe
    else -> Unread
}

fun share(ctx: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, subject))
}

@Composable
private fun CameraPermissionGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (granted) content() else Column(
        Modifier.fillMaxSize().background(Bg).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Back Stock Scanner needs the camera to read box labels.", color = Color.White, fontSize = 18.sp)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
    }
}

enum class Screen { CAMERA, RESULTS, FOUND, ROUTE, HANDSFREE }

@Composable
fun AppRoot(state: AppState) {
    var screen by remember { mutableStateOf(Screen.CAMERA) }
    var photoIndex by remember { mutableIntStateOf(0) }
    var progress by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Reads a batch of photos one after another, then shows the first.
    fun scanAll(uris: List<Uri>, label: (Int) -> String) {
        if (uris.isEmpty()) return
        scope.launch {
            val first = state.photos.size
            uris.forEachIndexed { i, u ->
                progress = if (uris.size == 1) "Reading labels…" else "Reading photo ${i + 1} of ${uris.size}…"
                try { state.scan(u, label(i)) } catch (e: Exception) { }
            }
            progress = null
            if (state.photos.size > first) { photoIndex = first; screen = Screen.RESULTS }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        scanAll(uris) { "Photo ${it + 1} of ${uris.size}" }
    }
    val openGallery = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    BackHandler(enabled = screen != Screen.CAMERA) { screen = Screen.CAMERA }
    LaunchedEffect(state.openHandsFree) {
        if (state.openHandsFree) { screen = Screen.HANDSFREE; state.openHandsFree = false }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        when (screen) {
            Screen.CAMERA -> CameraScreen(
                state = state, busy = progress != null,
                onCaptured = { uri -> scanAll(listOf(uri)) { "Camera scan" } },
                onGallery = openGallery,
                onFound = { screen = Screen.FOUND },
                onRoute = { screen = Screen.ROUTE },
                onHandsFree = { screen = Screen.HANDSFREE },
                onResults = { photoIndex = state.photos.lastIndex; screen = Screen.RESULTS },
            )
            Screen.RESULTS -> ResultsScreen(
                state = state, index = photoIndex.coerceIn(0, (state.photos.size - 1).coerceAtLeast(0)),
                onIndex = { photoIndex = it },
                onBack = { screen = Screen.CAMERA },
                onFound = { screen = Screen.FOUND },
            )
            Screen.FOUND -> FoundScreen(state, onBack = { screen = Screen.CAMERA })
            Screen.HANDSFREE -> HandsFreeScreen(state, onBack = { screen = Screen.CAMERA })
            Screen.ROUTE -> com.frozenpull.camera.WalkInSection(
                onExit = { screen = Screen.CAMERA },
                onRead = { uris, labels -> scanAll(uris) { labels.getOrElse(it) { "Walk-in photo" } } },
            )
        }
        progress?.let { msg ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Sure)
                    Spacer(Modifier.height(12.dp))
                    Text(msg, color = Color.White, fontSize = 18.sp)
                }
            }
        }
    }
}

@Composable
fun CameraScreen(
    state: AppState,
    busy: Boolean,
    onCaptured: (Uri) -> Unit,
    onGallery: () -> Unit,
    onFound: () -> Unit,
    onRoute: () -> Unit,
    onHandsFree: () -> Unit,
    onResults: () -> Unit,
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var shooting by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        }, ContextCompat.getMainExecutor(ctx))
    }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }

    fun shoot() {
        if (shooting || busy) return
        shooting = true
        imageCapture.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                scope.launch {
                    val uri = withContext(Dispatchers.IO) {
                        try {
                            val bmp = image.toBitmap()
                            val rot = image.imageInfo.rotationDegrees
                            val up = if (rot == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                            val f = state.newCaptureFile()
                            f.outputStream().use { up.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                            Uri.fromFile(f)
                        } catch (e: Exception) { null } finally { image.close() }
                    }
                    shooting = false
                    if (uri != null) onCaptured(uri) else note = "Couldn't take the picture — try again"
                }
            }
            override fun onError(e: ImageCaptureException) { shooting = false; note = "Camera error — try again" }
        })
    }

    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            Text(
                note.ifEmpty { "Fit a few boxes in the picture, hold still, tap Scan. Or scan photos you already took." },
                color = Color.White, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.55f), MaterialTheme.shapes.small).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Column(
            Modifier.width(170.dp).fillMaxHeight().background(Panel)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            Button(
                onClick = { shoot() }, enabled = !shooting && !busy, shape = CircleShape,
                modifier = Modifier.size(90.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Sure, contentColor = Color.Black),
            ) { Text(if (shooting) "…" else "Scan", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            Button(
                onClick = onRoute, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Maybe, contentColor = Color.Black),
            ) { Text("Walk-in route", fontWeight = FontWeight.Bold) }
            Button(
                onClick = onHandsFree, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4FC0CC), contentColor = Color.Black),
            ) { Text("Hands-free pull", fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = onGallery, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Scan photos", color = Color.White) }
            OutlinedButton(onClick = onFound, modifier = Modifier.fillMaxWidth()) { Text("Found (${state.found.items.size})", color = Color.White) }
            if (state.photos.isNotEmpty()) {
                OutlinedButton(onClick = onResults, modifier = Modifier.fillMaxWidth()) { Text("Last results", color = Color.White) }
            }
            TextButton(onClick = { torch = !torch }) { Text(if (torch) "Light: on" else "Light: off", color = Color.White) }
            Text("version $APP_VERSION", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
        }
    }
}
