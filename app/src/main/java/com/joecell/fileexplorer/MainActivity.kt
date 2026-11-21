package com.joecell.fileexplorer

import android.content.Intent
import android.os.Bundle
import android.os.StatFs
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.style.TextOverflow
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
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
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
    var viewMode by remember { mutableStateOf("List") }
    var previewTitle by remember { mutableStateOf<String?>(null) }
    var previewText by remember { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf(context.getSharedPreferences("file_history", 0).getString("items", "").orEmpty().lines().filter { it.isNotBlank() }) }
    val scope = rememberCoroutineScope()

    fun rememberOpened(file: DocumentFile) {
        val entry = listOf(file.uri.toString(), file.name.orEmpty(), file.type.orEmpty()).joinToString("\\t")
        recent = (listOf(entry) + recent.filterNot { it.substringBefore('\\t') == file.uri.toString() }).take(30)
        context.getSharedPreferences("file_history", 0).edit().putString("items", recent.joinToString("\\n")).apply()
    }
    fun openFile(file: DocumentFile) {
        rememberOpened(file)
        val mime = file.type.orEmpty()
        if (mime.startsWith("text/") || file.name.orEmpty().endsWith(".txt", true) || file.name.orEmpty().endsWith(".log", true) || file.name.orEmpty().endsWith(".json", true) || file.name.orEmpty().endsWith(".xml", true) || file.name.orEmpty().endsWith(".csv", true)) {
            scope.launch {
                val content = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText().take(65536) } }.getOrNull() }
                previewTitle = file.name ?: "Text preview"
                previewText = content ?: "Unable to read this file."
            }
        } else {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.type ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            root = DocumentFile.fromTreeUri(context, uri)
            tab = 1
            selected = emptySet()
        }
    }
    val destinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val destination = DocumentFile.fromTreeUri(context, uri)
            if (destination != null) {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                val sources = root?.listFiles()?.filter { it.uri.toString() in selected }.orEmpty()
                if (sources.any { it.uri == destination.uri }) {
                    feedback = "Choose a different destination folder"
                } else {
                    val shouldMove = moving
                    scope.launch {
                        working = true
                        val result = withContext(Dispatchers.IO) {
                            var copied = 0
                            var failed = 0
                            sources.forEach { source ->
                                val ok = copyDocument(context, source, destination)
                                if (ok) { copied++; if (shouldMove) source.delete() } else failed++
                            }
                            copied to failed
                        }
                        working = false
                        selected = emptySet()
                        refresh++
                        feedback = (if (shouldMove) "Moved " else "Copied ") + result.first + " item(s)" + if (result.second > 0) "; " + result.second + " failed" else ""
                    }
                }
            }
        }
    }
    val allFiles = remember(root, refresh, sortBy, descending) {
        val all = root?.listFiles()?.toList().orEmpty()
        val ordered = when (sortBy) {
            "Size" -> all.sortedBy { it.length() }
            "Type" -> all.sortedBy { it.type.orEmpty().lowercase() }
            "Modified" -> all.sortedBy { it.lastModified() }
            else -> all.sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name.orEmpty().lowercase() })
        }
        if (descending) ordered.reversed() else ordered
    }
    val shownFiles = allFiles.filter { it.name.orEmpty().contains(query, true) }
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
        }
    }, containerColor = Color(0xFFF8FAFD)) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("FILE MANAGER", color = Muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Text(if (tab == 1) if (selected.isEmpty()) "Browse files" else selected.size.toString() + " selected" else if (tab == 2) "Recent files" else "Explore", style = MaterialTheme.typography.headlineLarge, color = Ink, fontWeight = FontWeight.Bold)
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
            if (tab == 0) {
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
                            CategoryTile("Images", Icons.Default.Image, Color(0xFFFFF0E5), Color(0xFFE88B43), Modifier.weight(1f)) { context.startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)) }
                            CategoryTile("Videos", Icons.Default.PlayCircle, Color(0xFFFFE9EB), Color(0xFFE45B68), Modifier.weight(1f)) { context.startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CategoryTile("Audio", Icons.Default.MusicNote, Color(0xFFF0EAFE), Color(0xFF8C68D9), Modifier.weight(1f)) { context.startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE)) }
                            CategoryTile("Documents", Icons.Default.Description, Color(0xFFE6F1FF), Accent, Modifier.weight(1f)) { context.startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/*").addCategory(Intent.CATEGORY_OPENABLE)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CategoryTile("Downloads", Icons.Default.Download, Color(0xFFE7F6EF), Color(0xFF31996A), Modifier.weight(1f)) { picker.launch(null) }
                            CategoryTile("APKs", Icons.Default.Android, Color(0xFFF0F2F5), Color(0xFF657181), Modifier.weight(1f)) { context.startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/vnd.android.package-archive").addCategory(Intent.CATEGORY_OPENABLE)) }
                        }
                    }
                }
                item { TextButton(onClick = { picker.launch(null) }) { Text("Browse storage") } }
            } else if (tab == 2) {
                if (recent.isEmpty()) item { EmptyState("No recent files yet", "Files you open will appear here", Icons.Default.Schedule) }
                else items(recent, key = { it.substringBefore('\\t') }) { entry ->
                    val parts = entry.split('\\t')
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
                    }
                }
            } else {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(root?.name ?: "Select a folder", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), color = Ink)
                        if (root != null) TextButton(onClick = { picker.launch(null) }) { Text("Change") }
                    }
                }
                if (root == null) item { EmptyState("Choose a folder to begin", "Android will ask you to grant access", Icons.Default.FolderOpen) }
                else if (shownFiles.isEmpty()) item { EmptyState("No matching files", "Try another search", Icons.Default.SearchOff) }
                else if (viewMode == "Grid" && selected.isEmpty()) {
                    shownFiles.chunked(2).forEach { pair -> item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { file ->
                                Card(Modifier.weight(1f).height(112.dp).clickable {
                                    if (file.isDirectory) { root = file; query = ""; selected = emptySet() } else openFile(file)
                                }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                        Icon(if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null, tint = Accent, modifier = Modifier.size(28.dp))
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
                            else if (file.isDirectory) { root = file; query = ""; selected = emptySet() }
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
                        if (file.isDirectory) Icon(Icons.Default.ChevronRight, null, tint = Muted)
                    }
                }
                if (selected.isNotEmpty()) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val chosen = allFiles.filter { it.uri.toString() in selected }
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

    if (previewTitle != null) {
        AlertDialog(onDismissRequest = { previewTitle = null; previewText = null }, title = { Text(previewTitle ?: "Preview") }, text = { Text(previewText ?: "Loading preview…", maxLines = 28, overflow = TextOverflow.Ellipsis) }, confirmButton = { TextButton(onClick = { previewTitle = null; previewText = null }) { Text("Close") } })
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
        AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("Delete selected items?") }, text = { Text("This permanently deletes " + selected.size + " selected item(s), including folder contents.") }, confirmButton = {
            TextButton(onClick = {
                val chosen = allFiles.filter { it.uri.toString() in selected }
                val failures = chosen.count { !it.delete() }
                feedback = if (failures == 0) "Deleted " + chosen.size + " item(s)" else "Could not delete " + failures + " item(s)"
                reload()
                dialog = ""
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
    }
    if (working) AlertDialog(onDismissRequest = {}, title = { Text(if (moving) "Moving files" else "Copying files") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Please keep the app open while the operation completes.") } }, confirmButton = {})
    feedback?.let { note ->
        AlertDialog(onDismissRequest = { feedback = null }, title = { Text("File Explorer") }, text = { Text(note) }, confirmButton = { TextButton(onClick = { feedback = null }) { Text("OK") } })
    }
}

private fun readableSize(bytes: Long): String = when {
    bytes >= 1073741824L -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= 1048576L -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> bytes.toString() + " B"
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

private fun copyDocument(context: android.content.Context, source: DocumentFile, destination: DocumentFile): Boolean {
    return try {
        if (source.isDirectory) {
            val folder = destination.findFile(source.name ?: "Folder") ?: destination.createDirectory(source.name ?: "Folder") ?: return false
            source.listFiles().all { copyDocument(context, it, folder) }
        } else {
            val name = source.name ?: "file"
            val target = destination.createFile(source.type ?: "application/octet-stream", name) ?: return false
            val input = context.contentResolver.openInputStream(source.uri) ?: return false
            val output = context.contentResolver.openOutputStream(target.uri) ?: return false
            input.use { ins -> output.use { outs -> ins.copyTo(outs) } }
            true
        }
    } catch (_: Exception) { false }
}
