package com.joecell.fileexplorer

import android.content.Intent
import android.content.ClipData
import android.os.Bundle
import android.os.Build
import android.os.StatFs
import android.os.Environment
import android.provider.Settings
import android.net.Uri
import android.webkit.MimeTypeMap
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import java.text.DecimalFormat
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.security.MessageDigest

private fun detectedFileMime(fileName: String, providerMime: String?): String {
    val supplied = providerMime.orEmpty().trim().lowercase()
    if (supplied.isNotBlank() && supplied != "application/octet-stream" && supplied != "binary/octet-stream") return supplied
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val known = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "jpe" to "image/jpeg", "jfif" to "image/jpeg",
        "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp", "heic" to "image/heic",
        "heif" to "image/heif", "bmp" to "image/bmp", "tif" to "image/tiff", "tiff" to "image/tiff",
        "avif" to "image/avif", "svg" to "image/svg+xml",
        "pdf" to "application/pdf",
        "mp4" to "video/mp4", "m4v" to "video/x-m4v", "mov" to "video/quicktime",
        "mkv" to "video/x-matroska", "webm" to "video/webm", "avi" to "video/x-msvideo",
        "3gp" to "video/3gpp", "3g2" to "video/3gpp2", "mpeg" to "video/mpeg", "mpg" to "video/mpeg",
        "ts" to "video/mp2t", "mts" to "video/mp2t",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/wav",
        "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/opus", "flac" to "audio/flac",
        "wma" to "audio/x-ms-wma",
        "doc" to "application/msword", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint", "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "txt" to "text/plain", "log" to "text/plain", "csv" to "text/csv", "json" to "application/json",
        "xml" to "application/xml", "rtf" to "application/rtf",
        "zip" to "application/zip", "rar" to "application/vnd.rar", "7z" to "application/x-7z-compressed",
        "apk" to "application/vnd.android.package-archive"
    )
    return known[extension]
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.lowercase()
        ?: "*/*"
}

private fun fileTypeIcon(file: DocumentFile): androidx.compose.ui.graphics.vector.ImageVector {
    if (file.isDirectory) return Icons.Default.Folder
    val name = file.name.orEmpty().lowercase()
    val mime = detectedFileMime(file.name.orEmpty(), file.type).lowercase()
    return when {
        mime.startsWith("image/") || listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".bmp", ".svg").any(name::endsWith) -> Icons.Default.Image
        mime == "application/pdf" || name.endsWith(".pdf") -> Icons.Default.PictureAsPdf
        mime.startsWith("video/") || listOf(".mp4", ".mkv", ".mov", ".webm", ".avi", ".3gp").any(name::endsWith) -> Icons.Default.VideoFile
        mime.startsWith("audio/") || listOf(".mp3", ".m4a", ".aac", ".wav", ".ogg", ".flac", ".opus").any(name::endsWith) -> Icons.Default.AudioFile
        listOf(".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".rtf", ".csv").any(name::endsWith) -> Icons.Default.Description
        name.endsWith(".apk") -> Icons.Default.Android
        name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".7z") -> Icons.Default.FolderZip
        else -> Icons.Default.InsertDriveFile
    }
}

@Composable
private fun OfficePreviewContent(fileName: String, content: String) {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val lines = content.lines().filter { it.isNotBlank() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(color = Accent.copy(alpha = 0.10f), shape = RoundedCornerShape(10.dp)) {
                Text(extension.uppercase(), color = Accent, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                when (extension) { "docx" -> "Word document"; "xlsx" -> "Spreadsheet"; "pptx" -> "Presentation"; else -> "Office document" },
                color = Muted, style = MaterialTheme.typography.labelMedium
            )
        }
        when (extension) {
            "pptx" -> {
                val slides = mutableListOf<Pair<String, String>>()
                var slideTitle = "Slide 1"
                var slideBody = StringBuilder()
                lines.forEach { line ->
                    if (line.matches(Regex("Slide\\s+\\d+", RegexOption.IGNORE_CASE))) {
                        if (slideBody.isNotEmpty()) slides += slideTitle to slideBody.toString().trim()
                        slideTitle = line
                        slideBody = StringBuilder()
                    } else slideBody.append(line).append('\n')
                }
                if (slideBody.isNotEmpty()) slides += slideTitle to slideBody.toString().trim()
                slides.forEach { (title, body) ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(title, color = Accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            HorizontalDivider(color = SurfaceSoft)
                            Text(body.ifBlank { "This slide contains no extractable text." }, color = Ink, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
            "xlsx" -> {
                lines.forEach { line ->
                    if (line.startsWith("Sheet ", true)) {
                        Text(line, color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    } else {
                        val cells = line.split(" | ")
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(10.dp)) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                cells.forEach { cell ->
                                    Surface(color = SurfaceSoft, shape = RoundedCornerShape(6.dp)) {
                                        Text(cell, color = Ink, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        lines.forEach { line ->
                            Text(line, color = Ink, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
        Text("Read-only preview. Original file remains unchanged.", color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}

private fun stageForExternalApp(context: android.content.Context, sourceUri: Uri, displayName: String): Uri {
    val safeName = displayName.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "shared-file" }
    val shareDir = File(context.cacheDir, "external-share").apply { mkdirs() }
    val staged = File(shareDir, "${System.currentTimeMillis()}_$safeName")
    context.contentResolver.openInputStream(sourceUri)?.use { input ->
        staged.outputStream().use { output -> input.copyTo(output) }
    } ?: throw IllegalStateException("Android could not read this file for sharing")
    if (!staged.isFile || staged.length() == 0L) throw IllegalStateException("The selected file is empty or unreadable")
    return androidx.core.content.FileProvider.getUriForFile(
        context, context.packageName + ".fileprovider", staged
    )
}

private fun hasAllFilesAccess(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

private val Ink = Color(0xFF20242B)
private val Muted = Color(0xFF777F8A)
private val Accent = Color(0xFF536DFE)
private val AccentCyan = Color(0xFF00B8D4)
private val AccentViolet = Color(0xFF8B5CF6)
private val SurfaceSoft = Color(0xFFF3F6FA)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Accent, background = Color(0xFFF8FAFD), surface = Color.White)) { FileHome() } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileHome() {
    val context = LocalContext.current
    var root by remember { mutableStateOf<DocumentFile?>(null) }
    var folderStack by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var accessStatus by remember { mutableStateOf("Allow all files access once to automatically scan shared storage") }
    val accessPreferences = remember { context.getSharedPreferences("storage_access", 0) }
    var showStoragePrompt by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!hasAllFilesAccess() && accessPreferences.getString("tree_uri", null) == null) showStoragePrompt = true
        if (hasAllFilesAccess()) {
            val sharedRoot = DocumentFile.fromFile(Environment.getExternalStorageDirectory())
            root = sharedRoot
            folderStack = listOf(sharedRoot)
            accessStatus = "All files access is active. Shared storage is ready."
        }
        val saved = accessPreferences.getString("tree_uri", null)
        if (!Environment.isExternalStorageManager()) {
        if (saved != null) {
            val savedUri = runCatching { Uri.parse(saved) }.getOrNull()
            val persisted = savedUri?.let { uri ->
                context.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission && it.isWritePermission
                }
            } == true
            val restored = if (persisted && savedUri != null) {
                runCatching { DocumentFile.fromTreeUri(context, savedUri) }.getOrNull()
            } else null
            if (restored != null && runCatching { isUsableTreeGrant(persisted, persisted, restored.exists(), restored.isDirectory, restored.canRead(), restored.canWrite()) }.getOrDefault(false)) {
                root = restored
                folderStack = listOf(restored)
                accessStatus = "Folder access is active and will remain available after restart"
            } else {
                accessPreferences.edit().remove("tree_uri").apply()
                accessStatus = "Previous folder access expired. Choose the folder again to restore access"
            }
        }
        }
    }
    var showHiddenFiles by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var categoryFilter by remember { mutableStateOf("All") }
    var recursiveSearchResults by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var categorySearchCache by remember { mutableStateOf<Map<String, List<DocumentFile>>>(emptyMap()) }
    var fileActionMenu by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val privateSpaceStore = remember { PrivateSpaceStore(context) }
    var privateSpaceIds by remember { mutableStateOf(privateSpaceStore.listIds()) }
    var privateSpaceNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pendingPrivateExportId by remember { mutableStateOf<String?>(null) }
    var privateDeleteTarget by remember { mutableStateOf<String?>(null) }
    var privateSpaceUnlocked by remember { mutableStateOf(false) }
    var privatePinDialog by remember { mutableStateOf(false) }
    var privatePinEntry by remember { mutableStateOf("") }
    var privatePinError by remember { mutableStateOf<String?>(null) }
    val privateSpacePreferences = remember { context.getSharedPreferences("private_space_lock", 0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                privateSpaceUnlocked = false
                privateSpaceNames = emptyMap()
                if (tab == 5) tab = 0
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val privateImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && privateSpaceUnlocked) {
            val originalName = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: DocumentFile.fromSingleUri(context, uri)?.name ?: "Private file"
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    privateSpaceStore.importStream(input, originalName)
                } ?: error("Cannot read selected file")
            }.onSuccess {
                privateSpaceIds = privateSpaceStore.listIds()
                feedback = "File encrypted and added to Private Space"
            }.onFailure { feedback = it.message ?: "Could not import file" }
        }
    }
    val storageSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Environment.isExternalStorageManager()) {
            val sharedRoot = DocumentFile.fromFile(Environment.getExternalStorageDirectory())
            root = sharedRoot
            folderStack = listOf(sharedRoot)
            accessStatus = "All files access is active. Shared storage is ready."
            tab = 1
            refresh++
        } else {
            accessStatus = "All files access is required to automatically display shared storage."
        }
    }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sortBy by remember { mutableStateOf("Name") }
    var descending by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<DocumentFile?>(null) }
    var moving by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var operationJob by remember { mutableStateOf<Job?>(null) }
    var operationName by remember { mutableStateOf("Working") }
    var operationDetail by remember { mutableStateOf("") }
    var viewMode by remember { mutableStateOf("List") }
    var detailsFile by remember { mutableStateOf<DocumentFile?>(null) }
    var confirmClearList by remember { mutableStateOf<String?>(null) }
    var trashDeleteTarget by remember { mutableStateOf<DocumentFile?>(null) }
    var previewTitle by remember { mutableStateOf<String?>(null) }
    var previewText by remember { mutableStateOf<String?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewZoom by remember { mutableFloatStateOf(1f) }
    var previewPdfUri by remember { mutableStateOf<Uri?>(null) }
    var previewZipUri by remember { mutableStateOf<Uri?>(null) }
    var previewZipApks by remember { mutableStateOf<List<String>>(emptyList()) }
    var previewRarUri by remember { mutableStateOf<Uri?>(null) }
    var pendingApkInstall by remember { mutableStateOf<File?>(null) }
    val installPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val staged = pendingApkInstall
        pendingApkInstall = null
        if (staged != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && context.packageManager.canRequestPackageInstalls()) {
            runCatching { launchStagedApkInstaller(context, staged) }
                .onFailure { feedback = "Android could not open the APK installer: " + (it.message ?: "unknown error") }
        } else if (staged != null) {
            feedback = "Allow File Explorer to install unknown apps in Android settings, then tap the APK again."
        }
    }
    fun installStagedApk(staged: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            pendingApkInstall = staged
            val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
            runCatching { installPermissionLauncher.launch(settingsIntent) }
                .onFailure { feedback = "Open Android Settings and allow File Explorer to install unknown apps." }
        } else {
            runCatching { launchStagedApkInstaller(context, staged) }
                .onFailure { feedback = "Android could not open the APK installer: " + (it.message ?: "unknown error") }
        }
    }
    var rarPassword by remember { mutableStateOf("") }
    var analysisReport by remember { mutableStateOf<String?>(null) }
    var pdfPage by remember { mutableIntStateOf(0) }
    var pdfPageCount by remember { mutableIntStateOf(0) }
    var favourites by remember { mutableStateOf(context.getSharedPreferences("file_favourites", 0).getStringSet("uris", emptySet()).orEmpty()) }
    var recent by remember { mutableStateOf(context.getSharedPreferences("file_history", 0).getString("items", "").orEmpty().lines().filter { it.isNotBlank() }) }
    val scope = rememberCoroutineScope()
    val privateExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = pendingPrivateExportId
        pendingPrivateExportId = null
        if (uri != null && id != null) {
            operationName = "Exporting from Private Space"
            operationJob = scope.launch {
                working = true
                try {
                    val result = withContext(Dispatchers.IO) {
                        val temp = privateSpaceStore.exportToTemp(id, context.cacheDir)
                        try {
                            val output = context.contentResolver.openOutputStream(uri) ?: error("Cannot write to selected destination")
                            output.use { sink -> temp.inputStream().use { source -> source.copyTo(sink) } }
                        } finally { temp.delete() }
                    }
                    feedback = "Private file exported successfully"
                } catch (error: Exception) {
                    feedback = "Private file export failed: " + (error.message ?: "unknown error")
                } finally {
                    working = false
                    operationJob = null
                }
            }
        }
    }
    LaunchedEffect(privateSpaceUnlocked, privateSpaceIds) {
        privateSpaceNames = if (!privateSpaceUnlocked) emptyMap() else withContext(Dispatchers.IO) {
            privateSpaceIds.associateWith { id -> privateSpaceStore.displayName(id) ?: "Encrypted file" }
        }
    }

    fun rememberOpened(file: DocumentFile) {
        val entry = listOf(file.uri.toString(), file.name.orEmpty(), file.type.orEmpty()).joinToString("\t")
        recent = (listOf(entry) + recent.filterNot { it.substringBefore('\t') == file.uri.toString() }).take(30)
        context.getSharedPreferences("file_history", 0).edit().putString("items", recent.joinToString("\n")).apply()
    }
    fun toggleFavourite(file: DocumentFile) {
        val uri = file.uri.toString()
        favourites = if (uri in favourites) favourites - uri else favourites + uri
        context.getSharedPreferences("file_favourites", 0).edit().putStringSet("uris", favourites).apply()
    }

    fun currentVisibleSelection(): List<DocumentFile> {
        val candidates = if (query.isBlank() && categoryFilter == "All") root?.listFiles()?.toList().orEmpty() else recursiveSearchResults
        return candidates.filter { it.uri.toString() in selected && (showHiddenFiles || !it.name.orEmpty().startsWith(".")) }
    }

    fun shareDocuments(files: List<DocumentFile>) {
        val shareable = files.filter { !it.isDirectory }
        if (shareable.isEmpty()) {
            feedback = "Select at least one file to share"
            return
        }
        scope.launch {
            val uris = withContext(Dispatchers.IO) {
                shareable.map { file ->
                    stageForExternalApp(context, file.uri, file.name ?: "shared-file")
                }
            }
            val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND) else Intent(Intent.ACTION_SEND_MULTIPLE)
            intent.type = "*/*"
            if (uris.size == 1) intent.putExtra(Intent.EXTRA_STREAM, uris.first())
            else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            intent.clipData = ClipData.newUri(context.contentResolver, "Shared files", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(Intent.createChooser(intent, "Share files")) }
                .onFailure { feedback = "Could not share files: " + (it.message ?: "no compatible app") }
        }
    }
    fun renderPdfPage(uri: Uri, pageIndex: Int): Pair<Bitmap?, Int> {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return Pair(null, 0)
        return descriptor.use { pfd -> PdfRenderer(pfd).use { renderer ->
            val count = renderer.pageCount
            if (count == 0) return Pair(null, count)
            val index = pageIndex.coerceIn(0, count - 1)
            val bitmap = renderer.openPage(index).use { page ->
                val scale = (1400f / page.width.coerceAtLeast(1)).coerceAtMost(2f)
                val image = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                image.eraseColor(AndroidColor.WHITE)
                page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                image
            }
            Pair(bitmap, count)
        } }
    }

    fun launchExternalViewer(uri: Uri, suppliedMime: String, fileName: String) {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val mime = detectedFileMime(fileName, suppliedMime)
        scope.launch {
            runCatching {
                val shareUri = withContext(Dispatchers.IO) { stageForExternalApp(context, uri, fileName) }
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(shareUri, mime)
                    .setClipData(ClipData.newUri(context.contentResolver, fileName, shareUri))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val handlers = context.packageManager.queryIntentActivities(intent, 0)
                when (handlers.size) {
                    0 -> feedback = "No compatible app is installed for .$extension files. Install a viewer/player and try again."
                    1 -> context.startActivity(intent)
                    else -> context.startActivity(Intent.createChooser(intent, "Open ${fileName.ifBlank { "file" }} with"))
                }
            }.onFailure {
                feedback = "Could not open this file with another app: " + (it.message ?: "unknown error")
            }
        }
    }

    fun openFile(file: DocumentFile) {
        rememberOpened(file)
        previewBitmap = null
        previewText = null
        previewPdfUri = null
        previewZipUri = null
        previewRarUri = null
        previewZipApks = emptyList()
        previewTitle = file.name ?: "Preview"
        previewZoom = 1f
        pdfPage = 0
        pdfPageCount = 0
        val name = file.name.orEmpty()
        val mime = detectedFileMime(name, file.type)
        if (mime.startsWith("image/") && mime != "image/svg+xml") {
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) { runCatching {
                    val resolver = context.contentResolver
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(file.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    val maxSide = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
                    val sample = Integer.highestOneBit((maxSide / 1600).coerceAtLeast(1))
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    resolver.openInputStream(file.uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                }.getOrNull() }
                previewBitmap = bitmap
                previewZoom = 1f
                previewText = if (bitmap == null) "Unable to display this image." else null
                previewTitle = file.name ?: "Image preview"
            }
        } else if (mime == "application/pdf" || name.endsWith(".pdf", true)) {
            previewBitmap = null
            previewZoom = 1f
            previewText = "Loading PDF…"
            pdfPageCount = 0
            previewPdfUri = file.uri
            pdfPage = 0
            scope.launch {
                val rendered = withContext(Dispatchers.IO) { runCatching { renderPdfPage(file.uri, 0) }.getOrNull() }
                previewBitmap = rendered?.first
                pdfPageCount = rendered?.second ?: 0
                previewText = if (previewBitmap == null) "Unable to render this PDF page." else null
                previewTitle = file.name ?: "PDF preview"
            }
        } else if (name.endsWith(".zip", true) || mime == "application/zip" || mime == "application/x-zip-compressed") {
            scope.launch {
                val listing = withContext(Dispatchers.IO) { runCatching { readZipListing(context, file.uri) }.getOrNull() }
                val apks = withContext(Dispatchers.IO) { runCatching { listZipApkEntries(context, file.uri) }.getOrDefault(emptyList()) }
                previewZipUri = file.uri
                previewZipApks = apks
                previewTitle = name.ifBlank { "ZIP archive" }
                previewText = listing ?: "Unable to read this ZIP archive."
            }
        } else if (name.endsWith(".rar", true) || mime == "application/vnd.rar" || mime == "application/x-rar-compressed") {
            scope.launch {
                val listing = withContext(Dispatchers.IO) { runCatching { readRarListing(context, file.uri) }.getOrNull() }
                previewRarUri = file.uri
                previewTitle = name.ifBlank { "RAR archive" }
                previewText = listing ?: "Unable to read this RAR archive."
            }
        } else if (name.endsWith(".apk", true)) {
            scope.launch {
                val staged = withContext(Dispatchers.IO) { runCatching { stageApkFromUri(context, file.uri) }.getOrNull() }
                if (staged != null) {
                    runCatching {
                        installStagedApk(staged)
                    }.onFailure { feedback = "Android could not open the APK installer: " + (it.message ?: "unknown error") }
                } else feedback = "Could not read this APK. It may be empty or larger than 512 MB."
            }
        } else if (name.endsWith(".docx", true) || name.endsWith(".xlsx", true) || name.endsWith(".pptx", true)) {
            scope.launch {
                val content = withContext(Dispatchers.IO) {
                    runCatching { readOfficePreview(context, file.uri, name) }.getOrElse { "Unable to preview this Office document: " + (it.message ?: "read error") }
                }
                previewTitle = name
                previewText = content
            }
        } else if (mime.startsWith("text/") || file.name.orEmpty().endsWith(".txt", true) || file.name.orEmpty().endsWith(".log", true) || file.name.orEmpty().endsWith(".json", true) || file.name.orEmpty().endsWith(".xml", true) || file.name.orEmpty().endsWith(".csv", true)) {
            scope.launch {
                val content = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader -> val buffer = CharArray(65536); val count = reader.read(buffer); if (count <= 0) "" else String(buffer, 0, count) } }.getOrNull() }
                previewTitle = file.name ?: "Text preview"
                previewText = content ?: "Unable to read this file."
            }
        } else {
            launchExternalViewer(file.uri, mime, name)
        }
    }

    val createZipPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            val sources = currentVisibleSelection()
            operationName = "Creating ZIP"
            operationJob = scope.launch {
                working = true
                try {
                    val result = withContext(Dispatchers.IO) { createZipArchive(context, sources, uri) }
                    feedback = result
                    if (result.startsWith("Created")) selected = emptySet()
                } catch (cancelled: CancellationException) {
                    feedback = "ZIP creation cancelled. The incomplete archive was removed where the storage provider allows it."
                } catch (error: Exception) {
                    feedback = "ZIP creation failed: " + (error.message ?: "unable to read selected files")
                } finally {
                    working = false
                    operationJob = null
                }
            }
        }
    }
    val analysisPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val readSaved = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            }.getOrDefault(false)
            val folder = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
            if (folder != null && runCatching { isUsableTreeGrant(readSaved, false, folder.exists(), folder.isDirectory, folder.canRead(), false, requireWrite = false) }.getOrDefault(false)) {
                operationName = "Analyzing storage"
                operationJob = scope.launch {
                    working = true
                    try {
                        analysisReport = withContext(Dispatchers.IO) { analyzeStorageTree(context, folder) }
                    } catch (cancelled: CancellationException) {
                        feedback = "Storage analysis cancelled."
                        throw cancelled
                    } catch (error: Exception) {
                        analysisReport = "Analysis failed: " + (error.message ?: "unable to scan folder")
                    } finally {
                        working = false
                        operationJob = null
                    }
                }
            } else {
                feedback = "Storage analysis needs persistent read access to the selected folder."
            }
        }
    }
    val zipDestinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val zipArchiveUri = previewZipUri
        val rarArchiveUri = previewRarUri
        if (uri != null && (zipArchiveUri != null || rarArchiveUri != null)) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val permissionSaved = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
                context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
            }.getOrDefault(false)
            val destination = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
            if (destination != null && runCatching { isUsableTreeGrant(permissionSaved, permissionSaved, destination.exists(), destination.isDirectory, destination.canRead(), destination.canWrite()) }.getOrDefault(false)) {
                operationName = "Extracting ZIP"
                operationJob = scope.launch {
                    working = true
                    try {
                        feedback = withContext(Dispatchers.IO) {
                            if (zipArchiveUri != null) extractZipSafely(context, zipArchiveUri, destination)
                            else extractRarSafely(context, rarArchiveUri!!, destination, rarPassword)
                        }
                        refresh++
                    } catch (cancelled: CancellationException) {
                        feedback = "ZIP extraction cancelled. Newly created files and folders from this extraction were rolled back."
                    } catch (error: Exception) {
                        feedback = "Extraction failed: " + (error.message ?: "unable to extract archive")
                    } finally {
                        working = false
                        operationJob = null
                    }
                }
            } else {
                feedback = "ZIP extraction needs persistent read and write access to the destination folder."
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
                context.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission && it.isWritePermission
                }
            }.getOrDefault(false)
            val selectedRoot = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
            val usable = selectedRoot != null && runCatching {
                isUsableTreeGrant(persisted, persisted, selectedRoot.exists(), selectedRoot.isDirectory, selectedRoot.canRead(), selectedRoot.canWrite())
            }.getOrDefault(false)
            if (persisted && usable) {
                accessPreferences.edit().putString("tree_uri", uri.toString()).apply()
                root = selectedRoot
                folderStack = listOfNotNull(selectedRoot)
                accessStatus = "Folder access is active and will remain available after restart"
                tab = if (tab == 4) 4 else 1
                categoryFilter = "All"
                query = ""
                selected = emptySet()
            } else {
                root = null
                folderStack = emptyList()
                accessStatus = "Could not keep read and write access to that folder. Please choose another folder"
                feedback = accessStatus
            }
        }
    }
    fun navigateInto(folder: DocumentFile) {
        folderStack = folderStack + folder
        root = folder
        query = ""
        selected = emptySet()
        categoryFilter = "All"
    }
    fun navigateToBreadcrumb(index: Int) {
        folderStack = folderStack.take(index + 1)
        root = folderStack.lastOrNull()
        query = ""
        selected = emptySet()
        categoryFilter = "All"
    }
    fun navigateBackFolder() {
        if (folderStack.size > 1) navigateToBreadcrumb(folderStack.lastIndex - 1)
    }
    BackHandler(enabled = tab != 0 || (tab == 1 && folderStack.size > 1)) {
        when {
            tab == 1 && folderStack.size > 1 -> navigateBackFolder()
            tab == 5 -> { privateSpaceUnlocked = false; tab = 0 }
            else -> { tab = 0; selected = emptySet(); query = ""; categoryFilter = "All" }
        }
    }

    fun openCategory(category: String) {
        categoryFilter = category
        tab = 1
        selected = emptySet()
        query = ""
        if (root == null) picker.launch(null)
    }

    val destinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val permissionSaved = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
                context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
            }.getOrDefault(false)
            val destination = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
            val destinationUsable = destination != null && runCatching {
                isUsableTreeGrant(permissionSaved, permissionSaved, destination.exists(), destination.isDirectory, destination.canRead(), destination.canWrite())
            }.getOrDefault(false)
            if (destination != null && destinationUsable) {
                val sources = currentVisibleSelection()
                val shouldMove = moving
                operationName = if (moving) "Moving files" else "Copying files"
                operationJob = scope.launch {
                    working = true
                    var copied = 0
                    var failed = 0
                    try {
                        val unsafeDestination = withContext(Dispatchers.IO) {
                            sources.any { source ->
                                !isSafeCopyDestination(source.uri.toString(), destination.uri.toString(), source.isDirectory, source.isDirectory && containsDocumentUri(source, destination.uri))
                            }
                        }
                        if (unsafeDestination) {
                            feedback = "Cannot copy or move a folder into itself or one of its subfolders"
                        } else {
                            sources.forEachIndexed { index, source ->
                                operationDetail = "${if (shouldMove) "Moving" else "Copying"} ${index + 1} of ${sources.size}: ${source.name ?: "item"}"
                                val ok = withContext(Dispatchers.IO) { copyDocument(context, source, destination) }
                                if (ok) {
                                    copied++
                                    if (shouldMove) {
                                        val deleted = withContext(Dispatchers.IO) { source.delete() }
                                        if (!deleted) failed++
                                    }
                                } else failed++
                            }
                            selected = emptySet()
                            refresh++
                            feedback = (if (shouldMove) "Moved " else "Copied ") + copied + " item(s)" + if (failed > 0) "; " + failed + " failed" else ""
                        }
                    } catch (cancelled: CancellationException) {
                        feedback = "Operation cancelled. Completed: $copied; failed: $failed. Check the destination for any partially copied folder."
                    } finally {
                        working = false
                        operationDetail = ""
                        operationJob = null
                    }
                }
            } else {
                feedback = "Destination folder does not provide persistent read and write access. Choose another folder."
            }
        }
    }
    val allFiles = remember(root, refresh, sortBy, descending, showHiddenFiles) {
        val all = root?.listFiles()?.filter { it.name != ".FileExplorerTrash" && (showHiddenFiles || !it.name.orEmpty().startsWith(".")) }?.toList().orEmpty()
        val ordered = when (sortBy) {
            "Size" -> all.sortedBy { it.length() }
            "Type" -> all.sortedBy { it.type.orEmpty().lowercase() }
            "Modified" -> all.sortedBy { it.lastModified() }
            else -> all.sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name.orEmpty().lowercase() })
        }
        if (descending) ordered.reversed() else ordered
    }
    val searchRoot = folderStack.firstOrNull() ?: root
    LaunchedEffect(searchRoot, query, categoryFilter, refresh, sortBy, descending, showHiddenFiles) {
        val folder = searchRoot
        val term = query.trim()
        if (folder == null || (term.isEmpty() && categoryFilter == "All")) {
            recursiveSearchResults = emptyList()
            searching = false
        } else {
            searching = true
            recursiveSearchResults = emptyList()
            try {
                // Debounce rapid typing; each settled query still scans the selected storage root.
                kotlinx.coroutines.delay(250)
                currentCoroutineContext().ensureActive()
                val found = withContext(Dispatchers.IO) { searchDocuments(folder, term, categoryFilter, showHiddenFiles) }
                currentCoroutineContext().ensureActive()
                val ordered = when (sortBy) {
                    "Size" -> found.sortedBy { it.length() }
                    "Type" -> found.sortedBy { it.type.orEmpty().lowercase() }
                    "Modified" -> found.sortedBy { it.lastModified() }
                    else -> found.sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name.orEmpty().lowercase() })
                }
                recursiveSearchResults = if (descending) ordered.reversed() else ordered
                searching = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recursiveSearchResults = emptyList()
                searching = false
                feedback = "Search failed: " + (error.message ?: "storage could not be read")
            }
        }
    }
    val shownFiles = if (query.isBlank() && categoryFilter == "All") allFiles else recursiveSearchResults.filter { showHiddenFiles || !it.name.orEmpty().startsWith(".") }
    val storageRoot = folderStack.firstOrNull() ?: root
    var resolvedFileUris by remember { mutableStateOf<Map<String, DocumentFile>>(emptyMap()) }
    LaunchedEffect(storageRoot, favourites, recent, refresh) {
        val requested = favourites + recent.map { it.substringBefore('\t') }
        resolvedFileUris = withContext(Dispatchers.IO) {
            resolveDocumentsByUri(storageRoot, requested)
        }
    }
    val trashRecords = remember(storageRoot, refresh) { listTrashRecords(storageRoot) }
    val stats = remember { runCatching { StatFs(Environment.getExternalStorageDirectory().path) }.getOrNull() }
    val total = stats?.totalBytes ?: 0L
    val free = stats?.availableBytes ?: 0L
    val used = (total - free).coerceAtLeast(0L)
    val formatter = remember { DecimalFormat("#,##0.0") }

    fun reload() { categorySearchCache = emptyMap(); refresh += 1; selected = emptySet() }

    Scaffold(bottomBar = {
        NavigationBar(containerColor = Color.White) {
            NavigationBarItem(tab == 0, { tab = 0; selected = emptySet() }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
            NavigationBarItem(tab == 1, { tab = 1; if (root == null) picker.launch(null) }, icon = { Icon(Icons.Default.Folder, null) }, label = { Text("Browse") })
            NavigationBarItem(tab == 2, { tab = 2; selected = emptySet() }, icon = { Icon(Icons.Default.Schedule, null) }, label = { Text("Recent") })
            NavigationBarItem(tab == 3, { tab = 3; selected = emptySet() }, icon = { Icon(Icons.Default.Star, null) }, label = { Text("Favourites") })
        }
    }, containerColor = Color(0xFFF8FAFD)) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("FILE MANAGER", color = Muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Text(if (tab == 1) if (selected.isNotEmpty()) selected.size.toString() + " selected" else if (categoryFilter != "All") categoryFilter else "Browse files" else if (tab == 2) "Recent files" else if (tab == 3) "Favourites" else if (tab == 4) "Recycle Bin" else "Explore", style = MaterialTheme.typography.headlineLarge, color = Ink, fontWeight = FontWeight.Bold)
                    }
                    if (tab == 2 && recent.isNotEmpty()) {
                        IconButton(onClick = { confirmClearList = "recent" }) { Icon(Icons.Default.Delete, "Clear recent history", tint = Accent) }
                    } else if (tab == 3 && favourites.isNotEmpty()) {
                        IconButton(onClick = { confirmClearList = "favourites" }) { Icon(Icons.Default.Delete, "Clear favourites", tint = Accent) }
                    } else if (tab == 4 && trashRecords.isNotEmpty()) {
                        IconButton(onClick = { confirmClearList = "trash" }) { Icon(Icons.Default.DeleteForever, "Empty Recycle Bin", tint = MaterialTheme.colorScheme.error) }
                    }
                    if (tab == 1 && root != null && selected.isNotEmpty()) {
                        IconButton(onClick = { dialog = "delete" }) { Icon(Icons.Default.Delete, "Move selected items to Recycle Bin", tint = MaterialTheme.colorScheme.error) }
                        Box {
                            IconButton(onClick = { overflowMenu = true }) { Icon(Icons.Default.MoreVert, "More file actions", tint = Accent) }
                            DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                                DropdownMenuItem(text = { Text("New window") }, leadingIcon = { Icon(Icons.Default.OpenInNew, null) }, onClick = {
                                    overflowMenu = false
                                    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
                                })
                                DropdownMenuItem(text = { Text("New folder") }, leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) }, onClick = { input = ""; dialog = "new"; overflowMenu = false })
                                DropdownMenuItem(text = { Text("Sort by…") }, leadingIcon = { Icon(Icons.Default.Sort, null) }, onClick = { sortMenu = true; overflowMenu = false })
                                DropdownMenuItem(text = { Text("Select all") }, leadingIcon = { Icon(Icons.Default.SelectAll, null) }, onClick = { selected = shownFiles.map { it.uri.toString() }.toSet(); overflowMenu = false })
                                DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { shareDocuments(shownFiles.filter { it.uri.toString() in selected }); overflowMenu = false })
                                DropdownMenuItem(text = { Text("Copy to…") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { moving = false; overflowMenu = false; destinationPicker.launch(null) })
                                DropdownMenuItem(text = { Text("Move to…") }, leadingIcon = { Icon(Icons.Default.DriveFileMove, null) }, onClick = { moving = true; overflowMenu = false; destinationPicker.launch(null) })
                                DropdownMenuItem(text = { Text("Compress") }, leadingIcon = { Icon(Icons.Default.FolderZip, null) }, onClick = { overflowMenu = false; createZipPicker.launch("Archive.zip") })
                                DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = {
                                    val chosen = shownFiles.filter { it.uri.toString() in selected }
                                    overflowMenu = false
                                    if (chosen.size == 1) { target = chosen.first(); input = target?.name.orEmpty(); dialog = "rename" }
                                    else feedback = "Select exactly one item to rename"
                                })
                                DropdownMenuItem(text = { Text("Get info") }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = {
                                    val chosen = shownFiles.filter { it.uri.toString() in selected }
                                    overflowMenu = false
                                    if (chosen.size == 1) detailsFile = chosen.first() else feedback = "Select exactly one item to view details"
                                })
                                DropdownMenuItem(text = { Text(if (showHiddenFiles) "Don't show hidden files" else "Show hidden files") }, leadingIcon = { Icon(if (showHiddenFiles) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) }, onClick = { showHiddenFiles = !showHiddenFiles; overflowMenu = false; selected = emptySet() })
                                DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { overflowMenu = false; dialog = "delete" })
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                listOf("Name", "Size", "Type", "Modified").forEach { mode ->
                                    DropdownMenuItem(text = { Text("Sort by $mode") }, onClick = { sortBy = mode; sortMenu = false })
                                }
                                DropdownMenuItem(text = { Text(if (descending) "Ascending" else "Descending") }, onClick = { descending = !descending; sortMenu = false })
                            }
                        }
                    } else if (tab == 1 && root != null) {
                        IconButton(onClick = { viewMode = if (viewMode == "List") "Grid" else "List" }) { Icon(if (viewMode == "List") Icons.Default.GridView else Icons.Default.ViewList, "Change layout", tint = Accent) }
                        Box {
                            IconButton(onClick = { overflowMenu = true }) { Icon(Icons.Default.MoreVert, "More file actions", tint = Accent) }
                            DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                                DropdownMenuItem(text = { Text("New window") }, leadingIcon = { Icon(Icons.Default.OpenInNew, null) }, onClick = {
                                    overflowMenu = false
                                    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
                                })
                                DropdownMenuItem(text = { Text("New folder") }, leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) }, onClick = { input = ""; dialog = "new"; overflowMenu = false })
                                DropdownMenuItem(text = { Text("Sort by…") }, leadingIcon = { Icon(Icons.Default.Sort, null) }, onClick = { sortMenu = true; overflowMenu = false })
                                DropdownMenuItem(text = { Text("Select all") }, leadingIcon = { Icon(Icons.Default.SelectAll, null) }, onClick = { selected = shownFiles.map { it.uri.toString() }.toSet(); overflowMenu = false })
                                DropdownMenuItem(text = { Text("Get info") }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = { detailsFile = root; overflowMenu = false })
                                DropdownMenuItem(text = { Text(if (showHiddenFiles) "Don't show hidden files" else "Show hidden files") }, leadingIcon = { Icon(if (showHiddenFiles) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) }, onClick = { showHiddenFiles = !showHiddenFiles; overflowMenu = false; selected = emptySet() })
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                listOf("Name", "Size", "Type", "Modified").forEach { mode ->
                                    DropdownMenuItem(text = { Text("Sort by " + mode) }, onClick = { sortBy = mode; sortMenu = false })
                                }
                                DropdownMenuItem(text = { Text(if (descending) "Ascending" else "Descending") }, onClick = { descending = !descending; sortMenu = false })
                            }
                        }
                    } else {
                        Surface(shape = CircleShape, color = Color(0xFFE8F1FF), modifier = Modifier.size(46.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Folder, null, tint = Accent) } }
                    }
                }
            }
            item {
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search files and folders") }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, null) } }, shape = RoundedCornerShape(18.dp), singleLine = true)
            }
            if (tab == 1 && folderStack.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        folderStack.forEachIndexed { index, folder ->
                            if (index > 0) {
                                Icon(Icons.Default.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
                            }
                            TextButton(
                                onClick = { navigateToBreadcrumb(index) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    folder.name ?: if (index == 0) "Storage" else "Folder",
                                    color = if (index == folderStack.lastIndex) Accent else Muted,
                                    fontWeight = if (index == folderStack.lastIndex) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
            if (tab == 0) {
                item {
                    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Internal storage", color = Ink, fontWeight = FontWeight.SemiBold)
                            if (total > 0L) {
                                Text(formatter.format(used / 1073741824.0) + " GB used of " + formatter.format(total / 1073741824.0) + " GB", color = Muted, style = MaterialTheme.typography.bodySmall)
                                LinearProgressIndicator(progress = { (used.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp), color = Accent, trackColor = SurfaceSoft)
                                Text(formatter.format(free / 1073741824.0) + " GB available", color = Muted)
                            } else {
                                Text("Storage details unavailable", color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item { Text("Categories", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CategoryTile("Images", Icons.Default.Image, Color(0xFFEAF0FF), Accent, Modifier.weight(1f)) { openCategory("Images") }
                            CategoryTile("Videos", Icons.Default.PlayCircle, Color(0xFFE4F8FC), AccentCyan, Modifier.weight(1f)) { openCategory("Videos") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CategoryTile("Audio", Icons.Default.MusicNote, Color(0xFFF1EBFF), AccentViolet, Modifier.weight(1f)) { openCategory("Audio") }
                            CategoryTile("Documents", Icons.Default.Description, Color(0xFFEAF0FF), Accent, Modifier.weight(1f)) { openCategory("Documents") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CategoryTile("Downloads", Icons.Default.Download, Color(0xFFE7F6EF), Color(0xFF31996A), Modifier.weight(1f)) {
                                val downloads = root?.findFile("Download") ?: root?.findFile("Downloads")
                                if (downloads?.isDirectory == true) { categoryFilter = "All"; tab = 1; navigateInto(downloads) }
                                else if (root == null) picker.launch(null)
                                else feedback = "Downloads folder was not found in the selected storage location."
                            }
                            CategoryTile("APKs", Icons.Default.Android, Color(0xFFF0F2F5), Color(0xFF657181), Modifier.weight(1f)) { openCategory("APKs") }
                        }
                    }
                }
                item {
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Row(Modifier.fillMaxWidth().clickable {
                            val scanRoot = folderStack.firstOrNull() ?: root
                            if (scanRoot != null) {
                                operationName = "Analyzing storage"
                                operationJob = scope.launch {
                                    working = true
                                    try {
                                        analysisReport = withContext(Dispatchers.IO) { analyzeStorageTree(context, scanRoot) }
                                    } catch (cancelled: CancellationException) {
                                        feedback = "Storage analysis cancelled."
                                        throw cancelled
                                    } catch (error: Exception) {
                                        analysisReport = "Analysis failed: " + (error.message ?: "unable to scan storage")
                                    } finally {
                                        working = false
                                        operationJob = null
                                    }
                                }
                            } else analysisPicker.launch(null)
                        }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Analytics, null, tint = Accent, modifier = Modifier.size(30.dp))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text("Storage analysis", color = Ink, fontWeight = FontWeight.SemiBold)
                                Text("Find large files and duplicate candidates in a folder", color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = Muted)
                        }
                    }
                }
                item {
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Row(Modifier.fillMaxWidth().clickable { tab = 4; selected = emptySet() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DeleteOutline, null, tint = Accent, modifier = Modifier.size(28.dp))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text("Recycle Bin", color = Ink, fontWeight = FontWeight.SemiBold)
                                Text(if (trashRecords.isEmpty()) "Restore or permanently delete" else "${trashRecords.size} item(s) · Restore or permanently delete", color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = Muted)
                        }
                    }
                }
                item {
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF1FF))) {
                        Row(Modifier.fillMaxWidth().clickable { privatePinEntry = ""; privatePinError = null; privatePinDialog = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, null, tint = Accent, modifier = Modifier.size(28.dp))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text("Private Space", color = Ink, fontWeight = FontWeight.SemiBold)
                                Text(if (privateSpaceIds.isEmpty()) "Encrypted files · PIN protected" else "Encrypted files · PIN protected · ${privateSpaceIds.size} item(s)", color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = Muted)
                        }
                    }
                }
                item { TextButton(onClick = { if (root == null) picker.launch(null) else tab = 1 }) { Text("Browse storage") } }
            } else if (tab == 3) {
                val favouriteFiles = favourites.mapNotNull { resolvedFileUris[it] }.filter { it.exists() }
                if (favouriteFiles.isEmpty()) item { EmptyState("No favourites yet", "Star files in Browse to keep them here", Icons.Default.StarBorder) }
                else items(favouriteFiles, key = { it.uri.toString() }) { file ->
                    Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).clickable {
                        if (file.isDirectory) {
                            scope.launch {
                                val path = withContext(Dispatchers.IO) { findDocumentPath(storageRoot, file.uri) }
                                if (path != null) { folderStack = path; root = file; tab = 1; selected = emptySet(); query = ""; categoryFilter = "All" }
                                else feedback = "This folder is no longer available in the current storage location."
                            }
                        } else openFile(file)
                    }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(fileTypeIcon(file), null, tint = Accent)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(file.name ?: "Unnamed", color = Ink, fontWeight = FontWeight.Medium); Text(if (file.isDirectory) "Folder" else file.type ?: "File", color = Muted, style = MaterialTheme.typography.bodySmall) }
                        if (!file.isDirectory) IconButton(onClick = { shareDocuments(listOf(file)) }) { Icon(Icons.Default.Share, "Share file", tint = Muted) }
                        IconButton(onClick = { toggleFavourite(file) }) { Icon(Icons.Default.Star, "Remove favourite", tint = Accent) }
                    }
                }
            } else if (tab == 2) {
                if (recent.isEmpty()) item { EmptyState("No recent files yet", "Files you open will appear here", Icons.Default.Schedule) }
                else items(recent, key = { it.substringBefore('\t') }) { entry ->
                    val parts = entry.split('\t')
                    val uri = parts.getOrNull(0).orEmpty()
                    val name = parts.getOrNull(1).orEmpty()
                    val mime = parts.getOrNull(2).orEmpty()
                    Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).clickable {
                        val file = resolvedFileUris[uri] ?: DocumentFile.fromSingleUri(context, Uri.parse(uri))
                        if (file != null && file.exists()) openFile(file)
                        else if (file != null) launchExternalViewer(Uri.parse(uri), mime, name)
                        else feedback = "This recent file is no longer available. Remove it from Recent and browse to the file again."
                    }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, null, tint = Accent, modifier = Modifier.size(26.dp))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(name.ifBlank { "Recent file" }, color = Ink, fontWeight = FontWeight.Medium)
                            Text(mime.ifBlank { "File" }, color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = {
                            val file = resolvedFileUris[uri] ?: DocumentFile.fromSingleUri(context, Uri.parse(uri))
                            if (file != null && file.exists()) shareDocuments(listOf(file)) else feedback = "This recent file is no longer available."
                        }) { Icon(Icons.Default.Share, "Share file", tint = Muted) }
                        IconButton(onClick = {
                            recent = recent.filterNot { it.substringBefore('\t') == uri }
                            context.getSharedPreferences("file_history", 0).edit().putString("items", recent.joinToString("\n")).apply()
                        }) { Icon(Icons.Default.Close, "Remove from recent", tint = Muted) }
                    }
                }
            } else if (tab == 5) {
                if (!privateSpaceUnlocked) {
                    item { EmptyState("Private Space is locked", "Unlock it from the Home screen with your PIN", Icons.Default.Lock) }
                } else {
                    item {
                        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF1FF))) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Default.Security, null, tint = Accent, modifier = Modifier.size(34.dp))
                                Text("Private Space", color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                                Text("Files are encrypted with AES-GCM. Their original names are encrypted inside each vault item.", color = Muted)
                                if (privateSpaceIds.isNotEmpty()) Text("${privateSpaceIds.size} encrypted item(s)", color = Ink, fontWeight = FontWeight.SemiBold)
                                Button(onClick = { privateImportLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Add, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Add files securely")
                                }
                                OutlinedButton(onClick = { privateSpaceUnlocked = false; tab = 0 }, modifier = Modifier.fillMaxWidth()) { Text("Lock Private Space") }
                            }
                        }
                    }
                    if (privateSpaceIds.isEmpty()) {
                        item { EmptyState("Private Space is empty", "Add a file to encrypt it on this device", Icons.Default.Lock) }
                    } else {
                        items(privateSpaceIds, key = { it }) { id ->
                            Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.EnhancedEncryption, null, tint = Accent)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(privateSpaceNames[id] ?: "Encrypted file", color = Ink, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Encrypted on this device", color = Muted, style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(onClick = { pendingPrivateExportId = id; privateExportLauncher.launch(privateSpaceNames[id] ?: "Private-file") }) {
                                    Icon(Icons.Default.FileDownload, "Export file", tint = Accent)
                                }
                                IconButton(onClick = { privateDeleteTarget = id }) {
                                    Icon(Icons.Default.Delete, "Delete private file", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            } else if (tab == 4) {
                if (root == null) {
                    item { EmptyState("Choose a storage folder", "Grant access to view the Recycle Bin", Icons.Default.Delete) }
                } else if (trashRecords.isEmpty()) {
                    item { EmptyState("Recycle Bin is empty", "Deleted files and folders will appear here", Icons.Default.DeleteOutline) }
                } else {
                    items(trashRecords, key = { it.uri.toString() }) { record ->
                        val payload = record.findFile("payload")?.listFiles()?.firstOrNull()
                        val metadata = readTrashMetadata(context, record)
                        Row(
                            Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(if (payload?.isDirectory == true) Icons.Default.Folder else Icons.Default.InsertDriveFile, null, tint = Accent, modifier = Modifier.size(28.dp))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(metadata?.originalName ?: payload?.name ?: "Unavailable item", color = Ink, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (payload?.isDirectory == true) "Folder" else readableSize(payload?.length() ?: 0L), color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = {
                                val storageRoot = folderStack.firstOrNull() ?: root ?: return@TextButton
                                operationName = "Restoring item"
                                operationJob = scope.launch {
                                    working = true
                                    try {
                                        val restored = withContext(Dispatchers.IO) { restoreTrashRecord(context, storageRoot, record) }
                                        feedback = if (restored) "Item restored to its original folder" else "Could not restore item. The original folder may be missing or a file with the same name may exist."
                                        reload()
                                    } catch (cancelled: CancellationException) {
                                        feedback = "Restore cancelled. Any partial copy was rolled back."
                                    } finally {
                                        working = false
                                        operationJob = null
                                    }
                                }
                            }) { Text("Restore") }
                            IconButton(onClick = { trashDeleteTarget = record }) { Icon(Icons.Default.DeleteForever, "Permanently delete", tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            } else {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (folderStack.size > 1) {
                            IconButton(onClick = { navigateBackFolder() }) { Icon(Icons.Default.ArrowBack, "Back to parent folder", tint = Accent) }
                        }
                        Text(root?.name ?: "Select a folder", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), color = Ink)
                    }
                }
                if (root == null) item { EmptyState("Choose a folder to begin", "Android will ask you to grant access", Icons.Default.FolderOpen) }
                else if (searching && shownFiles.isEmpty()) item { EmptyState("Searching this folder", "Looking through subfolders…", Icons.Default.Search) }
                else if (shownFiles.isEmpty()) item { EmptyState("No matching files", "Try another search", Icons.Default.SearchOff) }
                else if (viewMode == "Grid") {
                    shownFiles.chunked(2).forEach { pair -> item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { file ->
                                val key = file.uri.toString()
                                val checked = key in selected
                                Card(Modifier.weight(1f).height(132.dp).combinedClickable(
                                    onClick = {
                                        if (selected.isNotEmpty()) selected = if (checked) selected - key else selected + key
                                        else if (file.isDirectory) navigateInto(file) else openFile(file)
                                    },
                                    onLongClick = { selected = selected + key }
                                ), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = if (checked) Color(0xFFE8F1FF) else Color.White)) {
                                    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            if (selected.isNotEmpty()) Checkbox(checked, { value -> selected = if (value) selected + key else selected - key })
                                            else Icon(fileTypeIcon(file), null, tint = Accent, modifier = Modifier.size(28.dp))
                                            Spacer(Modifier.weight(1f))
                                            IconButton(onClick = { toggleFavourite(file) }, modifier = Modifier.size(32.dp)) {
                                                Icon(if (key in favourites) Icons.Default.Star else Icons.Default.StarBorder, "Toggle favourite", tint = if (key in favourites) Accent else Muted, modifier = Modifier.size(19.dp))
                                            }
                                            IconButton(onClick = { detailsFile = file }, modifier = Modifier.size(32.dp)) {
                                                Icon(Icons.Default.Info, "File details", tint = Muted, modifier = Modifier.size(19.dp))
                                            }
                                        }
                                        Text(file.name ?: "Unnamed", color = Ink, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(if (file.isDirectory) "Folder" else readableSize(file.length()), color = Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    } }
                } else items(shownFiles, key = { it.uri.toString() }) { file ->
                    val key = file.uri.toString()
                    val checked = key in selected
                    Row(Modifier.fillMaxWidth().background(if (checked) Color(0xFFE8F1FF) else Color.White, RoundedCornerShape(16.dp)).combinedClickable(
                        onClick = {
                            if (selected.isNotEmpty()) selected = if (checked) selected - key else selected + key
                            else if (file.isDirectory) navigateInto(file)
                            else openFile(file)
                        },
                        onLongClick = { selected = selected + key }
                    ).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selected.isNotEmpty()) Checkbox(checked, { value -> selected = if (value) selected + key else selected - key })
                        else Icon(fileTypeIcon(file), null, tint = if (file.isDirectory) Accent else Muted, modifier = Modifier.size(28.dp))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(file.name ?: "Unnamed", color = Ink, fontWeight = FontWeight.Medium)
                            Text(if (file.isDirectory) "Folder" else (file.type ?: "File") + " • " + readableSize(file.length()), color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Box {
                            IconButton(onClick = { fileActionMenu = key }) { Icon(Icons.Default.MoreVert, "More file actions", tint = Muted) }
                            DropdownMenu(expanded = fileActionMenu == key, onDismissRequest = { fileActionMenu = null }) {
                                if (!file.isDirectory) DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { fileActionMenu = null; shareDocuments(listOf(file)) })
                                DropdownMenuItem(text = { Text("Details") }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = { fileActionMenu = null; detailsFile = file })
                                DropdownMenuItem(text = { Text(if (key in favourites) "Remove favourite" else "Add to favourites") }, leadingIcon = { Icon(if (key in favourites) Icons.Default.Star else Icons.Default.StarBorder, null) }, onClick = { fileActionMenu = null; toggleFavourite(file) })
                            }
                        }
                        if (file.isDirectory) Icon(Icons.Default.ChevronRight, null, tint = Muted)
                    }
                }
                if (selected.isNotEmpty()) {
                    item {
                        OutlinedButton(onClick = { createZipPicker.launch("Archive.zip") }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.FolderZip, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Create ZIP")
                        }
                    }
                }
            }
        }
    }

    if (privatePinDialog) {
        AlertDialog(
            onDismissRequest = { privatePinDialog = false; privatePinEntry = "" },
            title = { Text(if (privateSpacePreferences.getString("pin_hash", null) == null) "Create Private Space PIN" else "Unlock Private Space") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose a 4–12 digit PIN. Your files are encrypted in app-private storage.")
                    OutlinedTextField(
                        value = privatePinEntry,
                        onValueChange = { value -> if (value.length <= 12 && value.all { it in '0'..'9' }) privatePinEntry = value },
                        label = { Text("PIN") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                    privatePinError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        require(privatePinEntry.length in 4..12) { "Enter a PIN with 4 to 12 digits." }
                        val saltRaw = privateSpacePreferences.getString("pin_salt", null)
                        val hashRaw = privateSpacePreferences.getString("pin_hash", null)
                        if (saltRaw == null || hashRaw == null) {
                            val salt = PrivateSpaceStore.createPinSalt()
                            val hash = PrivateSpaceStore.derivePinHash(privatePinEntry.toCharArray(), salt)
                            privateSpacePreferences.edit()
                                .putString("pin_salt", java.util.Base64.getEncoder().encodeToString(salt))
                                .putString("pin_hash", java.util.Base64.getEncoder().encodeToString(hash)).apply()
                        } else {
                            val salt = java.util.Base64.getDecoder().decode(saltRaw)
                            val expected = java.util.Base64.getDecoder().decode(hashRaw)
                            require(PrivateSpaceStore.verifyPin(privatePinEntry.toCharArray(), salt, expected)) { "Incorrect PIN." }
                        }
                        privateSpaceUnlocked = true
                        privateSpaceIds = privateSpaceStore.listIds()
                        privatePinDialog = false
                        privatePinEntry = ""
                        tab = 5
                    }.onFailure { privatePinError = it.message ?: "Unable to unlock Private Space" }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { privatePinDialog = false; privatePinEntry = "" }) { Text("Cancel") } }
        )
    }
    if (showStoragePrompt) {
        AlertDialog(
            onDismissRequest = { showStoragePrompt = false },
            title = { Text("Set up File Explorer") },
            text = { Text("To show your shared storage automatically like a full file manager, allow All files access in Android Settings. You only need to approve this once. Android still restricts some app-private folders.") },
            confirmButton = {
                TextButton(onClick = {
                    showStoragePrompt = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName))
                        runCatching { storageSettingsLauncher.launch(intent) }.onFailure {
                            storageSettingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    } else {
                        picker.launch(null)
                    }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { showStoragePrompt = false }) { Text("Not now") } }
        )
    }
    if (confirmClearList != null) {
        val clearingRecent = confirmClearList == "recent"
        val clearingTrash = confirmClearList == "trash"
        AlertDialog(
            onDismissRequest = { confirmClearList = null },
            title = { Text(when { clearingRecent -> "Clear recent history?"; clearingTrash -> "Empty Recycle Bin?"; else -> "Clear all favourites?" }) },
            text = { Text(when { clearingRecent -> "This removes the recent-file list from this device. Your files will not be deleted."; clearingTrash -> "Permanently delete all items in the Recycle Bin? This cannot be undone."; else -> "This removes all saved favourites. Your files will not be deleted." }) },
            confirmButton = {
                TextButton(onClick = {
                    if (clearingRecent) {
                        recent = emptyList()
                        context.getSharedPreferences("file_history", 0).edit().remove("items").apply()
                    } else if (clearingTrash) {
                        val failures = trashRecords.count { !permanentlyDeleteTrashRecord(it) }
                        feedback = if (failures == 0) "Recycle Bin emptied" else "Could not permanently delete $failures item(s)"
                        refresh++
                    } else {
                        favourites = emptySet()
                        context.getSharedPreferences("file_favourites", 0).edit().remove("uris").apply()
                    }
                    confirmClearList = null
                }) { Text(if (clearingTrash) "Delete permanently" else "Clear", color = if (clearingTrash) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { confirmClearList = null }) { Text("Cancel") } }
        )
    }

    if (detailsFile != null) {
        val file = detailsFile!!
        val modified = file.lastModified().takeIf { it > 0L }?.let {
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(it))
        } ?: "Unknown"
        AlertDialog(
            onDismissRequest = { detailsFile = null },
            title = { Text("Details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(file.name ?: "Unnamed", color = Ink, fontWeight = FontWeight.SemiBold)
                    HorizontalDivider()
                    DetailRow("Type", if (file.isDirectory) "Folder" else file.type ?: "Unknown")
                    DetailRow("Size", if (file.isDirectory) "Folder" else readableSize(file.length()))
                    DetailRow("Modified", modified)
                    DetailRow("Location provider", file.uri.authority ?: "Unknown")
                    Text(file.uri.toString(), color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            },
            confirmButton = { TextButton(onClick = { detailsFile = null }) { Text("Close") } }
        )
    }

    if (previewTitle != null) {
        Dialog(
            onDismissRequest = { previewTitle = null; previewText = null; previewBitmap = null; previewPdfUri = null; previewZipUri = null; previewZipApks = emptyList(); previewRarUri = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF101318)
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().background(Color(0xFF191F29)).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { previewTitle = null; previewText = null; previewBitmap = null; previewPdfUri = null; previewZipUri = null; previewZipApks = emptyList(); previewRarUri = null }) {
                            Icon(Icons.Default.ArrowBack, "Close preview", tint = Color.White)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(previewTitle ?: "Preview", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                                    previewPdfUri != null && pdfPageCount > 0 -> "PDF • Page ${pdfPage + 1} of $pdfPageCount"
                                    previewPdfUri != null -> "PDF document"
                                    previewBitmap != null -> "Image preview"
                                    previewZipUri != null || previewRarUri != null -> "Archive contents"
                                    else -> "Document preview"
                                },
                                color = Color(0xFFB9C4D3),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        var previewMenuExpanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { previewMenuExpanded = true }) { Icon(Icons.Default.MoreVert, "More preview actions", tint = Color.White) }
                            DropdownMenu(expanded = previewMenuExpanded, onDismissRequest = { previewMenuExpanded = false }) {
                                DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = {
                                    previewMenuExpanded = false
                                    val uri = previewPdfUri ?: previewZipUri ?: previewRarUri
                                    if (uri != null) shareDocuments(listOfNotNull(DocumentFile.fromSingleUri(context, uri)))
                                    else feedback = "Sharing this preview is not available from here yet."
                                })
                                DropdownMenuItem(text = { Text("Reset zoom") }, leadingIcon = { Icon(Icons.Default.ZoomOutMap, null) }, onClick = { previewMenuExpanded = false; previewZoom = 1f })
                            }
                        }
                    }
                    when {
                        previewBitmap != null && previewPdfUri == null -> {
                            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF080A0E)), contentAlignment = Alignment.Center) {
                                Image(
                                    bitmap = previewBitmap!!.asImageBitmap(),
                                    contentDescription = previewTitle,
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp).graphicsLayer(scaleX = previewZoom, scaleY = previewZoom),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            Row(Modifier.fillMaxWidth().background(Color(0xFF191F29)).padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { previewZoom = (previewZoom / 1.25f).coerceAtLeast(1f) }) { Icon(Icons.Default.ZoomOut, "Zoom out", tint = Color.White) }
                                Text("${(previewZoom * 100).toInt()}%", color = Color.White, modifier = Modifier.padding(horizontal = 12.dp))
                                IconButton(onClick = { previewZoom = (previewZoom * 1.25f).coerceAtMost(4f) }) { Icon(Icons.Default.ZoomIn, "Zoom in", tint = Color.White) }
                                TextButton(onClick = { previewZoom = 1f }) { Text("Fit") }
                            }
                        }
                        previewPdfUri != null -> {
                            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF30343B)), contentAlignment = Alignment.Center) {
                                if (previewBitmap != null) {
                                    Image(bitmap = previewBitmap!!.asImageBitmap(), contentDescription = "PDF page ${pdfPage + 1}", modifier = Modifier.fillMaxSize().padding(8.dp).graphicsLayer(scaleX = previewZoom, scaleY = previewZoom), contentScale = ContentScale.Fit)
                                } else {
                                    Text(previewText ?: "Rendering PDF…", color = Color.White, modifier = Modifier.padding(24.dp))
                                }
                            }
                            Row(Modifier.fillMaxWidth().background(Color(0xFF191F29)).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                                IconButton(onClick = { previewZoom = (previewZoom / 1.25f).coerceAtLeast(1f) }) { Icon(Icons.Default.ZoomOut, "Zoom out", tint = Color.White) }
                                Text("${(previewZoom * 100).toInt()}%", color = Color.White)
                                IconButton(onClick = { previewZoom = (previewZoom * 1.25f).coerceAtMost(4f) }) { Icon(Icons.Default.ZoomIn, "Zoom in", tint = Color.White) }
                                TextButton(onClick = { previewZoom = 1f }) { Text("Fit") }
                                TextButton(enabled = pdfPage > 0, onClick = {
                                    val uri = previewPdfUri ?: return@TextButton
                                    val next = pdfPage - 1
                                    scope.launch {
                                        val rendered = withContext(Dispatchers.IO) { runCatching { renderPdfPage(uri, next) }.getOrNull() }
                                        if (rendered?.first != null) { pdfPage = next; previewBitmap = rendered.first; previewText = null }
                                    }
                                }) { Icon(Icons.Default.ChevronLeft, null); Text("Previous") }
                                if (pdfPageCount > 0) Text(" ${pdfPage + 1} / $pdfPageCount ", color = Color.White) else Text("Loading pages…", color = Color.White)
                                TextButton(enabled = pdfPage + 1 < pdfPageCount, onClick = {
                                    val uri = previewPdfUri ?: return@TextButton
                                    val next = pdfPage + 1
                                    scope.launch {
                                        val rendered = withContext(Dispatchers.IO) { runCatching { renderPdfPage(uri, next) }.getOrNull() }
                                        if (rendered?.first != null) { pdfPage = next; previewBitmap = rendered.first; previewText = null }
                                    }
                                }) { Text("Next"); Icon(Icons.Default.ChevronRight, null) }
                            }
                        }
                        else -> {
                            Column(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFF7F9FC)).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (previewText == null) {
                                    CircularProgressIndicator()
                                    Text("Preparing preview…", color = Ink)
                                } else if (previewTitle.orEmpty().substringAfterLast('.', "").lowercase() in setOf("docx", "xlsx", "pptx")) {
                                    OfficePreviewContent(previewTitle.orEmpty(), previewText ?: "")
                                } else {
                                    Text(previewText ?: "", color = Ink, style = MaterialTheme.typography.bodyMedium)
                                }
                                if (previewRarUri != null) {
                                    OutlinedTextField(value = rarPassword, onValueChange = { rarPassword = it }, label = { Text("RAR password (leave blank if none)") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                                }
                                if (previewZipUri != null || previewRarUri != null) {
                                    Button(onClick = { zipDestinationPicker.launch(null) }) { Text("Extract to folder") }
                                }
                                if (previewZipUri != null && previewZipApks.isNotEmpty()) {
                                    HorizontalDivider()
                                    Text("APK files in this ZIP", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Ink)
                                    previewZipApks.forEach { entryName ->
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Android, null, tint = Accent)
                                            Text(entryName.substringAfterLast('/'), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = Ink)
                                            TextButton(onClick = {
                                                val archiveUri = previewZipUri ?: return@TextButton
                                                scope.launch {
                                                    val staged = withContext(Dispatchers.IO) { runCatching { stageApkFromZip(context, archiveUri, entryName) }.getOrNull() }
                                                    if (staged != null) runCatching { installStagedApk(staged) }.onFailure { feedback = "Could not launch Android package installer: " + (it.message ?: "unknown error") }
                                                    else feedback = "Could not stage this APK from the ZIP archive. Check that the archive is valid and within the 512 MB safety limit."
                                                }
                                            }) { Text("Install") }
                                        }
                                    }
                                    Text("The APK is staged privately for Android's installer. The ZIP is not extracted into shared storage.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (analysisReport != null) {
        AlertDialog(onDismissRequest = { analysisReport = null }, title = { Text("Storage analysis") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(analysisReport ?: "", maxLines = 24, overflow = TextOverflow.Ellipsis)
            }
        }, confirmButton = { TextButton(onClick = { analysisReport = null }) { Text("Done") } })
    }
    if (dialog == "new" || dialog == "rename") {
        AlertDialog(onDismissRequest = { dialog = "" }, title = { Text(if (dialog == "new") "Create folder" else "Rename item") }, text = {
            OutlinedTextField(input, { input = it }, singleLine = true, label = { Text("Name") })
        }, confirmButton = {
            TextButton(onClick = {
                val value = input.trim()
                if (value.isNotEmpty()) {
                    val success = if (dialog == "new") root?.createDirectory(value) != null else target?.renameTo(value) == true
                    feedback = if (success) if (dialog == "new") "Folder created" else "Item renamed" else "Could not complete operation. Check access and name."
                    if (success) reload()
                }
                dialog = ""
            }) { Text("Save") }
        }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
    }
    if (dialog == "delete") {
        AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("Move to Recycle Bin?") }, text = { Text("Move " + selected.size + " selected item(s) to the Recycle Bin? The app first copies them into its private recycle folder, then removes the originals. This needs enough free space. You can restore them later or permanently delete them there.") }, confirmButton = {
            TextButton(onClick = {
                val storageRoot = folderStack.firstOrNull() ?: root
                val chosen = shownFiles.filter { it.uri.toString() in selected }
                dialog = ""
                if (storageRoot == null || chosen.isEmpty()) {
                    feedback = "Select files or folders in Browse first."
                } else {
                    operationName = "Moving to Recycle Bin"
                    operationJob = scope.launch {
                        working = true
                        var moved = 0
                        var failed = 0
                        val movedUris = mutableListOf<String>()
                        try {
                            for (source in chosen) {
                                val success = withContext(Dispatchers.IO) {
                                    val parentPath = findParentSegments(storageRoot, source.uri)
                                        ?: folderStack.drop(1).mapNotNull { it.name }
                                    moveDocumentToTrash(context, storageRoot, source, parentPath)
                                }
                                if (success) {
                                    moved++
                                    movedUris.add(source.uri.toString())
                                } else failed++
                            }
                            if (movedUris.isNotEmpty()) {
                                favourites = favourites - movedUris.toSet()
                                context.getSharedPreferences("file_favourites", 0).edit().putStringSet("uris", favourites).apply()
                                recent = recent.filterNot { it.substringBefore('\t') in movedUris }
                                context.getSharedPreferences("file_history", 0).edit().putString("items", recent.joinToString("\n")).apply()
                            }
                            feedback = "Moved $moved item(s) to Recycle Bin" + if (failed > 0) "; $failed could not be moved" else ""
                            reload()
                        } catch (cancelled: CancellationException) {
                            feedback = "Recycle Bin operation cancelled. Completed: $moved; failed: $failed."
                        } finally {
                            working = false
                            operationJob = null
                        }
                    }
                }
            }) { Text("Move to Recycle Bin") }
        }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
    }
    if (privateDeleteTarget != null) {
        val id = privateDeleteTarget!!
        AlertDialog(
            onDismissRequest = { privateDeleteTarget = null },
            title = { Text("Delete private file?") },
            text = { Text("Permanently delete this encrypted item from Private Space? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val removed = privateSpaceStore.delete(id)
                    privateSpaceIds = privateSpaceStore.listIds()
                    privateDeleteTarget = null
                    feedback = if (removed) "Private file permanently deleted" else "Could not delete this private file"
                }) { Text("Delete permanently", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { privateDeleteTarget = null }) { Text("Cancel") } }
        )
    }
    if (trashDeleteTarget != null) {
        val record = trashDeleteTarget!!
        AlertDialog(
            onDismissRequest = { trashDeleteTarget = null },
            title = { Text("Permanently delete?") },
            text = { Text("This item and its contents will be permanently removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val removed = permanentlyDeleteTrashRecord(record)
                    feedback = if (removed) "Item permanently deleted" else "Could not permanently delete this item"
                    trashDeleteTarget = null
                    reload()
                }) { Text("Delete permanently", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { trashDeleteTarget = null }) { Text("Cancel") } }
        )
    }
    if (working) AlertDialog(onDismissRequest = {}, title = { Text(operationName) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(operationDetail.ifBlank { "Working…" }); Text("You can cancel this operation. Newly created items are rolled back where the storage provider allows it.") } }, confirmButton = { TextButton(onClick = { operationJob?.cancel() }) { Text("Cancel") } })
    feedback?.let { note ->
        AlertDialog(onDismissRequest = { feedback = null }, title = { Text("File Explorer") }, text = { Text(note) }, confirmButton = { TextButton(onClick = { feedback = null }) { Text("OK") } })
    }
}

private suspend fun searchDocuments(root: DocumentFile, query: String, category: String, showHidden: Boolean): List<DocumentFile> {
    val matches = mutableListOf<DocumentFile>()
    val stack = ArrayDeque<Pair<DocumentFile, Int>>()
    stack.add(root to 0)
    var visited = 0
    while (stack.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val (folder, depth) = stack.removeLast()
        if (depth > 32 || !folder.isDirectory) continue
        val children = try {
            folder.listFiles().toList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw IllegalStateException("Cannot read folder ${folder.name ?: "Storage"}: ${error.message ?: "access denied"}", error)
        }
        for (child in children) {
            currentCoroutineContext().ensureActive()
            visited++
            if (visited > 10000) {
                throw IllegalStateException("Search reached the 10,000-item safety limit. Narrow your search or open a smaller folder.")
            }
            val childName = child.name.orEmpty()
            if (childName == ".FileExplorerTrash" || (!showHidden && childName.startsWith("."))) continue
            val nameMatches = query.isBlank() || childName.contains(query, ignoreCase = true)
            val categoryMatches = category == "All" || (child.isFile && matchesCategory(child, category))
            if (nameMatches && categoryMatches) matches.add(child)
            if (child.isDirectory && depth < 32) stack.add(child to depth + 1)
        }
    }
    return matches
}

private fun matchesCategory(file: DocumentFile, category: String): Boolean {
    if (category == "All" || file.isDirectory) return true
    val name = file.name.orEmpty().lowercase()
    val mime = detectedFileMime(file.name.orEmpty(), file.type).lowercase()
    return when (category) {
        "Images" -> mime.startsWith("image/") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".gif") || name.endsWith(".webp") || name.endsWith(".heic") || name.endsWith(".bmp")
        "Videos" -> mime.startsWith("video/") || listOf(".mp4", ".mkv", ".mov", ".webm", ".3gp", ".avi", ".m4v").any(name::endsWith)
        "Audio" -> mime.startsWith("audio/") || listOf(".mp3", ".m4a", ".aac", ".wav", ".ogg", ".flac", ".opus", ".wma").any(name::endsWith)
        "Documents" -> mime.startsWith("text/") || mime in setOf("application/pdf", "application/msword", "application/rtf", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.openxmlformats-officedocument.presentationml.presentation") || listOf(".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".rtf", ".csv", ".odt", ".ods").any(name::endsWith)
        "APKs" -> mime == "application/vnd.android.package-archive" || name.endsWith(".apk") || name.endsWith(".apks") || name.endsWith(".xapk")
        else -> true
    }
}

private fun readableSize(bytes: Long): String = when {
    bytes >= 1073741824L -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= 1048576L -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> bytes.toString() + " B"
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
        Text(value, color = Ink, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.widthIn(max = 190.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun CategoryTile(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, bg: Color, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier = modifier.height(104.dp).clickable(onClick = onClick), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Surface(shape = RoundedCornerShape(12.dp), color = bg, modifier = Modifier.size(38.dp)) { Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint) } }
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(Modifier.fillMaxWidth().padding(vertical = 42.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = Muted, modifier = Modifier.size(42.dp))
        Text(title, color = Ink, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

private fun containsDocumentUri(folder: DocumentFile, targetUri: Uri, depth: Int = 0): Boolean {
    if (depth > 32) return false
    if (folder.uri == targetUri) return true
    if (!folder.isDirectory) return false
    return folder.listFiles().any { child -> child.uri == targetUri || (child.isDirectory && containsDocumentUri(child, targetUri, depth + 1)) }
}

internal suspend fun copyDocument(context: android.content.Context, source: DocumentFile, destination: DocumentFile): Boolean {
    val created = RollbackJournal<DocumentFile> { it.delete() }
    return try {
        val success = copyDocumentTree(context, source, destination, created, 0)
        if (success) created.commit() else created.rollback()
        success
    } catch (cancelled: CancellationException) {
        created.rollback()
        throw cancelled
    } catch (_: Exception) {
        created.rollback()
        false
    }
}

private suspend fun copyDocumentTree(
    context: android.content.Context,
    source: DocumentFile,
    destination: DocumentFile,
    created: RollbackJournal<DocumentFile>,
    depth: Int
): Boolean {
    currentCoroutineContext().ensureActive()
    if (depth > 64) return false
    return try {
        if (source.isDirectory) {
            val name = source.name ?: "Folder"
            // Never merge into an existing directory: it may be the source folder
            // itself when the user chooses the same parent as the destination.
            if (destination.findFile(name) != null) return false
            val folder = destination.createDirectory(name)?.also { created.record(it) } ?: return false
            for (child in source.listFiles()) {
                currentCoroutineContext().ensureActive()
                if (!copyDocumentTree(context, child, folder, created, depth + 1)) return false
            }
            true
        } else {
            val name = source.name ?: "file"
            if (destination.findFile(name) != null) return false
            val target = destination.createFile(source.type ?: "application/octet-stream", name) ?: return false
            created.record(target)
            val input = context.contentResolver.openInputStream(source.uri) ?: return false
            input.use { ins ->
                val output = context.contentResolver.openOutputStream(target.uri) ?: return false
                output.use { outs ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = ins.read(buffer)
                        if (count < 0) break
                        outs.write(buffer, 0, count)
                    }
                }
            }
            true
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

private fun readZipListing(context: android.content.Context, uri: Uri): String {
    val input = context.contentResolver.openInputStream(uri) ?: return "Cannot open archive."
    val lines = mutableListOf<String>()
    var count = 0
    ZipInputStream(input.buffered()).use { zip ->
        while (count < 100) {
            val entry = zip.nextEntry ?: break
            lines += (if (entry.isDirectory) "[Folder] " else "[File] ") + entry.name.take(240)
            count++
            zip.closeEntry()
        }
    }
    return if (lines.isEmpty()) "This archive is empty." else lines.joinToString("\n") + if (count == 100) "\nShowing first 100 entries" else ""
}

private suspend fun extractZipSafely(context: android.content.Context, archiveUri: Uri, destination: DocumentFile): String {
    val input = context.contentResolver.openInputStream(archiveUri) ?: return "Cannot open ZIP archive."
    val created = RollbackJournal<DocumentFile> { it.delete() }
    var entries = 0
    var totalBytes = 0L
    var extracted = 0
    try {
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > 5000) throw IllegalArgumentException("Archive contains too many entries")
                val segments = validatedZipEntryPath(entry.name)
                var parent = destination
                for (segment in segments.dropLast(1).filter { it.isNotBlank() }) {
                    currentCoroutineContext().ensureActive()
                    val existing = parent.findFile(segment)
                    if (existing != null && !existing.isDirectory) throw IllegalArgumentException("Cannot create archive folder")
                    if (existing == null) {
                        parent = parent.createDirectory(segment) ?: throw IllegalArgumentException("Cannot create archive folder")
                        created.record(parent)
                    } else parent = existing
                }
                val leaf = segments.lastOrNull().orEmpty()
                if (entry.isDirectory) {
                    if (leaf.isNotBlank() && parent.findFile(leaf) == null) {
                        val folder = parent.createDirectory(leaf) ?: throw IllegalArgumentException("Cannot create archive folder")
                        created.record(folder)
                    } else if (leaf.isNotBlank() && parent.findFile(leaf)?.isDirectory != true) {
                        throw IllegalArgumentException("Cannot create archive folder")
                    }
                } else {
                    if (leaf.isBlank() || parent.findFile(leaf) != null) throw IllegalArgumentException("File already exists or invalid name: $leaf")
                    val outputFile = parent.createFile("application/octet-stream", leaf) ?: throw IllegalArgumentException("Cannot create file")
                    created.record(outputFile)
                    val output = context.contentResolver.openOutputStream(outputFile.uri) ?: throw IllegalArgumentException("Cannot write file")
                    output.use { out ->
                        val buffer = ByteArray(32768)
                        var entryBytes = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = zip.read(buffer)
                            if (read < 0) break
                            entryBytes += read
                            totalBytes += read
                            if (entryBytes > 134217728L || totalBytes > 536870912L) throw IllegalArgumentException("Archive exceeds extraction size limits")
                            out.write(buffer, 0, read)
                        }
                    }
                    extracted++
                }
                zip.closeEntry()
            }
        }
        created.commit()
        return "Extracted $extracted file(s) from ZIP archive."
    } catch (error: Exception) {
        created.rollback()
        throw error
    }
}

private suspend fun createZipArchive(context: android.content.Context, sources: List<DocumentFile>, outputUri: Uri): String {
    if (sources.isEmpty()) return "No files selected."
    val output = context.contentResolver.openOutputStream(outputUri) ?: return "Cannot create ZIP file."
    var entries = 0
    var totalBytes = 0L
    val buffer = ByteArray(32768)
    suspend fun addDocument(zip: ZipOutputStream, document: DocumentFile, path: String, depth: Int) {
        currentCoroutineContext().ensureActive()
        if (depth > 64) throw IllegalArgumentException("Folder nesting limit exceeded")
        entries++
        if (entries > 5000) throw IllegalArgumentException("ZIP entry limit exceeded (5,000)")
        if (document.isDirectory) {
            zip.putNextEntry(ZipEntry(path + "/"))
            zip.closeEntry()
            for (child in document.listFiles()) {
                currentCoroutineContext().ensureActive()
                val childName = child.name?.takeIf { it.isNotBlank() && it != "." && it != ".." }
                    ?: throw IllegalArgumentException("A selected folder contains an invalid filename")
                addDocument(zip, child, path + "/" + childName, depth + 1)
            }
        } else {
            zip.putNextEntry(ZipEntry(path))
            val input = context.contentResolver.openInputStream(document.uri)
                ?: throw IllegalArgumentException("Cannot read selected file")
            input.use { stream ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    if (totalBytes > 1073741824L) throw IllegalArgumentException("ZIP source data exceeds 1 GB limit")
                    zip.write(buffer, 0, count)
                }
            }
            zip.closeEntry()
        }
    }
    try {
        ZipOutputStream(output.buffered()).use { zip ->
            for (source in sources) {
                currentCoroutineContext().ensureActive()
                val name = source.name?.takeIf { it.isNotBlank() && it != "." && it != ".." }
                    ?: throw IllegalArgumentException("Selected item has an invalid filename")
                addDocument(zip, source, name, 0)
            }
        }
        return "Created ZIP archive with " + entries + " entr(y/ies)."
    } catch (error: Exception) {
        runCatching { context.contentResolver.delete(outputUri, null, null) }
        throw error
    }
}


private suspend fun analyzeStorageTree(context: android.content.Context, root: DocumentFile): String {
    val largest = mutableListOf<Pair<String, Long>>()
    val sizeGroups = mutableMapOf<Long, MutableList<DocumentFile>>()
    val stack = ArrayDeque<Pair<DocumentFile, Int>>()
    stack.add(root to 0)
    var files = 0
    var folders = 0
    var totalBytes = 0L
    var unreadableItems = 0
    var unknownSizes = 0
    var truncated = false

    while (stack.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val (node, depth) = stack.removeLast()
        if (depth > 64) continue
        if (node.isDirectory) {
            folders++
            val children = try {
                node.listFiles().toList()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                unreadableItems++
                emptyList()
            }
            for (child in children) {
                if (files + folders + stack.size >= 10000) {
                    truncated = true
                    break
                }
                if (child.name != ".FileExplorerTrash") stack.add(child to depth + 1)
            }
        } else if (node.isFile) {
            files++
            val size = try {
                node.length().takeIf { it >= 0L }
            } catch (_: Exception) {
                null
            }
            if (size == null) {
                unknownSizes++
            } else {
                totalBytes = if (Long.MAX_VALUE - totalBytes < size) Long.MAX_VALUE else totalBytes + size
                largest.add((node.name ?: "Unnamed") to size)
                if (size > 0) sizeGroups.getOrPut(size) { mutableListOf() }.add(node)
            }
        }
        if (files + folders >= 10000) {
            truncated = true
            break
        }
    }

    val top = largest.sortedByDescending { it.second }.take(5)
    val digest = MessageDigest.getInstance("SHA-256")
    val duplicateGroups = mutableListOf<List<String>>()
    var bytesHashed = 0L
    var skippedHashing = 0
    for ((size, candidates) in sizeGroups.filter { it.value.size > 1 }.toList().sortedByDescending { it.first }) {
        val hashes = mutableMapOf<String, MutableList<String>>()
        for (file in candidates) {
            currentCoroutineContext().ensureActive()
            if (size > 104857600L || bytesHashed + size > 536870912L) {
                skippedHashing++
                continue
            }
            try {
                val input = context.contentResolver.openInputStream(file.uri)
                    ?: throw IllegalStateException("File stream unavailable")
                val hash = input.use { stream ->
                    digest.reset()
                    val buffer = ByteArray(32768)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                    bytesHashed += size
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
                hashes.getOrPut(hash) { mutableListOf() }.add(file.name ?: "Unnamed")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                unreadableItems++
            }
        }
        hashes.values.filter { it.size > 1 }.forEach { duplicateGroups.add(it) }
    }

    fun pretty(bytes: Long): String = when {
        bytes >= 1073741824L -> "%.2f GB".format(bytes / 1073741824.0)
        bytes >= 1048576L -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
    val report = StringBuilder()
    report.append("Scanned ").append(files).append(" files and ").append(folders).append(" folders\n")
    report.append("Total known file size: ").append(pretty(totalBytes)).append("\n")
    if (unknownSizes > 0) report.append("Files with unavailable size: ").append(unknownSizes).append("\n")
    if (unreadableItems > 0) report.append("Items that could not be read: ").append(unreadableItems).append("\n")
    report.append("\nLargest files:\n")
    if (top.isEmpty()) report.append("No readable files found\n")
    top.forEach { report.append("• ").append(it.first.take(70)).append(" — ").append(pretty(it.second)).append('\n') }
    report.append("\nExact duplicate groups: ").append(duplicateGroups.size)
    duplicateGroups.take(5).forEachIndexed { index, group ->
        report.append("\n").append(index + 1).append(". ").append(group.take(4).joinToString(", ") { it.take(40) })
        if (group.size > 4) report.append(" and ").append(group.size - 4).append(" more")
    }
    if (duplicateGroups.isEmpty()) report.append("\nNo exact duplicates found in the readable files checked.")
    report.append("\n\nDuplicate hashing checks files up to 100 MB each and 512 MB total.")
    if (skippedHashing > 0) report.append("\nDuplicate check skipped ").append(skippedHashing).append(" file(s) due to hashing limits.")
    if (truncated) report.append("\nScan stopped at the 10,000-item safety limit.")
    return report.toString()
}

private fun launchStagedApkInstaller(context: android.content.Context, staged: File) {
    require(staged.isFile && staged.length() > 0L) { "Staged APK is missing or empty" }
    val apkUri = androidx.core.content.FileProvider.getUriForFile(
        context,
        context.packageName + ".fileprovider",
        staged
    )
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(apkUri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(intent)
}

private fun resolveDocumentsByUri(root: DocumentFile?, requested: Set<String>): Map<String, DocumentFile> {
    if (root == null || requested.isEmpty()) return emptyMap()
    val result = mutableMapOf<String, DocumentFile>()
    val stack = ArrayDeque<Pair<DocumentFile, Int>>()
    stack.add(root to 0)
    var visited = 0
    while (stack.isNotEmpty() && visited < 10000 && result.size < requested.size) {
        val (folder, depth) = stack.removeLast()
        if (!folder.isDirectory || depth > 32) continue
        for (child in runCatching { folder.listFiles().toList() }.getOrDefault(emptyList())) {
            visited++
            val key = child.uri.toString()
            if (key in requested) result[key] = child
            if (child.isDirectory && child.name != ".FileExplorerTrash" && depth < 32) stack.add(child to depth + 1)
            if (visited >= 10000 || result.size >= requested.size) break
        }
    }
    return result
}

private fun findDocumentPath(root: DocumentFile?, target: Uri): List<DocumentFile>? {
    if (root == null) return null
    val stack = ArrayDeque<Pair<DocumentFile, List<DocumentFile>>>()
    stack.add(root to listOf(root))
    var visited = 0
    while (stack.isNotEmpty() && visited < 10000) {
        val (folder, path) = stack.removeLast()
        if (!folder.isDirectory || path.size > 33) continue
        for (child in runCatching { folder.listFiles().toList() }.getOrDefault(emptyList())) {
            visited++
            if (child.uri == target) return path + child
            if (child.isDirectory && child.name != ".FileExplorerTrash" && path.size < 33) stack.add(child to (path + child))
            if (visited >= 10000) break
        }
    }
    return null
}
