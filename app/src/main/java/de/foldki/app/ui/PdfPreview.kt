package de.foldki.app.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.foldki.app.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Preview of a PDF before it is saved or shared: pages one below the other, "Seite x von n", pinch zoom,
 * Speichern / Teilen / Text ändern / Verwerfen. Back gesture = Verwerfen (in the editor: back to the preview).
 * Folded it is a full-screen page with stacked buttons, unfolded it fills the right pane.
 */
@Composable
fun PdfPreview(
    vm: ChatViewModel,
    state: ChatViewModel.PdfPreviewState,
    onSave: (File, String) -> Unit,
    onShare: (File) -> Unit,
    onSender: () -> Unit = {},
) {
    if (state.editing) { PdfTextEditor(vm, state); return }
    BackHandler { vm.closePreview() }
    // Exactly like mock-up 13: ✕ / file name / ⤴ (save) on top, the page in the middle, four buttons below.
    Surface(color = FK.Bg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val listState = rememberLazyListState()
            var pageCount by remember(state.file) { mutableStateOf(0) }
            val page by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }
            val enabled = !state.busy && state.file != null
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                NavBtn(Icons.Default.Close, "Verwerfen") { vm.closePreview() }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.fileName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = FK.Text, maxLines = 1)
                    if (!state.busy && pageCount > 1) Text("Seite ${page.coerceAtMost(pageCount)} von $pageCount", fontSize = 12.sp, color = FK.Muted)
                }
                Box(
                    Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(FK.Surface)
                        .clickable(enabled = enabled) { state.file?.let { onSave(it, state.fileName) } },
                    contentAlignment = Alignment.Center,
                ) { Text("⤴", fontSize = 18.sp, color = if (enabled) FK.Text else FK.Dim) }
            }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
                val file = state.file
                if (state.busy || file == null) CircularProgressIndicator(color = FK.Accent)
                else PdfPages(file, listState, onPageCount = { pageCount = it })
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 34.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                Cta("✎ Ändern", ghost = true, enabled = enabled) { vm.editPreview(true) }
                Cta("Absender", ghost = true) { onSender() }
                Cta("Unterschrift", ghost = true) { onSender() }
                Cta("Teilen", enabled = enabled) { state.file?.let { vm.markPreviewShared(); onShare(it) } }
            }
        }
    }
}

/** "Text ändern": the document text in a full-width field; "Übernehmen" builds the PDF again. */
@Composable
private fun PdfTextEditor(vm: ChatViewModel, state: ChatViewModel.PdfPreviewState) {
    var text by rememberSaveable(state.text) { mutableStateOf(state.text) }
    BackHandler { vm.editPreview(false) }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Text ändern", fontFamily = FontFamily.Serif, fontSize = 20.sp)
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.editPreview(false) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Abbrechen") }
                Button(onClick = { vm.applyPreviewText(text) }, enabled = text.isNotBlank(), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Übernehmen") }
            }
        }
    }
}

/** Renders the pages with Android's PdfRenderer: one page open at a time, bitmaps ≤ 1400 px, freed off-screen. */
@Composable
private fun PdfPages(file: File, listState: androidx.compose.foundation.lazy.LazyListState, onPageCount: (Int) -> Unit) {
    val mutex = remember(file) { Mutex() }
    val renderer = remember(file) {
        runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull()
    }
    DisposableEffect(renderer) {
        onDispose {
            // Close only after a running page render has finished.
            renderer?.let { r -> CoroutineScope(Dispatchers.IO).launch { mutex.withLock { runCatching { r.close() } } } }
        }
    }
    if (renderer == null) { Text("Vorschau nicht möglich.", color = Color.White); return }
    val count = remember(renderer) { runCatching { renderer.pageCount }.getOrDefault(0) }
    LaunchedEffect(count) { onPageCount(count) }

    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offsetX by remember(file) { mutableFloatStateOf(0f) }
    var offsetY by remember(file) { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { (maxWidth - 44.dp).toPx() }.toInt().coerceIn(200, 1400)
        val viewW = with(density) { maxWidth.toPx() }
        val viewH = with(density) { maxHeight.toPx() }
        // Zoom exactly where the fingers are (focal point), not only into the middle; the zoomed page can be moved everywhere.
        fun zoomAt(cx: Float, cy: Float, newScale: Float, panX: Float = 0f, panY: Float = 0f) {
            val ns = newScale.coerceIn(1f, 5f)
            val f = ns / scale
            offsetX = ((cx - (cx - offsetX) * f) + panX).coerceIn(viewW - viewW * ns, 0f)
            offsetY = ((cy - (cy - offsetY) * f) + panY).coerceIn(viewH - viewH * ns, 0f)
            scale = ns
            if (ns == 1f) { offsetX = 0f; offsetY = 0f }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
                // Two fingers: zoom (1–5×) around the point between the fingers and move in all directions.
                // One finger: scrolls the pages; when zoomed it also moves sideways.
                .pointerInput(file) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) {
                                val c = event.calculateCentroid(useCurrent = true)
                                val pan = event.calculatePan()
                                zoomAt(c.x, c.y, scale * event.calculateZoom(), pan.x, pan.y)
                                event.changes.forEach { it.consume() }
                            } else if (pressed == 1 && scale > 1f) {
                                val pan = event.calculatePan()
                                offsetX = (offsetX + pan.x).coerceIn(viewW - viewW * scale, 0f)
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                // Double tap: zoom in at that spot (2.5×), double tap again: back to normal.
                .pointerInput(file) {
                    detectTapGestures(onDoubleTap = { p -> if (scale > 1f) zoomAt(p.x, p.y, 1f) else zoomAt(p.x, p.y, 2.5f) })
                }
                .graphicsLayer {
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                    scaleX = scale; scaleY = scale; translationX = offsetX; translationY = offsetY
                },
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(count) { i -> PdfPageImage(renderer, mutex, i, widthPx) }
        }
    }
}

@Composable
private fun PdfPageImage(renderer: PdfRenderer, mutex: Mutex, index: Int, widthPx: Int) {
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(renderer, index, widthPx) {
        val b = withContext(Dispatchers.IO) {
            runCatching {
                mutex.withLock {
                    renderer.openPage(index).use { p ->
                        val w = widthPx
                        val h = (w.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(AColor.WHITE)
                            p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }.getOrNull()
        }
        bmp = b
    }
    DisposableEffect(Unit) { onDispose { val b = bmp; bmp = null; b?.recycle() } }
    val b = bmp
    Box(
        Modifier.widthIn(max = 900.dp).fillMaxWidth().aspectRatio(b?.let { it.width.toFloat() / it.height } ?: (1f / 1.414f))
            .shadow(25.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp)).background(Color.White),
    ) {
        if (b != null && !b.isRecycled) Image(b.asImageBitmap(), "Seite ${index + 1}", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize())
    }
}
