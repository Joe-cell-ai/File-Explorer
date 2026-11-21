package com.joecell.fileexplorer

import android.content.Intent
import android.content.ClipData
import android.os.Bundle
import android.os.StatFs
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.layout.ContentScale
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
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.security.MessageDigest

private val Ink = Color(0xFF20242B)
private val Muted = Color(0xFF777F8A)
private val Accent = Color(0xFF1677E8)
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
    var accessStatus by remember { mutableStateOf("Choose a folder to grant File Explorer access") }
    val accessPreferences = remember { context.getSharedPreferences("storage_access", 0) }
    LaunchedEffect(Unit) {
        val saved = accessPreferences.getString("tree_uri", null)
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
            if (restored != null && runCatching { restored.exists() && restored.canRead() && restored.canWrite() }.getOrDefault(false)) {
                root = restored
                folderStack = listOf(restored)
                accessStatus = "Folder access is active and will remain available after restart"
            } else {
                accessPreferences.edit().remove("tree_uri").apply()
                accessStatus = "Previous folder access expired. Choose the folder again to restore access"
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
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sortBy by remember { mutableStateOf("Name") }
    var descending by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<DocumentFile?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
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
    var previewPdfUri by remember { mutableStateOf<Uri?>(null) }
    var previewZipUri by remember { mutableStateOf<Uri?>(null) }
    var previewRarUri by remember { mutableStateOf<Uri?>(null) }
    var analysisReport by remember { mutableStateOf<String?>(null) }
    var pdfPage by remember { mutableIntStateOf(0) }
    var pdfPageCount by remember { mutableIntStateOf(0) }
    var favourites by remember { mutableStateOf(context.getSharedPreferences("file_favourites", 0).getStringSet("uris", emptySet()).orEmpty()) }
    var recent by remember { mutableStateOf(context.getSharedPreferences("file_history", 0).getString("items", "").orEmpty().lines().filter { it.isNotBlank() }) }
    val scope = rememberCoroutineScope()

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

    fun shareDocuments(files: List<DocumentFile>) {
        val shareable = files.filter { !it.isDirectory }
        if (shareable.isEmpty()) {
            feedback = "Select at least one file to share"
            return
        }
        val uris = shareable.map { it.uri }
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND) else Intent(Intent.ACTION_SEND_MULTIPLE)
        intent.type = "*/*"
        if (uris.size == 1) intent.putExtra(Intent.EXTRA_STREAM, uris.first())
        else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        intent.clipData = ClipData.newUri(context.contentResolver, "Shared files", uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(intent, "Share files")) }
            .onFailure { feedback = "No app available to share these files" }
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

    fun openFile(file: DocumentFile) {
        rememberOpened(file)
        val mime = file.type.orEmpty()
        val name = file.name.orEmpty()
        if (mime.startsWith("image/")) {
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
                previewText = if (bitmap == null) "Unable to display this image." else null
                previewTitle = file.name ?: "Image preview"
            }
        } else if (mime == "application/pdf" || name.endsWith(".pdf", true)) {
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
                previewZipUri = file.uri
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
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.type ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        }
    }

    val createZipPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            val sources = root?.listFiles()?.filter { it.uri.toString() in selected }.orEmpty()
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
            if (readSaved && folder != null && runCatching { folder.exists() && folder.isDirectory && folder.canRead() }.getOrDefault(false)) {
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
            if (permissionSaved && destination != null && runCatching { destination.exists() && destination.isDirectory && destination.canRead() && destination.canWrite() }.getOrDefault(false)) {
                operationName = "Extracting ZIP"
                operationJob = scope.launch {
                    working = true
                    try {
                        feedback = withContext(Dispatchers.IO) {
                            if (zipArchiveUri != null) extractZipSafely(context, zipArchiveUri, destination)
                            else extractRarSafely(context, rarArchiveUri!!, destination)
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
                selectedRoot.exists() && selectedRoot.isDirectory && selectedRoot.canRead() && selectedRoot.canWrite()
            }.getOrDefault(false)
            if (persisted && usable) {
                accessPreferences.edit().putString("tree_uri", uri.toString()).apply()
                root = selectedRoot
                folderStack = listOfNotNull(selectedRoot)
                accessStatus = "Folder access is active and will remain available after restart"
                tab = 1
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
    BackHandler(enabled = tab == 1 && folderStack.size > 1) { navigateBackFolder() }

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
            val destinationUsable = permissionSaved && destination != null && runCatching {
                destination.exists() && destination.isDirectory && destination.canRead() && destination.canWrite()
            }.getOrDefault(false)
            if (destination != null && destinationUsable) {
                val sources = root?.listFiles()?.filter { it.uri.toString() in selected }.orEmpty()
                val shouldMove = moving
                operationName = if (moving) "Moving files" else "Copying files"
                operationJob = scope.launch {
                    working = true
                    var copied = 0
                    var failed = 0
                    try {
                        val unsafeDestination = withContext(Dispatchers.IO) {
                            sources.any { source ->
                                source.uri == destination.uri || (source.isDirectory && containsDocumentUri(source, destination.uri))
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
    val allFiles = remember(root, refresh, sortBy, descending) {
        val all = root?.listFiles()?.filter { showHiddenFiles || !it.name.orEmpty().startsWith(".") }?.toList().orEmpty()
        val ordered = when (sortBy) {
            "Size" -> all.sortedBy { it.length() }
            "Type" -> all.sortedBy { it.type.orEmpty().lowercase() }
            "Modified" -> all.sortedBy { it.lastModified() }
            else -> all.sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name.orEmpty().lowercase() })
        }
        if (descending) ordered.reversed() else ordered
    }
    LaunchedEffect(root, query, categoryFilter, refresh, sortBy, descending, showHiddenFiles) {
        val folder = root
        val term = query.trim()
        if (folder == null || term.isEmpty()) {
            recursiveSearchResults = emptyList()
            searching = false
        } else {
            searching = true
            recursiveSearchResults = emptyList()
            try {
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
            } catch (_: Exception) {
                recursiveSearchResults = emptyList()
                searching = false
            }
        }
    }
    val shownFiles = if (query.isBlank()) allFiles.filter { matchesCategory(it, categoryFilter) } else recursiveSearchResults.filter { showHiddenFiles || !it.name.orEmpty().startsWith(".") }
    val storageRoot = folderStack.firstOrNull() ?: root
    val trashRecords = remember(storageRoot, refresh) { listTrashRecords(storageRoot) }
    val stats = remember { runCatching { StatFs(android.os.Environment.getDataDirectory().path) }.getOrNull() }
    val total = stats?.totalBytes ?: 0L
    val free = stats?.availableBytes ?: 0L
    val used = (total - free).coerceAtLeast(0L)
    val formatter = remember { DecimalFormat("#,##0.0") }

    fun reload() { refresh += 1; selected = emptySet() }

    Scaffold(bottomBar = {
        NavigationBar(containerColor = Color.White) {
            NavigationBarItem(tab == 0, { tab = 0; selected = emptySet() }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
            NavigationBarItem(tab == 1, { tab = 1; if (root == null) picker.launch(null) }, icon = { Icon(Icons.Default.Folder, null) }, label = { Text("Browse") })
            NavigationBarItem(tab == 2, { tab = 2; selected = emptySet() }, icon = { Icon(Icons.Default.Schedule, null) }, label = { Text("Recent") })
            NavigationBarItem(tab == 3, { tab = 3; selected = emptySet() }, icon = { Icon(Icons.Default.Star, null) }, label = { Text("Favourites") })
            NavigationBarItem(tab == 4, { tab = 4; selected = emptySet(); if (root == null) picker.launch(null) }, icon = { Icon(Icons.Default.Delete, null) }, label = { Text("Recycle Bin") })
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
                    }
                    if (tab == 1 && root != null) {
                        IconButton(onClick = { viewMode = if (viewMode == "List") "Grid" else "List" }) { Icon(if (viewMode == "List") Icons.Default.GridView else Icons.Default.ViewList, "Change layout", tint = Accent) }
                        IconButton(onClick = { input = ""; dialog = "new" }) { Icon(Icons.Default.CreateNewFolder, "Create folder", tint = Accent) }
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Sort, "Sort", tint = Accent) }
                            DropdownMenu(sortMenu, { sortMenu = false }) {
                                listOf("Name", "Size", "Type", "Modified").forEach { mode ->
                                    DropdownMenuItem(text = { Text("Sort by " + mode) }, onClick = { sortBy = mode; sortMenu = false })
                                }
                                DropdownMenuItem(text = { Text(if (descending) "Ascending" else "Descending") }, onClick = { descending = !descending; sortMenu = false })
                                DropdownMenuItem(
                                    text = { Text(if (showHiddenFiles) "Hide hidden files" else "Show hidden files") },
                                    leadingIcon = { Icon(if (showHiddenFiles) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) },
                                    onClick = { showHiddenFiles = !showHiddenFiles; sortMenu = false; selected = emptySet() }
                                )
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
            if (tab == 1 && categoryFilter != "All") {
                item {
                    Row(Modifier.fillMaxWidth().background(Color(0xFFEAF2FF), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Filtered by $categoryFilter", color = Accent, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { categoryFilter = "All"; query = "" }) { Text("Clear") }
                    }
                }
            }
            if (tab == 0) {
                item {
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FolderOpen, null, tint = Accent, modifier = Modifier.size(28.dp))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text("Folder access", color = Ink, fontWeight = FontWeight.SemiBold)
                                Text(accessStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { picker.launch(null) }) { Text(if (root == null) "Grant" else "Change") }
                        }
                        if (root != null) {
                            TextButton(
                                onClick = {
                                    val savedUri = accessPreferences.getString("tree_uri", null)?.let { Uri.parse(it) }
                                    if (savedUri != null) runCatching {
                                        context.contentResolver.releasePersistableUriPermission(
                                            savedUri,
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                        )
                                    }
                                    accessPreferences.edit().remove("tree_uri").apply()
                                    root = null
                                    folderStack = emptyList()
                                    selected = emptySet()
                                    query = ""
                                    accessStatus = "Folder access removed. Grant access again when you want to browse"
                                },
                                modifier = Modifier.align(Alignment.End)
                            ) { Text("Remove access") }
                        }
                    }
                }
                item {
                    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Internal storage", color = Ink, fontWeight = FontWeight.SemiBold)
                            Text(formatter.format(used / 1073741824.0) + " GB used of " + formatter.format(total / 1073741824.0) + " GB", color = Muted, style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(progress = { if (total > 0) used.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth().height(8.dp), color = Accent, trackColor = SurfaceSoft)
                            Text(formatter.format(free / 1073741824.0) + " GB available", color = Muted)
                        }
                    }
                }
                item { Text("Categories", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CategoryTile("Images", Icons.Default.Image, Color(0xFFFFF0E5), Color(0xFFE88B43), Modifier.weight(1f)) { openCategory("Images") }
                            CategoryTile("Videos", Icons.Default.PlayCircle, Color(0xFFFFE9EB), Color(0xFFE45B68), Modifier.weight(1f)) { openCategory("Videos") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CategoryTile("Audio", Icons.Default.MusicNote, Color(0xFFF0EAFE), Color(0xFF8C68D9), Modifier.weight(1f)) { openCategory("Audio") }
                            CategoryTile("Documents", Icons.Default.Description, Color(0xFFE6F1FF), Accent, Modifier.weight(1f)) { openCategory("Documents") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CategoryTile("Downloads", Icons.Default.Download, Color(0xFFE7F6EF), Color(0xFF31996A), Modifier.weight(1f)) { categoryFilter = "All"; picker.launch(null) }
                            CategoryTile("APKs", Icons.Default.Android, Color(0xFFF0F2F5), Color(0xFF657181), Modifier.weight(1f)) { openCategory("APKs") }
                        }
                    }
                }
                item {
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Row(Modifier.fillMaxWidth().clickable { analysisPicker.launch(null) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Analytics, null, tint = Accent, modifier = Modifier.size(30.dp))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text("Storage analysis", color = Ink, fontWeight = FontWeight.SemiBold)
                                Text("Find large files and duplicate candidates in a folder", color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = Muted)
                        }
                    }
                }
                item { TextButton(onClick = { picker.launch(null) }) { Text("Browse storage") } }
            } else if (tab == 3) {
                val favouriteFiles = favourites.mapNotNull { raw -> runCatching { DocumentFile.fromSingleUri(context, Uri.parse(raw)) }.getOrNull() }.filter { it.exists() }
                if (favouriteFiles.isEmpty()) item { EmptyState("No favourites yet", "Star files in Browse to keep them here", Icons.Default.StarBorder) }
                else items(favouriteFiles, key = { it.uri.toString() }) { file ->
                    Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).clickable { if (file.isDirectory) feedback = "Open this folder from Browse" else openFile(file) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null, tint = Accent)
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
                        val file = DocumentFile.fromSingleUri(context, Uri.parse(uri))
                        if (file != null) openFile(file) else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, null, tint = Accent, modifier = Modifier.size(26.dp))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(name.ifBlank { "Recent file" }, color = Ink, fontWeight = FontWeight.Medium)
                            Text(mime.ifBlank { "File" }, color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { shareDocuments(listOfNotNull(DocumentFile.fromSingleUri(context, Uri.parse(uri)))) }) { Icon(Icons.Default.Share, "Share file", tint = Muted) }
                        IconButton(onClick = {
                            recent = recent.filterNot { it.substringBefore('\t') == uri }
                            context.getSharedPreferences("file_history", 0).edit().putString("items", recent.joinToString("\n")).apply()
                        }) { Icon(Icons.Default.Close, "Remove from recent", tint = Muted) }
                    }
                }
            } else if (tab == 4) {
                if (root == null) {
                    item { EmptyState("Choose a storage folder", "Grant access to view the Recycle Bin", Icons.Default.Delete) }
                } else if (trashRecords.isEmpty()) {
                    item { EmptyState("Recycle Bin is empty", "Deleted files and folders will appear here", Icons.Default.DeleteOutline) }
                } else {
                    items(trashRecords, key = { it.uri.toString() }) { record ->
                        val payload = record.listFiles().firstOrNull { it.name != ".trashinfo" }
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
                                    val restored = withContext(Dispatchers.IO) { restoreTrashRecord(context, storageRoot, record) }
                                    feedback = if (restored) "Item restored to its original folder" else "Could not restore item. The original folder may be missing or a file with the same name may exist."
                                    reload()
                                    working = false
                                    operationJob = null
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
                        if (root != null) TextButton(onClick = { picker.launch(null) }) { Text("Change") }
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
                                            else Icon(if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null, tint = Accent, modifier = Modifier.size(28.dp))
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
                        else Icon(if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null, tint = if (file.isDirectory) Accent else Muted, modifier = Modifier.size(28.dp))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(file.name ?: "Unnamed", color = Ink, fontWeight = FontWeight.Medium)
                            Text(if (file.isDirectory) "Folder" else (file.type ?: "File") + " • " + readableSize(file.length()), color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        if (!file.isDirectory) IconButton(onClick = { shareDocuments(listOf(file)) }) { Icon(Icons.Default.Share, "Share file", tint = Muted) }
                        IconButton(onClick = { detailsFile = file }) { Icon(Icons.Default.Info, "File details", tint = Muted) }
                        IconButton(onClick = { toggleFavourite(file) }) { Icon(if (key in favourites) Icons.Default.Star else Icons.Default.StarBorder, "Toggle favourite", tint = if (key in favourites) Accent else Muted) }
                        if (file.isDirectory) Icon(Icons.Default.ChevronRight, null, tint = Muted)
                    }
                }
                if (selected.isNotEmpty()) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { createZipPicker.launch("Archive.zip") }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.FolderZip, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Create ZIP")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { shareDocuments(shownFiles.filter { it.uri.toString() in selected }) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Share, null); Spacer(Modifier.width(4.dp)); Text("Share")
                            }
                            OutlinedButton(onClick = {
                                val chosen = shownFiles.filter { it.uri.toString() in selected }
                                if (chosen.size == 1) { target = chosen.first(); input = target?.name.orEmpty(); dialog = "rename" }
                                else feedback = "Select exactly one item to rename"
                            }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(4.dp)); Text("Rename") }
                            OutlinedButton(onClick = { dialog = "delete" }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(4.dp)); Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    if (confirmClearList != null) {
        val clearingRecent = confirmClearList == "recent"
        AlertDialog(
            onDismissRequest = { confirmClearList = null },
            title = { Text(if (clearingRecent) "Clear recent history?" else "Clear all favourites?") },
            text = { Text(if (clearingRecent) "This removes the recent-file list from this device. Your files will not be deleted." else "This removes all saved favourites. Your files will not be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    if (clearingRecent) {
                        recent = emptyList()
                        context.getSharedPreferences("file_history", 0).edit().remove("items").apply()
                    } else {
                        favourites = emptySet()
                        context.getSharedPreferences("file_favourites", 0).edit().remove("uris").apply()
                    }
                    confirmClearList = null
                }) { Text("Clear") }
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
        AlertDialog(onDismissRequest = { previewTitle = null; previewText = null; previewBitmap = null; previewPdfUri = null; previewZipUri = null; previewRarUri = null }, title = { Text(previewTitle ?: "Preview") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (previewBitmap != null) Image(bitmap = previewBitmap!!.asImageBitmap(), contentDescription = previewTitle, modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp), contentScale = ContentScale.Fit)
                else Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    Text(previewText ?: "Loading preview…")
                }
                if (previewZipUri != null || previewRarUri != null) {
                    TextButton(onClick = { zipDestinationPicker.launch(null) }) { Text("Extract to folder") }
                }
                if (previewPdfUri != null && pdfPageCount > 0) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(enabled = pdfPage > 0, onClick = {
                            val uri = previewPdfUri ?: return@TextButton
                            val next = pdfPage - 1
                            scope.launch {
                                val rendered = withContext(Dispatchers.IO) { runCatching { renderPdfPage(uri, next) }.getOrNull() }
                                if (rendered?.first != null) { pdfPage = next; previewBitmap = rendered.first }
                            }
                        }) { Text("Previous") }
                        Text("Page ${pdfPage + 1} of $pdfPageCount", style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = pdfPage + 1 < pdfPageCount, onClick = {
                            val uri = previewPdfUri ?: return@TextButton
                            val next = pdfPage + 1
                            scope.launch {
                                val rendered = withContext(Dispatchers.IO) { runCatching { renderPdfPage(uri, next) }.getOrNull() }
                                if (rendered?.first != null) { pdfPage = next; previewBitmap = rendered.first }
                            }
                        }) { Text("Next") }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { previewTitle = null; previewText = null; previewBitmap = null; previewPdfUri = null; previewZipUri = null; previewRarUri = null }) { Text("Close") } })
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
        AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("Move to Recycle Bin?") }, text = { Text("Move " + selected.size + " selected item(s) to the Recycle Bin? You can restore them later or permanently delete them there.") }, confirmButton = {
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
                        for (source in chosen) {
                            val success = withContext(Dispatchers.IO) {
                                val parentPath = findParentSegments(storageRoot, source.uri)
                                    ?: folderStack.drop(1).mapNotNull { it.name }
                                moveDocumentToTrash(context, storageRoot, source, parentPath)
                            }
                            if (success) moved++ else failed++
                        }
                        feedback = "Moved $moved item(s) to Recycle Bin" + if (failed > 0) "; $failed could not be moved" else ""
                        reload()
                        working = false
                        operationJob = null
                    }
                }
            }) { Text("Move to Recycle Bin") }
        }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
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
    if (working) AlertDialog(onDismissRequest = {}, title = { Text(operationName) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(operationDetail.ifBlank { "Working…" }); Text("You can cancel this operation. A folder may contain partially copied items if cancelled.") } }, confirmButton = { TextButton(onClick = { operationJob?.cancel() }) { Text("Cancel") } })
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
        for (child in folder.listFiles()) {
            currentCoroutineContext().ensureActive()
            visited++
            if (visited > 10000) return matches
            val childName = child.name.orEmpty()
            if (!showHidden && childName.startsWith(".")) continue
            val nameMatches = childName.contains(query, ignoreCase = true)
            if (nameMatches && matchesCategory(child, category)) matches.add(child)
            if (child.isDirectory && depth < 32) stack.add(child to depth + 1)
        }
    }
    return matches
}

private fun matchesCategory(file: DocumentFile, category: String): Boolean {
    if (category == "All" || file.isDirectory) return true
    val name = file.name.orEmpty().lowercase()
    val mime = file.type.orEmpty().lowercase()
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
    val created = mutableListOf<DocumentFile>()
    fun rollback() {
        created.asReversed().forEach { runCatching { it.delete() } }
    }
    return try {
        val success = copyDocumentTree(context, source, destination, created, 0)
        if (!success) rollback()
        success
    } catch (cancelled: CancellationException) {
        rollback()
        throw cancelled
    } catch (_: Exception) {
        rollback()
        false
    }
}

private suspend fun copyDocumentTree(
    context: android.content.Context,
    source: DocumentFile,
    destination: DocumentFile,
    created: MutableList<DocumentFile>,
    depth: Int
): Boolean {
    currentCoroutineContext().ensureActive()
    if (depth > 64) return false
    return try {
        if (source.isDirectory) {
            val name = source.name ?: "Folder"
            val existing = destination.findFile(name)
            val folder = when {
                existing == null -> destination.createDirectory(name)?.also { created.add(it) }
                existing.isDirectory -> existing
                else -> null
            } ?: return false
            for (child in source.listFiles()) {
                currentCoroutineContext().ensureActive()
                if (!copyDocumentTree(context, child, folder, created, depth + 1)) return false
            }
            true
        } else {
            val name = source.name ?: "file"
            if (destination.findFile(name) != null) return false
            val target = destination.createFile(source.type ?: "application/octet-stream", name) ?: return false
            created.add(target)
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
    val created = mutableListOf<DocumentFile>()
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
                        created.add(parent)
                    } else parent = existing
                }
                val leaf = segments.lastOrNull().orEmpty()
                if (entry.isDirectory) {
                    if (leaf.isNotBlank() && parent.findFile(leaf) == null) {
                        val folder = parent.createDirectory(leaf) ?: throw IllegalArgumentException("Cannot create archive folder")
                        created.add(folder)
                    } else if (leaf.isNotBlank() && parent.findFile(leaf)?.isDirectory != true) {
                        throw IllegalArgumentException("Cannot create archive folder")
                    }
                } else {
                    if (leaf.isBlank() || parent.findFile(leaf) != null) throw IllegalArgumentException("File already exists or invalid name: $leaf")
                    val outputFile = parent.createFile("application/octet-stream", leaf) ?: throw IllegalArgumentException("Cannot create file")
                    created.add(outputFile)
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
        return "Extracted $extracted file(s) from ZIP archive."
    } catch (error: Exception) {
        created.asReversed().forEach { runCatching { it.delete() } }
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
    var truncated = false
    while (stack.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val (node, depth) = stack.removeLast()
        if (depth > 64) continue
        if (node.isDirectory) {
            folders++
            node.listFiles().forEach { child ->
                if (files + folders + stack.size >= 10000) { truncated = true; return@forEach }
                stack.add(child to depth + 1)
            }
        } else if (node.isFile) {
            files++
            val size = node.length().coerceAtLeast(0L)
            totalBytes += size
            largest.add((node.name ?: "Unnamed") to size)
            if (size > 0) sizeGroups.getOrPut(size) { mutableListOf() }.add(node)
        }
        if (files + folders >= 10000) { truncated = true; break }
    }
    val top = largest.sortedByDescending { it.second }.take(5)
    val digest = MessageDigest.getInstance("SHA-256")
    val duplicateGroups = mutableListOf<List<String>>()
    var bytesHashed = 0L
    for ((size, candidates) in sizeGroups.filter { it.value.size > 1 }.toList().sortedByDescending { it.first }) {
        val hashes = mutableMapOf<String, MutableList<String>>()
        for (file in candidates) {
            currentCoroutineContext().ensureActive()
            if (size > 104857600L || bytesHashed + size > 536870912L) continue
            val input = context.contentResolver.openInputStream(file.uri) ?: continue
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
    report.append("Total file size: ").append(pretty(totalBytes)).append("\n\nLargest files:\n")
    if (top.isEmpty()) report.append("No files found\n")
    top.forEach { report.append("• ").append(it.first.take(70)).append(" — ").append(pretty(it.second)).append('\n') }
    report.append("\nExact duplicate groups: ").append(duplicateGroups.size)
    duplicateGroups.take(5).forEachIndexed { index, group ->
        report.append("\n").append(index + 1).append(". ").append(group.take(4).joinToString(", ") { it.take(40) })
        if (group.size > 4) report.append(" and ").append(group.size - 4).append(" more")
    }
    if (duplicateGroups.isEmpty()) report.append("\nNo exact duplicates found in the files checked.")
    report.append("\n\nDuplicate hashing checks files up to 100 MB each and 512 MB total.")
    if (truncated) report.append("\nScan stopped at the 10,000-item safety limit.")
    return report.toString()
}
