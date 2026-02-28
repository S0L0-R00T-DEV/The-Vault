package com.vault.srd.ui.intruder

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vault.srd.ui.common.rememberBitmapFromFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun IntruderLogScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sections by remember { mutableStateOf<List<IntruderSection>>(emptyList()) }
    var selectedImage by remember { mutableStateOf<IntruderImage?>(null) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var showDeleteOneConfirm by remember { mutableStateOf(false) }
    val dateHeaderFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    suspend fun refresh() {
        sections = withContext(Dispatchers.IO) {
            loadIntruderSections(context.filesDir)
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("INTRUDER CAPTURES", color = Color.White, fontWeight = FontWeight.Black) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    if (sections.isNotEmpty()) {
                        IconButton(onClick = { showClearAllConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear All", tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color.Black
    ) { padding ->
        if (sections.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No intruder captures yet.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showClearAllConfirm = true }) {
                            Text("CLEAR ALL", color = Color.Red)
                        }
                    }
                }
                items(sections, key = { it.dateLabel }) { section ->
                    Column {
                        Text(
                            text = section.dateLabel,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                        section.images.chunked(3).forEach { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                repeat(3) { index ->
                                    val image = row.getOrNull(index)
                                    if (image != null) {
                                        IntruderThumb(
                                            image = image,
                                            timeText = timeFormat.format(Date(image.timestamp)),
                                            onClick = { selectedImage = image }
                                        )
                                    } else {
                                        Spacer(
                                            modifier = Modifier
                                                .weight(1f)
                                                .aspectRatio(1f)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    }
                }
            }
        }
    }

    selectedImage?.let { image ->
        AlertDialog(
            onDismissRequest = { selectedImage = null },
            title = {
                Text(
                    "${dateHeaderFormat.format(Date(image.timestamp))} ${timeFormat.format(Date(image.timestamp))}",
                    color = Color.White
                )
            },
            text = {
                val bitmap by rememberBitmapFromFile(image.path, reqWidth = 1200, reqHeight = 1200)
                bitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit
                    )
                } ?: run {
                    Text("Image unavailable.", color = Color.Gray)
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showDeleteOneConfirm = true }) {
                        Text("DELETE", color = Color.Red)
                    }
                    TextButton(onClick = { selectedImage = null }) {
                        Text("CLOSE", color = Color.White)
                    }
                }
            },
            containerColor = Color.Black
        )
    }

    if (showDeleteOneConfirm && selectedImage != null) {
        AlertDialog(
            onDismissRequest = { showDeleteOneConfirm = false },
            title = { Text("DELETE CAPTURE", color = Color.White) },
            text = { Text("Delete this intruder image?", color = Color.Gray) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val image = selectedImage
                        if (image != null) {
                            File(image.path).delete()
                        }
                        selectedImage = null
                        showDeleteOneConfirm = false
                        scope.launch { refresh() }
                    }
                ) { Text("DELETE", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteOneConfirm = false }) { Text("CANCEL", color = Color.White) }
            },
            containerColor = Color.Black
        )
    }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("CLEAR ALL CAPTURES", color = Color.White) },
            text = { Text("Delete all intruder images?", color = Color.Gray) },
            confirmButton = {
                TextButton(
                    onClick = {
                        sections.flatMap { it.images }.forEach { File(it.path).delete() }
                        selectedImage = null
                        showClearAllConfirm = false
                        scope.launch { refresh() }
                    }
                ) { Text("DELETE ALL", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) { Text("CANCEL", color = Color.White) }
            },
            containerColor = Color.Black
        )
    }
}

@Composable
private fun RowScope.IntruderThumb(
    image: IntruderImage,
    timeText: String,
    onClick: () -> Unit
) {
    val bitmap by rememberBitmapFromFile(image.path, reqWidth = 240, reqHeight = 240)
    Box(
        modifier = Modifier
            .weight(1f)
            .aspectRatio(1f)
            .clickable(onClick = onClick)
            .background(Color(0xFF1E1E1E))
    ) {
        bitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Text(
            text = timeText,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

private fun loadIntruderSections(filesDir: File): List<IntruderSection> {
    val files = filesDir.listFiles { file ->
        file.isFile && file.name.startsWith("intruder_") && file.extension.lowercase() == "jpg"
    }?.toList().orEmpty()

    val images = files.map { file ->
        val ts = file.name
            .removePrefix("intruder_")
            .removeSuffix(".jpg")
            .toLongOrNull() ?: file.lastModified()
        IntruderImage(path = file.absolutePath, timestamp = ts)
    }.sortedByDescending { it.timestamp }

    val headerFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    return images
        .groupBy { headerFormat.format(Date(it.timestamp)) }
        .map { (date, list) -> IntruderSection(dateLabel = date, images = list) }
}

data class IntruderSection(
    val dateLabel: String,
    val images: List<IntruderImage>
)

data class IntruderImage(
    val path: String,
    val timestamp: Long
)
