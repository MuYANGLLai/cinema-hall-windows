package com.cinemahall.desktop.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cinemahall.desktop.app.AppPaths
import com.cinemahall.desktop.app.LocalCinemaColors
import com.cinemahall.desktop.library.DesktopCategory
import com.cinemahall.desktop.library.DesktopMovie
import com.cinemahall.desktop.library.DesktopMovieCatalog
import com.cinemahall.desktop.library.ScanPhase
import com.cinemahall.desktop.library.ScanStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 影片库（2026-11-07 第二版：复刻安卓端「影片管理」页）。
 *
 * ── 形状 ────────────────────────────────────────────────────────────────────
 * 每个分类一张卡：卡头是「分类名（N 部）」+ 三颗小图标（加片 / 改名 / 删分类），
 * 卡身是一行**横向滚动**的封面。不再有左侧分类栏、也不再是统一网格 —— 那两种都不是
 * 安卓端的样子，用户对比截图后要求按分类分块。
 *
 * ── 封面 ────────────────────────────────────────────────────────────────────
 * 用**视频自己的首帧**（[CoverCache]，由 `PlayerController.captureCover` 抽帧后落盘）。
 * 找不到才退回片名首字占位。
 */
@Composable
fun LibraryScreen(
    catalog: DesktopMovieCatalog,
    roots: List<String>,
    movies: List<DesktopMovie>,
    categories: List<DesktopCategory>,
    scanning: Boolean,
    stats: ScanStats,
    onAddRoot: (File) -> Unit,
    onRemoveRoot: (String) -> Unit,
    onScan: () -> Unit,
    onCancel: () -> Unit,
    onSelect: (DesktopMovie) -> Unit,
    onAddCategory: (String) -> Unit,
    onRenameCategory: (String, String) -> Unit,
    onDeleteCategory: (String) -> Unit,
    onRenameMovie: (String, String?) -> Unit,
    onMoveMovies: (List<String>, String) -> Unit,
    onRemoveMovies: (List<String>) -> Unit,
    onAddFiles: (List<File>, String) -> Unit,
) {
    val colors = LocalCinemaColors.current
    var pastePath by remember { mutableStateOf("") }
    var pasteOpen by remember { mutableStateOf(false) }
    var rootMenu by remember { mutableStateOf(false) }
    var newCategoryOpen by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    var renamingCategory by remember { mutableStateOf<DesktopCategory?>(null) }
    var deletingCategory by remember { mutableStateOf<DesktopCategory?>(null) }
    var renamingMovie by remember { mutableStateOf<DesktopMovie?>(null) }
    var movingPaths by remember { mutableStateOf<List<String>?>(null) }
    var removingPaths by remember { mutableStateOf<List<String>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val selected = remember { mutableStateListOf<String>() }

    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp)) {
        // ── 卡头：标题 + 三颗主按钮（与安卓端「新建分类 / 扫描影片」同位置）──────
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("影片库", style = MaterialTheme.typography.titleLarge, color = colors.ink)
                Text(
                    if (roots.isEmpty()) "还没添加根目录" else "根目录 ${roots.size} 个 · 影片 ${movies.size} 部",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.ink3,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { chooseDirectory()?.let(onAddRoot) }) { Text("添加根目录…") }
                Spacer(Modifier.width(6.dp))
                Box {
                    TextButton(onClick = { rootMenu = true }) { Text("根目录") }
                    DropdownMenu(expanded = rootMenu, onDismissRequest = { rootMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("粘贴路径…") },
                            onClick = {
                                rootMenu = false
                                pastePath = ""
                                pasteOpen = true
                            },
                        )
                        roots.forEach { root ->
                            DropdownMenuItem(
                                text = { Text("移除 · $root", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = {
                                    rootMenu = false
                                    onRemoveRoot(root)
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = {
                    newCategoryName = ""
                    newCategoryOpen = true
                },
            ) { Text("新建分类") }
            Spacer(Modifier.width(8.dp))
            if (scanning) {
                OutlinedButton(onClick = onCancel) { Text("取消扫描") }
            } else {
                Button(onClick = onScan, enabled = roots.isNotEmpty()) { Text("扫描影片") }
            }
        }

        if (scanning || stats.phase != ScanPhase.IDLE) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (scanning) {
                    LinearProgressIndicator(
                        progress = { if (stats.phase == ScanPhase.FAST) 0f else stats.probedFraction },
                        modifier = Modifier.width(140.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    when (stats.phase) {
                        ScanPhase.IDLE -> "未开始"
                        ScanPhase.FAST -> "快扫中…"
                        ScanPhase.PROBING -> "解析时长 ${stats.probed}/${stats.found}"
                        ScanPhase.DONE -> "扫描完成：${stats.found} 部"
                        ScanPhase.CANCELLED -> "已取消（已列出 ${stats.found} 部）"
                    },
                    color = colors.ink2,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        if (notice != null) {
            Spacer(Modifier.height(6.dp))
            Text(notice!!, color = colors.accent, style = MaterialTheme.typography.labelSmall)
        }

        if (selected.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("已选 ${selected.size} 部", color = colors.accent, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(10.dp))
                // 播放从「点封面」搬到这儿（2026-11-07）：选一部再点它，才去影厅。
                TextButton(
                    enabled = selected.size == 1,
                    onClick = {
                        val path = selected.firstOrNull()
                        movies.firstOrNull { it.path.equals(path, ignoreCase = true) }?.let(onSelect)
                    },
                ) { Text("播放") }
                TextButton(onClick = { movingPaths = selected.toList() }) { Text("移动到…") }
                TextButton(
                    enabled = selected.size == 1,
                    onClick = {
                        val path = selected.firstOrNull()
                        renamingMovie = movies.firstOrNull { it.path.equals(path, ignoreCase = true) }
                    },
                ) { Text("改名") }
                TextButton(onClick = { removingPaths = selected.toList() }) { Text("移除", color = colors.danger) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { selected.clear() }) { Text("清空选择") }
            }
        }

        Spacer(Modifier.height(10.dp))
        if (movies.isEmpty() && categories.size <= 1) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有影片。添加根目录后点「扫描影片」。", color = colors.ink3)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(categories, key = { it.id }) { category ->
                    val inCategory = movies.filter { it.categoryId == category.id }
                    CategorySection(
                        category = category,
                        movies = inCategory,
                        selected = selected,
                        onToggleSelect = { movie ->
                            val existing = selected.firstOrNull { it.equals(movie.path, ignoreCase = true) }
                            if (existing != null) selected.remove(existing) else selected.add(movie.path)
                        },
                        onPlay = { onSelect(it) },
                        onRenameMovie = { renamingMovie = it },
                        onMoveMovie = { movingPaths = listOf(it.path) },
                        onRemoveMovie = { removingPaths = listOf(it.path) },
                        onAddFiles = {
                            chooseVideoFiles()?.let { files ->
                                if (files.isNotEmpty()) {
                                    onAddFiles(files, category.id)
                                    notice = "已把 ${files.size} 个文件加入「${category.name}」。"
                                }
                            }
                        },
                        onRenameCategory = { renamingCategory = category },
                        onDeleteCategory = { deletingCategory = category },
                    )
                }
            }
        }
    }

    // ── 对话框 ──────────────────────────────────────────────────────────────

    if (pasteOpen) {
        AlertDialog(
            onDismissRequest = { pasteOpen = false },
            title = { Text("添加根目录") },
            text = {
                TextField(
                    value = pastePath,
                    onValueChange = { pastePath = it },
                    singleLine = true,
                    placeholder = { Text("例如 D:\\Movies") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val path = pastePath.trim()
                        pasteOpen = false
                        if (path.isNotEmpty()) onAddRoot(File(path))
                    },
                ) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { pasteOpen = false }) { Text("取消") } },
        )
    }

    if (newCategoryOpen) {
        AlertDialog(
            onDismissRequest = { newCategoryOpen = false },
            title = { Text("新建分类") },
            text = {
                TextField(
                    value = newCategoryName,
                    onValueChange = { newCategoryName = it },
                    singleLine = true,
                    placeholder = { Text("比如：纪录片") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        newCategoryOpen = false
                        onAddCategory(newCategoryName)
                        notice = "已新建分类「${newCategoryName.trim()}」，它在这一页最下面。"
                    },
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { newCategoryOpen = false }) { Text("取消") } },
        )
    }

    renamingCategory?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renamingCategory = null },
            title = { Text("重命名分类") },
            text = { TextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(
                    onClick = {
                        renamingCategory = null
                        onRenameCategory(target.id, text)
                    },
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renamingCategory = null }) { Text("取消") } },
        )
    }

    deletingCategory?.let { target ->
        val count = movies.count { it.categoryId == target.id }
        AlertDialog(
            onDismissRequest = { deletingCategory = null },
            title = { Text("删除分类「${target.name}」") },
            text = { Text("里面的 $count 部影片会回到默认分类，影片本身不会被删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletingCategory = null
                        onDeleteCategory(target.id)
                    },
                ) { Text("删除分类") }
            },
            dismissButton = { TextButton(onClick = { deletingCategory = null }) { Text("取消") } },
        )
    }

    renamingMovie?.let { target ->
        var text by remember(target.path) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renamingMovie = null },
            title = { Text("修改显示名称") },
            text = {
                Column {
                    TextField(value = text, onValueChange = { text = it }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    Text("只改库里显示的名字，磁盘上的文件名不动。留空 = 恢复默认。", color = colors.ink3, style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renamingMovie = null
                        onRenameMovie(target.path, text)
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            renamingMovie = null
                            onRenameMovie(target.path, null)
                        },
                    ) { Text("恢复默认") }
                    TextButton(onClick = { renamingMovie = null }) { Text("取消") }
                }
            },
        )
    }

    movingPaths?.let { paths ->
        AlertDialog(
            onDismissRequest = { movingPaths = null },
            title = { Text("移动 ${paths.size} 部影片到") },
            text = {
                Column {
                    categories.forEach { category ->
                        Text(
                            category.name,
                            color = colors.ink,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    movingPaths = null
                                    onMoveMovies(paths, category.id)
                                    selected.clear()
                                }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { movingPaths = null }) { Text("取消") } },
        )
    }

    removingPaths?.let { paths ->
        AlertDialog(
            onDismissRequest = { removingPaths = null },
            title = { Text("从库里移除 ${paths.size} 部影片") },
            text = { Text("只移除记录，磁盘上的文件不动。下次扫描会把它们重新扫回来。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        removingPaths = null
                        onRemoveMovies(paths)
                        selected.clear()
                    },
                ) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { removingPaths = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun CategorySection(
    category: DesktopCategory,
    movies: List<DesktopMovie>,
    selected: List<String>,
    onToggleSelect: (DesktopMovie) -> Unit,
    onPlay: (DesktopMovie) -> Unit,
    onRenameMovie: (DesktopMovie) -> Unit,
    onMoveMovie: (DesktopMovie) -> Unit,
    onRemoveMovie: (DesktopMovie) -> Unit,
    onAddFiles: () -> Unit,
    onRenameCategory: () -> Unit,
    onDeleteCategory: () -> Unit,
) {
    val colors = LocalCinemaColors.current

    // 预取封面：`LazyRow` 只组合视口内的卡片，不预取的话右侧那些片永远没有封面
    // （用户一横向滚动，看到的是一排「首字占位」）。
    LaunchedEffect(movies.map { it.path }) {
        movies.take(10).forEach { movie -> CoverCache.load(movie) }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface2)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${category.name}（${movies.size} 部）",
                style = MaterialTheme.typography.titleSmall,
                color = colors.ink,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(10.dp))
            ActionGlyph("＋", "往这个分类里加影片") { onAddFiles() }
            if (!category.isDefault) {
                ActionGlyph("✎", "重命名分类") { onRenameCategory() }
                ActionGlyph("🗑", "删除分类") { onDeleteCategory() }
            }
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        if (movies.isEmpty()) {
            Text(
                "这个分类里还没有影片。",
                color = colors.ink3,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(movies, key = { it.path }) { movie ->
                    val checked = selected.any { it.equals(movie.path, ignoreCase = true) }
                    CoverTile(
                        movie = movie,
                        checked = checked,
                        onToggleSelect = { onToggleSelect(movie) },
                        onPlay = { onPlay(movie) },
                        onRename = { onRenameMovie(movie) },
                        onMove = { onMoveMovie(movie) },
                        onRemove = { onRemoveMovie(movie) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionGlyph(glyph: String, label: String, onClick: () -> Unit) {
    val colors = LocalCinemaColors.current
    Text(
        glyph,
        color = colors.accent,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * 一格封面（对齐安卓端截图：横版 16:9 图 + 名字压在图上）。
 */
@Composable
private fun CoverTile(
    movie: DesktopMovie,
    checked: Boolean,
    onToggleSelect: () -> Unit,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalCinemaColors.current
    val cover = rememberCover(movie)
    var menu by remember { mutableStateOf(false) }

    Box(
        Modifier
            .width(168.dp)
            .height(96.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.videoBackdrop)
            .border(
                width = if (checked) 2.dp else 0.dp,
                color = if (checked) colors.accent else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            // 点封面 = **选中**（2026-11-07 用户口径），不再是「跳到影厅播放」。
            // 播放挪到选中后出现的工具条里那颗「播放」，是一次明确的动作 ——
            // 想整理片子的人不该被一次误点拉走。
            .clickable(onClickLabel = "选中", onClick = onToggleSelect),
    ) {
        if (cover != null) {
            Image(
                bitmap = cover,
                contentDescription = movie.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 还没抽出首帧（或抽帧失败）：先给一个深色块 + 片名首字，不是空白。
            Box(
                Modifier.fillMaxSize().background(colors.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    movie.title.take(1).uppercase(),
                    color = colors.accent.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }
        // 名字压在封面底部（截图同款）：半透明黑底，长名字省略。
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                movie.title,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 勾选：左上角小圆，只做多选，不触发播放。
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(if (checked) colors.accent else Color.Black.copy(alpha = 0.45f))
                .border(1.dp, if (checked) colors.accent else Color.White.copy(alpha = 0.6f), CircleShape)
                .clickable { onToggleSelect() },
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Text("✓", color = Color.Black, style = MaterialTheme.typography.labelSmall)
        }
        Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            Text(
                "⋯",
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { menu = true }
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                // 播放还留着一条明确入口（点封面已经改成选中，见上面那颗 clickable 的注释）。
                DropdownMenuItem(
                    text = { Text("在影厅播放") },
                    onClick = {
                        menu = false
                        onPlay()
                    },
                )
                DropdownMenuItem(
                    text = { Text("修改显示名称…") },
                    onClick = {
                        menu = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text("移动到…") },
                    onClick = {
                        menu = false
                        onMove()
                    },
                )
                DropdownMenuItem(
                    text = { Text("加入多选") },
                    onClick = {
                        menu = false
                        onToggleSelect()
                    },
                )
                DropdownMenuItem(
                    text = { Text("从库中移除") },
                    onClick = {
                        menu = false
                        onRemove()
                    },
                )
            }
        }
    }
}

/**
 * 封面缓存 + 抽帧。
 *
 * 三档：内存 → `data/covers 下的 jpg` → 现场抽帧（串行，一次只开一个播放器）。
 * 抽帧本身由 [generator] 提供（`Main` 把它接到 `PlayerController.captureCover`），
 * 这样这层不需要认识 libVLC。
 */
internal object CoverCache {
    private val cache = ConcurrentHashMap<String, Any>()
    private val miss = Any()
    // 抽帧要开一个真实播放器（约 3 秒/部）。并发 2 是权衡：一个太慢（十几部要等一分多钟），
    // 再多会和正在播放的主播放器抢解码器。
    private val gate = Semaphore(2)

    /** 由 `Main` 注入：抽一帧到指定文件，成功返回 true。 */
    var generator: ((path: String, out: File) -> Boolean)? = null

    fun peek(path: String): ImageBitmap? = cache[path] as? ImageBitmap

    fun dir(): File = File(AppPaths.dataDir, "covers").apply { mkdirs() }

    private fun fileFor(movie: DesktopMovie): File =
        File(dir(), Integer.toHexString(movie.path.lowercase().hashCode()) + "-v5.jpg")

    suspend fun load(movie: DesktopMovie): ImageBitmap? {
        cache[movie.path]?.let { return it as? ImageBitmap }
        // 同目录同名图片优先（用户自己放的海报比首帧好看，也更快）。
        val local = localArtwork(movie)
        val file = local ?: fileFor(movie)
        if (!file.isFile) {
            val ok = gate.withPermit {
                if (file.isFile) {
                    true
                } else {
                    withContext(Dispatchers.IO) {
                        runCatching { generator?.invoke(movie.path, file) ?: false }.getOrDefault(false)
                    }
                }
            }
            if (!ok) {
                cache[movie.path] = miss
                return null
            }
        }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { SkiaImage.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull()
        }
        cache[movie.path] = bitmap ?: miss
        return bitmap
    }

    private fun localArtwork(movie: DesktopMovie): File? {
        val file = File(movie.path)
        val dir = file.parentFile ?: return null
        val base = file.nameWithoutExtension
        val candidates = buildList {
            listOf("jpg", "jpeg", "png", "webp").forEach { ext -> add(File(dir, "$base.$ext")) }
            listOf("poster", "folder", "cover").forEach { name ->
                listOf("jpg", "jpeg", "png", "webp").forEach { ext -> add(File(dir, "$name.$ext")) }
            }
        }
        return candidates.firstOrNull { it.isFile }
    }

    /** 抽帧写完之后清掉这一条的失败标记，让界面下次能重试。 */
    fun invalidate(path: String) {
        cache.remove(path)
    }
}

@Composable
internal fun rememberCover(movie: DesktopMovie): ImageBitmap? {
    val state by produceState(initialValue = CoverCache.peek(movie.path), movie.path) {
        if (value == null) value = CoverCache.load(movie)
    }
    return state
}

/** 视频文件多选（「+」用）。 */
internal fun chooseVideoFiles(): List<File>? {
    var result: List<File>? = null
    val action = {
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = "选择要加入影片库的文件"
            isMultiSelectionEnabled = true
            fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY
        }
        if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
            result = chooser.selectedFiles.toList()
        }
    }
    if (javax.swing.SwingUtilities.isEventDispatchThread()) action() else javax.swing.SwingUtilities.invokeAndWait(action)
    return result
}
