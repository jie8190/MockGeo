package com.mockgeo.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mockgeo.core.model.*

@Composable
internal fun SearchPanel(vm: HomeViewModel, onChosen: () -> Unit, onCoordinates: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results by vm.searchResults.collectAsState()
    val searching by vm.searching.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("城市、地址、地标") },
            leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) { keyboard?.hide(); vm.search(query.trim()) } }),
            trailingIcon = {
                IconButton(onClick = { keyboard?.hide(); vm.search(query.trim()) }, enabled = query.isNotBlank() && !searching) {
                    if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.ArrowForward, "搜索")
                }
            })
        TextButton(onClick = onCoordinates, contentPadding = PaddingValues(vertical = 10.dp)) {
            Icon(Icons.Default.MyLocation, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("使用经纬度精确选点")
        }
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.heightIn(min = 170.dp, max = 390.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (results.isEmpty() && !searching) item { EmptyState(Icons.Default.TravelExplore, "从一个地点开始", "搜索目的地，或直接在地图上选点") }
            items(results) { place ->
                ListItem(headlineContent = { Text(place.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(coordinates(place.position), style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { Icon(Icons.Default.Place, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                    modifier = Modifier.clickable {
                        vm.select(place.position); vm.setSelectedName(place.name.substringBefore(','))
                        vm.addHistory(place.position, HistorySource.SEARCH); keyboard?.hide(); onChosen()
                    })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            }
        }
    }
}

@Composable
internal fun FavoritesPanel(vm: HomeViewModel, onChosen: () -> Unit, onAdd: () -> Unit) {
    val favorites by vm.favorites.collectAsState()
    val selected by vm.selected.collectAsState()
    var delete by remember { mutableStateOf<Favorite?>(null) }
    var editing by remember { mutableStateOf<Favorite?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val filtered = remember(favorites, query) { favorites.filter { query.isBlank() || (it.name + " " + coordinates(LatLng(it.lat, it.lng))).contains(query.trim(), ignoreCase = true) } }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        uri?.let { vm.exportFavorites(it)?.let(vm::postMessage) }
    }
    Column(Modifier.padding(horizontal = 12.dp)) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 10.dp), singleLine = true,
            placeholder = { Text("筛选名称或坐标") }, leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(14.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${filtered.size} 个收藏位置", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { exporter.launch("mockgeo-favorites.gpx") }, enabled = favorites.isNotEmpty()) { Icon(Icons.Default.FileDownload, "导出收藏") }
            FilledTonalButton(onClick = onAdd, enabled = selected != null, contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Default.Add, null, Modifier.size(17.dp)); Text("收藏选点") }
        }
        LazyColumn(Modifier.heightIn(min = 200.dp, max = 460.dp), contentPadding = PaddingValues(bottom = 22.dp)) {
            if (favorites.isEmpty()) item { EmptyState(Icons.Default.BookmarkBorder, "收藏常用的地方", "选中位置后点击星标，下次一键到达") }
            else if (filtered.isEmpty()) item { EmptyState(Icons.Default.SearchOff, "没有匹配的收藏", "试试其他名称或坐标") }
            items(filtered, key = { it.id }) { favorite ->
                var menu by remember { mutableStateOf(false) }
                ListItem(headlineContent = { Text(favorite.name, maxLines = 1, fontWeight = FontWeight.Medium) },
                    supportingContent = { Text(coordinates(LatLng(favorite.lat, favorite.lng)), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                vm.startFixedAt(context, LatLng(favorite.lat, favorite.lng), favorite.name, HistorySource.FAVORITE)
                                onChosen()
                            }) {
                                Icon(Icons.Default.PlayArrow, "立即模拟 ${favorite.name}", tint = MaterialTheme.colorScheme.primary)
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "管理 ${favorite.name}") }
                                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("编辑收藏") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; editing = favorite })
                                    DropdownMenuItem(text = { Text("复制位置") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { menu = false; copyPosition(context, favorite.name, LatLng(favorite.lat, favorite.lng)); vm.postMessage("位置已复制") })
                                    DropdownMenuItem(text = { Text("分享位置") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { menu = false; sharePosition(context, favorite.name, LatLng(favorite.lat, favorite.lng)) })
                                    DropdownMenuItem(text = { Text("删除") }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, onClick = { menu = false; delete = favorite })
                                }
                            }
                        }
                    },
                    modifier = Modifier.clickable {
                        vm.selectFavorite(favorite); onChosen()
                    })
                HorizontalDivider(Modifier.padding(horizontal = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            }
        }
    }
    delete?.let { favorite -> ConfirmDialog("删除收藏？", "「${favorite.name}」将从收藏中移除。", "删除", { delete = null }) { vm.removeFavorite(favorite.id); delete = null } }
    editing?.let { favorite -> FavoriteEditDialog(favorite, onDismiss = { editing = null }) { name, position, altitude ->
        vm.updateFavorite(favorite.id, name, position, altitude); editing = null
    } }
}

@Composable
internal fun HistoryPanel(vm: HomeViewModel, onChosen: () -> Unit) {
    val history by vm.history.collectAsState()
    var clear by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var delete by remember { mutableStateOf<HistoryEntry?>(null) }
    var editing by remember { mutableStateOf<HistoryEntry?>(null) }
    val context = LocalContext.current
    val filtered = remember(history, query) { history.filter {
        query.isBlank() || (it.extra.orEmpty() + " " + coordinates(LatLng(it.lat, it.lng)) + " " + sourceLabel(it.source) + " " + historyTime(it.createdAt)).contains(query.trim(), ignoreCase = true)
    } }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        uri?.let { vm.exportHistory(it)?.let(vm::postMessage) }
    }
    Column(Modifier.padding(horizontal = 12.dp)) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 10.dp), singleLine = true,
            placeholder = { Text("筛选地点、坐标或日期") }, leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(14.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${filtered.size} 条记录", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { exporter.launch("mockgeo-history.gpx") }, enabled = history.isNotEmpty()) { Icon(Icons.Default.FileDownload, "导出历史") }
            TextButton(onClick = { clear = true }, enabled = history.isNotEmpty()) { Text("清空") }
        }
        LazyColumn(Modifier.heightIn(min = 200.dp, max = 460.dp), contentPadding = PaddingValues(bottom = 22.dp)) {
            if (history.isEmpty()) item { EmptyState(Icons.Default.History, "还没有历史记录", "搜索、手动输入和模拟过的位置会出现在这里") }
            else if (filtered.isEmpty()) item { EmptyState(Icons.Default.SearchOff, "没有匹配的记录", "试试地点名称、坐标或日期") }
            items(filtered, key = { it.id }) { item ->
                val pos = LatLng(item.lat, item.lng)
                var menu by remember { mutableStateOf(false) }
                ListItem(headlineContent = { Text(item.extra?.takeIf { it.isNotBlank() } ?: coordinates(pos), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Column {
                        if (!item.extra.isNullOrBlank()) Text(coordinates(pos), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                        Text("${sourceLabel(item.source)} · ${historyTime(item.createdAt)}", style = MaterialTheme.typography.bodySmall)
                    } },
                    leadingContent = { Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                vm.startFixedAt(context, pos, item.extra, item.source)
                                onChosen()
                            }) {
                                Icon(Icons.Default.PlayArrow, "立即模拟历史位置", tint = MaterialTheme.colorScheme.primary)
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "管理历史位置") }
                                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("编辑后选用") }, leadingIcon = { Icon(Icons.Default.EditLocationAlt, null) }, onClick = { menu = false; editing = item })
                                    DropdownMenuItem(text = { Text("复制位置") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { menu = false; copyPosition(context, item.extra, pos); vm.postMessage("位置已复制") })
                                    DropdownMenuItem(text = { Text("分享位置") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { menu = false; sharePosition(context, item.extra, pos) })
                                    DropdownMenuItem(text = { Text("删除此记录") }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, onClick = { menu = false; delete = item })
                                }
                            }
                        }
                    }, modifier = Modifier.clickable { vm.select(pos); item.extra?.let(vm::setSelectedName); onChosen() })
                HorizontalDivider(Modifier.padding(horizontal = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            }
        }
    }
    if (clear) ConfirmDialog("清空历史记录？", "已收藏的位置和路线会继续保留。", "清空", { clear = false }) { vm.clearHistory(); clear = false }
    delete?.let { item -> ConfirmDialog("删除这条历史记录？", item.extra ?: coordinates(LatLng(item.lat, item.lng)), "删除", { delete = null }) { vm.deleteHistory(item.id); delete = null } }
    editing?.let { item -> CoordinateDialog(LatLng(item.lat, item.lng), onDismiss = { editing = null }, title = "编辑后选用") { position ->
        vm.select(position); item.extra?.let(vm::setSelectedName); vm.addHistory(position, HistorySource.MANUAL); editing = null; onChosen()
    } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RoutePanel(vm: HomeViewModel, onEditMap: () -> Unit, onPreview: (List<LatLng>) -> Unit, onStart: () -> Unit) {
    val draft by vm.routeDraft.collectAsState()
    val controls by vm.routeControls.collectAsState()
    val planned by vm.routePlanned.collectAsState()
    val profile by vm.routeProfile.collectAsState()
    val routes by vm.routes.collectAsState()
    val planning by vm.planning.collectAsState()
    val selected by vm.selected.collectAsState()
    val settings by vm.settings.collectAsState()
    val sim by vm.sim.collectAsState()
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(RouteMode.ONCE) }
    var nameOpen by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Route?>(null) }
    var delete by remember { mutableStateOf<Route?>(null) }
    var clear by remember { mutableStateOf(false) }
    var editPoint by remember { mutableStateOf<Int?>(null) }
    val distance = remember(draft) { draft.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) } }
    val importedTrack = controls.isEmpty() && draft.isNotEmpty()
    val canStart = sim.engineState == EngineState.Idle
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importGpx(it)?.let(vm::postMessage) } }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri -> uri?.let { vm.exportGpx(it)?.let(vm::postMessage) } }
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Route, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp)); Text(when {
                            draft.isEmpty() -> "创建一条路线"
                            importedTrack -> "导入轨迹 · ${draft.size} 个点"
                            else -> "当前路线 · ${controls.size} 个途经点"
                        }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(12.dp))
                    if (draft.isEmpty()) Text("在地图上连接途经点，或导入 GPX 轨迹。", style = MaterialTheme.typography.bodySmall)
                    else {
                        Text("起点  ${coordinates(draft.first())}", style = MaterialTheme.typography.bodySmall)
                        Text("终点  ${coordinates(draft.last())}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 5.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("${distanceLabel(distance)}   ·   预计 ${durationLabel(distance / settings.routeSpeedMps)}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(when { importedTrack -> "保留原始轨迹"; planned -> "已沿道路规划"; else -> "按途经点直线连接" }, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (importedTrack) vm.postMessage("请先保存并清空当前轨迹，再创建新的途经点路线") else onEditMap()
                }) { Icon(Icons.Default.AddLocationAlt, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("地图选点") }
                OutlinedButton(onClick = { selected?.let { vm.addRoutePoint(it) } }, enabled = selected != null && controls.size < 100) { Text("加入当前点") }
                OutlinedButton(onClick = { vm.removeLastRoutePoint() }, enabled = controls.isNotEmpty()) { Icon(Icons.Default.Undo, "撤销末点", Modifier.size(17.dp)) }
                OutlinedButton(onClick = { clear = true }, enabled = draft.isNotEmpty()) { Text("清空") }
                OutlinedButton(onClick = { vm.reverseRouteDraft() }, enabled = draft.size >= 2 && !planning) { Icon(Icons.Default.SwapVert, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("反向") }
                OutlinedButton(onClick = { vm.planOnline() }, enabled = controls.size >= 2 && !planning) {
                    if (planning) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp) else Icon(Icons.Default.AltRoute, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp)); Text(if (planning) "规划中" else "沿道路规划")
                }
                OutlinedButton(onClick = { onPreview(draft) }, enabled = draft.size >= 2) { Icon(Icons.Default.Map, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("预览") }
            }
            Text(if (importedTrack) "新建途经点路线前，请先保存并清空当前轨迹。" else "地图标记支持拖动；也可编辑下方坐标。最多 100 个途经点。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("walk" to "步行道路", "ride" to "骑行道路", "drive" to "驾车道路").forEach { (id, label) ->
                    FilterChip(selected = profile == id, onClick = { vm.setRouteProfile(id) }, label = { Text(label) })
                }
            }
        }
        if (controls.isNotEmpty()) item { SectionLabel("途经点 · ${controls.size}") }
        if (importedTrack) item {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Text("已导入轨迹 ${draft.size} 点，可预览、反向和模拟。", Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        itemsIndexed(controls) { index, point ->
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { editPoint = index }) {
                        Text(when (index) { 0 -> "起点"; controls.lastIndex -> "终点"; else -> "途经点 ${index + 1}" }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(coordinates(point), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { editPoint = index }) { Icon(Icons.Default.EditLocationAlt, "编辑途经点 ${index + 1}", Modifier.size(18.dp)) }
                    IconButton(onClick = { vm.removeRoutePoint(index) }) { Icon(Icons.Default.DeleteOutline, "删除途经点 ${index + 1}", Modifier.size(18.dp)) }
                }
            }
        }
        item {
            SectionLabel("移动方式")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RouteMode.entries.forEach { option -> FilterChip(selected = mode == option, onClick = { mode = option }, label = { Text(routeModeLabel(option)) }) }
            }
            SettingSlider("移动速度", "%.1f km/h".format(settings.routeSpeedMps * 3.6), (settings.routeSpeedMps * 3.6).toFloat(), .5f..108f) { vm.setRouteSpeed(it / 3.6) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("步行" to 1.4, "骑行" to 4.2, "驾车" to 13.9).forEach { (label, speed) ->
                    SuggestionChip(onClick = { vm.setRouteSpeed(speed) }, label = { Text(label) })
                }
            }
            SwitchRow("平滑加减速", "起步与接近终点时逐渐调整速度", settings.sineSpeedCurve) { vm.setSineCurve(it) }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { nameOpen = true }, enabled = draft.size >= 2 && !planning, modifier = Modifier.weight(1f)) { Icon(Icons.Default.BookmarkAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("保存") }
                Button(onClick = { vm.startRouteSim(context, mode); onStart() }, enabled = draft.size >= 2 && canStart && !planning, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("开始路线")
                }
            }
            if (!canStart) Text("请先停止当前模拟，再启动其他路线。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { importer.launch(arrayOf("application/gpx+xml", "text/*", "application/octet-stream", "application/xml")) }) { Icon(Icons.Default.FileUpload, null, Modifier.size(17.dp)); Text("导入 GPX") }
                TextButton(onClick = { exporter.launch("mockgeo-route.gpx") }, enabled = draft.isNotEmpty()) { Icon(Icons.Default.FileDownload, null, Modifier.size(17.dp)); Text("导出 GPX") }
            }
        }
        item { HorizontalDivider(); Spacer(Modifier.height(15.dp)); SectionLabel("已保存路线 · ${routes.size}") }
        if (routes.isEmpty()) item { EmptyState(Icons.Default.Route, "把旅程保存下来", "保存后可反复模拟，也可导出分享") }
        items(routes, key = { it.id }) { route ->
            Surface(shape = RoundedCornerShape(17.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { vm.loadRoute(route); mode = route.mode; onPreview(route.points.map { LatLng(it.lat, it.lng) }) }) {
                            Text(route.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text("${distanceLabel(route.distanceM)} · ${route.points.size} 点 · ${routeModeLabel(route.mode)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
                        }
                        IconButton(onClick = { rename = route }) { Icon(Icons.Default.Edit, "重命名路线", Modifier.size(19.dp)) }
                        IconButton(onClick = { delete = route }) { Icon(Icons.Default.DeleteOutline, "删除路线", Modifier.size(19.dp)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = { vm.loadRoute(route); mode = route.mode; onPreview(route.points.map { LatLng(it.lat, it.lng) }) }) { Text("加载预览") }
                        TextButton(onClick = { vm.startSavedRoute(context, route); onStart() }, enabled = canStart) { Icon(Icons.Default.PlayArrow, null, Modifier.size(17.dp)); Text("开始") }
                    }
                }
            }
        }
    }
    if (nameOpen) NameDialog("保存路线", "路线 ${routes.size + 1}", "路线名称", { nameOpen = false }) { vm.saveRoute(it, mode); nameOpen = false }
    rename?.let { route -> NameDialog("重命名路线", route.name, "路线名称", { rename = null }) { vm.renameRoute(route, it); rename = null } }
    delete?.let { route -> ConfirmDialog("删除路线？", "「${route.name}」及途经点将被删除。", "删除", { delete = null }) { vm.deleteRoute(route.id); delete = null } }
    if (clear) ConfirmDialog("清空当前路线？", "已保存的路线不受影响。", "清空", { clear = false }) { vm.clearRouteDraft(); clear = false }
    editPoint?.let { index -> controls.getOrNull(index)?.let { point ->
        CoordinateDialog(point, onDismiss = { editPoint = null }, title = "编辑途经点 ${index + 1}", onDelete = { vm.removeRoutePoint(index); editPoint = null }) {
            vm.moveRoutePoint(index, it); editPoint = null
        }
    } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ParametersPanel(vm: HomeViewModel) {
    val settings by vm.settings.collectAsState()
    val speedKmh = (settings.routeSpeedMps * 3.6).toFloat()
    val isDefault = kotlin.math.abs(settings.routeSpeedMps - 1.4) < 0.05 &&
        settings.sineSpeedCurve &&
        kotlin.math.abs(settings.gpsAccuracyM - 3.0) < 0.1 &&
        kotlin.math.abs(settings.altitudeM - 0.0) < 0.5 &&
        settings.satellites == 12 &&
        settings.simIntervalMs == 1000L &&
        !settings.networkProviderToo &&
        !settings.jitterEnabled

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "参数会保存为下次默认值；点击右侧蓝色数值可直接输入精确值。",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isDefault) {
                TextButton(onClick = { vm.resetParametersToDefault() }) {
                    Icon(Icons.Default.RestartAlt, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("恢复默认")
                }
            }
        }
        SectionLabel("移动")
        SettingSlider(
            title = "移动速度",
            display = "%.1f km/h".format(speedKmh),
            value = speedKmh,
            range = .5f..108f,
            defaultValue = 5.04f,
            unit = "km/h",
            presets = listOf("步行 5" to 5.04f, "跑步 10" to 10f, "骑行 18" to 18f, "驾车 45" to 45f),
            onValue = { vm.setRouteSpeed(it / 3.6) },
        )
        SwitchRow("平滑加减速", "路线起步和终点缓慢改变速度", settings.sineSpeedCurve) { vm.setSineCurve(it) }
        HorizontalDivider(); SectionLabel("定位与信号")
        SettingSlider(
            title = "水平精度",
            display = "%.0f m".format(settings.gpsAccuracyM),
            value = settings.gpsAccuracyM.toFloat(),
            range = 1f..50f,
            defaultValue = 3f,
            unit = "m",
            presets = listOf("高精度 3m" to 3f, "常规 5m" to 5f, "室内 15m" to 15f),
            onValue = { vm.setAccuracy(it.toDouble()) },
        )
        SettingSlider(
            title = "海拔高度",
            display = "%.0f m".format(settings.altitudeM),
            value = settings.altitudeM.toFloat(),
            range = -500f..9000f,
            defaultValue = 0f,
            unit = "m",
            presets = listOf("0m (平原)" to 0f, "50m" to 50f, "200m" to 200f, "500m" to 500f),
            onValue = { vm.setAltitude(it.toDouble()) },
        )
        SettingSlider(
            title = "卫星数量 (GPS/北斗信号)",
            display = "${settings.satellites} 颗",
            value = settings.satellites.toFloat(),
            range = 0f..64f,
            defaultValue = 12f,
            unit = "颗",
            presets = listOf("0颗 (无信号)" to 0f, "12颗 (常规)" to 12f, "24颗 (开阔)" to 24f, "36颗 (强)" to 36f),
            onValue = { vm.setSatellites(it.toInt()) },
        )
        SettingSlider(
            title = "更新间隔",
            display = "${settings.simIntervalMs} ms",
            value = settings.simIntervalMs.toFloat(),
            range = 200f..2000f,
            defaultValue = 1000f,
            unit = "ms",
            presets = listOf("200ms (高频)" to 200f, "500ms" to 500f, "1000ms (标准)" to 1000f),
            onValue = { vm.setInterval(it.toLong()) },
        )
        SwitchRow("同时模拟网络定位", "除 GPS 外，同时更新网络位置", settings.networkProviderToo) { vm.setNetworkProviderToo(it) }
        HorizontalDivider(); SectionLabel("GPS 浮动 (位置漂移)")
        SwitchRow("小范围随机移动", "在设定半径内产生微幅 GPS 坐标浮动，防止静止死坐标被风控", settings.jitterEnabled) { vm.setJitter(it, settings.jitterMeters.coerceAtLeast(1.0)) }
        if (settings.jitterEnabled) SettingSlider(
            title = "漂移半径",
            display = "%.1f m".format(settings.jitterMeters),
            value = settings.jitterMeters.toFloat(),
            range = .5f..50f,
            defaultValue = 2f,
            unit = "m",
            presets = listOf("微幅 1.5m" to 1.5f, "自然 3m" to 3f, "明显 8m" to 8f),
            onValue = { vm.setJitter(true, it.toDouble()) },
        )
    }
}

@Composable
internal fun ToolsPanel(onPanel: (HomePanel) -> Unit, onCoordinates: () -> Unit, onSettings: () -> Unit, onDiagnostics: () -> Unit, onJoystick: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 24.dp)) {
        ToolRow(Icons.Default.MyLocation, "坐标定位", "输入或粘贴经纬度（支持高德/百度坐标转换）", onCoordinates)
        ToolRow(Icons.Default.Route, "路线模拟", "沿真实道路规划、保存和回放移动轨迹") { onPanel(HomePanel.ROUTE) }
        ToolRow(Icons.Default.Gamepad, "悬浮摇杆", "在其他应用界面上实时控制移动方向与速度", onJoystick)
        ToolRow(Icons.Default.StarBorder, "常用收藏", "保存常去地点，随时一键直达") { onPanel(HomePanel.FAVORITES) }
        ToolRow(Icons.Default.History, "足迹历史", "查看并快速复用最近模拟过的位置") { onPanel(HomePanel.HISTORY) }
        HorizontalDivider(Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
        ToolRow(Icons.Default.Tune, "模拟参数", "调节移动速度、信号强度、海拔与位置浮动") { onPanel(HomePanel.PARAMS) }
        ToolRow(Icons.Default.Settings, "偏好设置", "运行模式、无痕保护、地图密钥与备份", onSettings)
        ToolRow(Icons.Default.VerifiedUser, "运行状态", "查看服务连接状态与权限配置", onDiagnostics)
    }
}

@Composable
internal fun ToolRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title, fontWeight = FontWeight.Medium) }, supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
        leadingContent = { Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) { Icon(icon, null, Modifier.padding(10.dp).size(20.dp), tint = MaterialTheme.colorScheme.primary) } },
        trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }, modifier = Modifier.clickable(onClick = onClick))
}

@Composable
internal fun SectionLabel(text: String) { Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface) }

@Composable
internal fun EmptyState(icon: ImageVector, title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 30.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .6f))
        Text(title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
        Text(subtitle, Modifier.padding(top = 7.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingSlider(
    title: String,
    display: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    defaultValue: Float? = null,
    unit: String = "",
    presets: List<Pair<String, Float>> = emptyList(),
    onValue: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value.coerceIn(range)) }
    var inputOpen by remember { mutableStateOf(false) }
    val showReset = defaultValue != null && kotlin.math.abs(local - defaultValue) > 0.05f
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (showReset && defaultValue != null) {
                TextButton(
                    onClick = { local = defaultValue; onValue(defaultValue) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Icon(Icons.Default.Refresh, "恢复默认", Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("默认", style = MaterialTheme.typography.labelSmall)
                }
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.clickable { inputOpen = true },
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(display, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(3.dp))
                    Icon(Icons.Default.Edit, "输入精确值", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Slider(value = local, onValueChange = { local = it; onValue(it) }, valueRange = range)
        if (presets.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                presets.forEach { (label, target) ->
                    val selected = kotlin.math.abs(local - target) < 0.1f
                    SuggestionChip(
                        onClick = { local = target.coerceIn(range); onValue(local) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        colors = if (selected) SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ) else SuggestionChipDefaults.suggestionChipColors(),
                    )
                }
            }
        }
    }
    if (inputOpen) {
        var text by remember { mutableStateOf(if (local % 1f == 0f) "%.0f".format(local) else "%.1f".format(local)) }
        val parsed = text.trim().toFloatOrNull()
        val valid = parsed != null && parsed.isFinite() && parsed in range
        AlertDialog(
            onDismissRequest = { inputOpen = false },
            title = { Text("输入$title") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(if (unit.isNotEmpty()) "$title ($unit)" else title) },
                    supportingText = { Text("有效范围：${range.start} ～ ${range.endInclusive} $unit") },
                    isError = text.isNotBlank() && !valid,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val v = parsed!!.coerceIn(range)
                        local = v
                        onValue(v)
                        inputOpen = false
                    },
                    enabled = valid,
                ) { Text("确定") }
            },
            dismissButton = {
                if (defaultValue != null) {
                    TextButton(onClick = {
                        local = defaultValue
                        onValue(defaultValue)
                        inputOpen = false
                    }) { Text("恢复默认") }
                }
                TextButton(onClick = { inputOpen = false }) { Text("取消") }
            },
        )
    }
}

@Composable
internal fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        Switch(checked, onChecked)
    }
}

private fun sourceLabel(source: HistorySource): String = when (source) { HistorySource.MAP_TAP -> "地图选点"; HistorySource.SEARCH -> "搜索"; HistorySource.FAVORITE -> "收藏"; HistorySource.GPX -> "GPX"; HistorySource.MANUAL -> "坐标输入"; HistorySource.ROUTE -> "路线" }
private fun historyTime(time: Long): String = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(time))
private fun routeModeLabel(mode: RouteMode): String = when (mode) { RouteMode.ONCE -> "单程"; RouteMode.LOOP -> "循环"; RouteMode.PING_PONG -> "往返" }
private fun durationLabel(seconds: Double): String = if (seconds >= 3600) "%.1f 小时".format(seconds / 3600) else if (seconds >= 60) "%.0f 分钟".format(seconds / 60) else "%.0f 秒".format(seconds)
