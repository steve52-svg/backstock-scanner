package com.frozenpull.camera

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Photos & settings: browse the route photos by day, open one full size,
 * retake or delete it, and the app's few settings.
 */
@Composable
fun PhotosScreen(
    store: PhotoStore,
    autoShoot: Boolean,
    onAutoShoot: (Boolean) -> Unit,
    onBack: () -> Unit,
    onRetake: (Spot) -> Unit,
    onReadDay: (String) -> Unit,
) {
    val today = LocalDate.now().toString()
    var refresh by remember { mutableIntStateOf(0) }
    var dates by remember { mutableStateOf(listOf(today)) }
    var date by remember { mutableStateOf(today) }
    var shots by remember { mutableStateOf<List<PhotoStore.Shot>>(emptyList()) }
    var open by remember { mutableStateOf<PhotoStore.Shot?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(refresh) {
        dates = withContext(Dispatchers.IO) { (store.dates() + today).distinct().sortedDescending() }
    }
    LaunchedEffect(date, refresh) {
        shots = withContext(Dispatchers.IO) { store.shots(date) }
    }

    val bg = Color(0xFF111417)
    Column(Modifier.fillMaxSize().background(bg).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Camera", color = Color.White, fontSize = 16.sp) }
            Spacer(Modifier.width(8.dp))
            Text("Photos & settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        // Settings
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Auto-shoot when green", color = Color.White)
            Switch(checked = autoShoot, onCheckedChange = onAutoShoot)
            Spacer(Modifier.width(16.dp))
            OutlinedButton(onClick = { confirm = "refs" }) { Text("Forget line-up references", color = Color.White) }
        }

        // Day picker
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dates.forEach { d ->
                FilterChip(selected = d == date, onClick = { date = d }, label = { Text(if (d == today) "Today" else d) })
            }
        }
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${shots.size} photos", color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.weight(1f))
            if (shots.isNotEmpty()) Button(onClick = { onReadDay(date) }) { Text("Read these photos") }
            Spacer(Modifier.width(8.dp))
            if (shots.isNotEmpty()) TextButton(onClick = { confirm = "day" }) { Text("Delete this day's photos", color = Color(0xFFFF8A80)) }
        }
        if (note.isNotEmpty()) Text(note, color = Color(0xFF2ECC71), fontSize = 13.sp)

        if (shots.isEmpty()) {
            Text("No photos for this day yet.", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 24.dp))
        } else {
            // Grouped by section, in route order.
            val grouped = shots.groupBy { it.spot?.section?.title ?: "Other" }
            LazyVerticalGrid(GridCells.Adaptive(150.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                grouped.forEach { (title, list) ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(title, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(list, key = { it.uri.toString() }) { shot -> ShotTile(store, shot) { open = shot } }
                }
            }
        }
    }

    // Full-size view of one photo.
    open?.let { shot ->
        var big by remember(shot) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(shot) { big = withContext(Dispatchers.IO) { store.full(shot.uri) } }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable { open = null }) {
            big?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().padding(bottom = 64.dp), contentScale = ContentScale.Fit) }
            Row(Modifier.align(Alignment.BottomCenter).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(shot.spot?.label ?: shot.name, color = Color.White)
                if (shot.date == today && shot.spot != null) {
                    Button(onClick = { open = null; onRetake(shot.spot!!) }) { Text("Retake this spot") }
                }
                OutlinedButton(onClick = {
                    store.delete(shot.uri); open = null; note = "Deleted ${shot.name}"; refresh++
                }) { Text("Delete", color = Color(0xFFFF8A80)) }
                TextButton(onClick = { open = null }) { Text("Close", color = Color.White) }
            }
        }
    }

    when (confirm) {
        "day" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete ${if (date == today) "today's" else date} photos?") },
            text = { Text("This removes all ${shots.size} route photos for that day from the phone. It can't be undone.") },
            confirmButton = { TextButton(onClick = { val n = store.deleteDay(date); note = "Deleted $n photos"; confirm = null; refresh++ }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
        "refs" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Forget line-up references?") },
            text = { Text("The faded \"last time\" photo for each spot will be cleared. Use this if shelves were rearranged. Your photos stay.") },
            confirmButton = { TextButton(onClick = { store.clearReferences(); note = "Line-up references cleared"; confirm = null }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ShotTile(store: PhotoStore, shot: PhotoStore.Shot, onClick: () -> Unit) {
    var bmp by remember(shot.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(shot.uri) { bmp = withContext(Dispatchers.IO) { store.thumbnail(shot.uri) } }
    Column(Modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF263238))) {
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Text(shot.spot?.let { "Spot ${it.step + 1}" } ?: shot.name, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
    }
}
