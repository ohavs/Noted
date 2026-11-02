package com.ohav.noted.ui.editor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.ohav.noted.data.BlockType
import com.ohav.noted.data.Media
import com.ohav.noted.data.MediaExtra
import com.ohav.noted.design.Haptics
import com.ohav.noted.design.LocalOverlays
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Ph
import com.ohav.noted.design.SheetRow
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.ToastState
import com.ohav.noted.ui.media.ImageViewer
import com.ohav.noted.ui.media.PicGrid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * What the editor's media buttons do: pickers and the camera, the document scanner, and the
 * full-screen pieces (picture viewer, recorder, drawing pad). Every request remembers the line
 * that had focus ([anchor]) so the result lands there even though focus moved meanwhile.
 */
@Stable
class MediaHost(
    val state: EditorState,
    val toasts: ToastState,
    val haptics: Haptics,
    private val scope: CoroutineScope,
    private val ctx: Context,
) {
    var anchor: String? = null
    var viewer by mutableStateOf<Pair<String, Int>?>(null)

    internal var launchGallery: () -> Unit = {}
    internal var launchCamera: () -> Unit = {}
    internal var launchScan: () -> Unit = {}

    private fun mark() { anchor = state.focused }

    fun gallery() { mark(); launchGallery() }
    fun camera() { mark(); launchCamera() }
    fun scan() { mark(); launchScan() }

    internal fun addUris(uris: List<Uri>, doc: Boolean) {
        if (uris.isEmpty()) return
        val at = anchor
        scope.launch {
            val pics = uris.mapNotNull { Media.importImage(ctx, it, doc) }
            if (pics.isEmpty()) toasts.show("לא הצלחנו לטעון את התמונה", Ph.WarningCircle)
            else { state.addPics(at, pics); haptics.confirm() }
        }
    }
}

val LocalMedia = staticCompositionLocalOf<MediaHost?> { null }

@Composable
fun rememberMediaHost(state: EditorState, toasts: ToastState): MediaHost {
    val ctx = LocalContext.current
    val haptics = Noted.haptics
    val scope = rememberCoroutineScope()
    val host = remember(state) { MediaHost(state, toasts, haptics, scope, ctx.applicationContext) }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(12)) { host.addUris(it, doc = false) }
    var shot by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = shot
        if (ok && u != null) host.addUris(listOf(u), doc = false)
    }
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val pages = GmsDocumentScanningResult.fromActivityResultIntent(r.data)?.pages?.map { it.imageUri }.orEmpty()
            host.addUris(pages, doc = true)
        }
    }

    host.launchGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    host.launchCamera = {
        runCatching {
            val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
            val f = File(dir, "shot-${System.currentTimeMillis()}.jpg")
            val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            shot = u
            camera.launch(u)
        }.onFailure { toasts.show("המצלמה לא זמינה", Ph.WarningCircle) }
    }
    host.launchScan = {
        val act = ctx.findActivity()
        if (act == null) toasts.show("הסורק לא זמין", Ph.WarningCircle)
        else {
            val opts = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(20)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            GmsDocumentScanning.getClient(opts).getStartScanIntent(act)
                .addOnSuccessListener { scanner.launch(IntentSenderRequest.Builder(it).build()) }
                .addOnFailureListener { toasts.show("הסורק לא זמין במכשיר הזה", Ph.WarningCircle) }
        }
    }
    return host
}

/** The picture sheet: camera, gallery, document scan. */
fun MediaHost.imageSheet(overlays: com.ohav.noted.design.Overlays) {
    anchor = state.focused
    overlays.sheet.show("תמונה") {
        SheetRow(Ph.Camera, "צילום") { overlays.sheet.close(); launchCamera() }
        SheetRow(Ph.Images, "מהגלריה") { overlays.sheet.close(); launchGallery() }
        SheetRow(Ph.Scan, "סריקת מסמך", last = true) { overlays.sheet.close(); launchScan() }
    }
}

/** A media block inside the editor. [armed] = marked by a backspace, the next one removes it. */
@Composable
fun MediaBlock(state: EditorState, b: EdBlock, armed: Boolean) {
    val c = Noted.colors
    val host = LocalMedia.current
    val ring = Modifier.drawBehind {
        if (armed) {
            val o = Squircle(18.dp).createOutline(size, layoutDirection, this)
            drawOutline(o, c.accent, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
        }
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).then(ring).padding(if (armed) 4.dp else 0.dp)) {
        when (b.type) {
            BlockType.Images -> PicGrid(MediaExtra.pics(b.extra), onClick = { i -> host?.viewer = b.id to i })
            else -> Unit
        }
    }
}

/** Full-screen media layers over the editor. */
@Composable
fun MediaOverlays(host: MediaHost) {
    val v = host.viewer ?: return
    val b = host.state.blocks.firstOrNull { it.id == v.first }
    if (b == null) { androidx.compose.runtime.LaunchedEffect(v) { host.viewer = null }; return }
    ImageViewer(
        pics = MediaExtra.pics(b.extra),
        start = v.second,
        onClose = { host.viewer = null },
        onRemove = { i ->
            host.state.removePic(b.id, i)
            host.toasts.show("התמונה הוסרה", Ph.Trash, action = "ביטול", millis = 4000) { host.state.undo() }
        },
    )
}
