package com.joecell.fileexplorer

import android.content.Intent
import android.os.Bundle
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

@Composable
private fun FileHome() {
    val context = LocalContext.current
    var root by remember { mutableStateOf<DocumentFile?>(null) }
    var query by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            root = DocumentFile.fromTreeUri(context, uri)
            selectedTab = 1
        }
    }
    val files = remember(root) { root?.listFiles()?.sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name?.lowercase() }) ?: emptyList() }
    val visible = files.filter { it.name.orEmpty().contains(query, ignoreCase = true) }
    val stat = remember { runCatching { StatFs(android.os.Environment.getDataDirectory().path) }.getOrNull() }
    val total = stat?.totalBytes ?: 0L
    val free = stat?.availableBytes ?: 0L
    val used = (total - free).coerceAtLeast(0)
    val fmt = remember { DecimalFormat("#,##0.0") }
    Scaffold(bottomBar = {
        NavigationBar(containerColor = Color.White) {
            NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
            NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1; if (root == null) picker.launch(null) }, icon = { Icon(Icons.Default.Folder, null) }, label = { Text("Browse") })
            NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Default.Schedule, null) }, label = { Text("Recent") })
        }
    }, containerColor = Color(0xFFF8FAFD)) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("FILE MANAGER", color = Muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Text(if (selectedTab == 1) "Browse files" else if (selectedTab == 2) "Recent files" else "Explore", style = MaterialTheme.typography.headlineLarge, color = Ink, fontWeight = FontWeight.Bold)
                    }
                    Surface(shape = CircleShape, color = Color(0xFFE8F1FF), modifier = Modifier.size(46.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, null, tint = Accent) } }
                }
            }
            item {
                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search files and folders") }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, null) } }, shape = RoundedCornerShape(18.dp), singleLine = true, colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White, focusedContainerColor = Color.White))
            }
            if (selectedTab == 0) {
                item {
                    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text("Internal storage", color = Ink, fontWeight = FontWeight.SemiBold); Text("${fmt.format(used / 1_073_741_824.0)} GB used of ${fmt.format(total / 1_073_741_824.0)} GB", color = Muted, style = MaterialTheme.typography.bodySmall) }
                                Icon(Icons.Default.ChevronRight, null, tint = Muted)
                            }
                            LinearProgressIndicator(progress = { if (total > 0) used.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth().height(8.dp), color = Accent, trackColor = SurfaceSoft)
                            Text("${fmt.format(free / 1_073_741_824.0)} GB available", color = Muted, style = MaterialTheme.typography.labelMedium)
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
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Browse storage", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
                        TextButton(onClick = { picker.launch(null) }) { Text("Open") }
                    }
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth().clickable { picker.launch(null) }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFE8F1FF), modifier = Modifier.size(46.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.FolderOpen, null, tint = Accent) } }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) { Text("Choose a folder", color = Ink, fontWeight = FontWeight.SemiBold); Text("Browse files with secure folder access", color = Muted, style = MaterialTheme.typography.bodySmall) }
                            Icon(Icons.Default.ChevronRight, null, tint = Muted)
                        }
                    }
                }
            } else if (selectedTab == 2) {
                item { EmptyState("No recent files yet", "Files you open will appear here", Icons.Default.Schedule) }
            } else {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(root?.name ?: "Select a folder", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), color = Ink)
                        TextButton(onClick = { picker.launch(null) }) { Text("Change") }
                    }
                }
                if (root == null) item { EmptyState("Choose a folder to begin", "Android will ask you to grant access to the folder", Icons.Default.FolderOpen) }
                else if (visible.isEmpty()) item { EmptyState("No matching files", "Try another search or choose a different folder", Icons.Default.SearchOff) }
                else items(visible, key = { it.uri.toString() }) { file ->
                    Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).clickable {
                        if (file.isDirectory) { root = file; query = "" }
                        else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.type ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null, tint = if (file.isDirectory) Accent else Muted, modifier = Modifier.size(28.dp))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(file.name ?: "Unnamed", color = Ink, fontWeight = FontWeight.Medium); Text(if (file.isDirectory) "Folder" else file.type ?: "File", color = Muted, style = MaterialTheme.typography.bodySmall) }
                        Icon(Icons.Default.ChevronRight, null, tint = Muted)
                    }
                }
            }
        }
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
