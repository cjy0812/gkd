package li.songe.gkd.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.ImagePreviewPageDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import li.songe.gkd.MainActivity
import li.songe.gkd.data.Snapshot
import li.songe.gkd.db.DbSet
import li.songe.gkd.permission.canWriteExternalStorage
import li.songe.gkd.permission.requiredPermission
import li.songe.gkd.ui.component.EmptyText
import li.songe.gkd.ui.component.FixedTimeText
import li.songe.gkd.ui.component.LocalNumberCharWidth
import li.songe.gkd.ui.component.PerfIcon
import li.songe.gkd.ui.component.PerfIconButton
import li.songe.gkd.ui.component.PerfTopAppBar
import li.songe.gkd.ui.component.animateListItem
import li.songe.gkd.ui.component.measureNumberTextWidth
import li.songe.gkd.ui.component.waitResult
import li.songe.gkd.ui.share.ListPlaceholder
import li.songe.gkd.ui.share.LocalMainViewModel
import li.songe.gkd.ui.share.noRippleClickable
import li.songe.gkd.ui.style.EmptyHeight
import li.songe.gkd.ui.style.ProfileTransitions
import li.songe.gkd.ui.style.itemHorizontalPadding
import li.songe.gkd.ui.style.itemVerticalPadding
import li.songe.gkd.ui.style.scaffoldPadding
import li.songe.gkd.util.IMPORT_SHORT_URL
import li.songe.gkd.util.ImageUtils
import li.songe.gkd.util.SnapshotExt
import li.songe.gkd.util.UriUtils
import li.songe.gkd.util.appInfoMapFlow
import li.songe.gkd.util.copyText
import li.songe.gkd.util.launchAsFn
import li.songe.gkd.util.saveFileToDownloads
import li.songe.gkd.util.shareFile
import li.songe.gkd.util.throttle
import li.songe.gkd.util.toast
import li.songe.gkd.util.format

@Destination<RootGraph>(style = ProfileTransitions::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapshotPage() {
    val context = LocalActivity.current as MainActivity
    val mainVm = LocalMainViewModel.current
    val colorScheme = MaterialTheme.colorScheme
    val vm = viewModel<SnapshotVm>()

    val firstLoading by vm.firstLoadingFlow.collectAsState()
    val snapshots by vm.snapshotsState.collectAsState()
    val appInfoMap by appInfoMapFlow.collectAsState()
    var selectedSnapshot by remember { mutableStateOf<Snapshot?>(null) }
    var groupMode by rememberSaveable { mutableStateOf(SnapshotGroupMode.App) }
    var filterText by rememberSaveable { mutableStateOf("") }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val gridState = rememberLazyGridState()
    val scrollBehavior = androidx.compose.material3.TopAppBarDefaults.enterAlwaysScrollBehavior()
    val timeTextWidth = measureNumberTextWidth(MaterialTheme.typography.bodySmall)
    val filteredSnapshots = remember(snapshots, filterText) {
        if (filterText.isBlank()) {
            snapshots
        } else {
            snapshots.filter { snapshot ->
                snapshot.appId.contains(filterText, ignoreCase = true) ||
                    snapshot.activityId?.contains(filterText, ignoreCase = true) == true
            }
        }
    }
    val groupedSnapshots = remember(filteredSnapshots, groupMode, appInfoMap) {
        when (groupMode) {
            SnapshotGroupMode.App -> {
                filteredSnapshots.groupBy { it.appId }.map { (appId, group) ->
                    SnapshotGroup(
                        key = appId,
                        title = appInfoMap[appId]?.name ?: appId,
                        count = group.size,
                        snapshots = group,
                    )
                }.sortedBy { it.title }
            }

            SnapshotGroupMode.Activity -> {
                filteredSnapshots.groupBy { it.activityId ?: "null" }.map { (activityId, group) ->
                    SnapshotGroup(
                        key = activityId,
                        title = activityId,
                        count = group.size,
                        snapshots = group,
                    )
                }.sortedBy { it.title }
            }

            SnapshotGroupMode.Date -> {
                filteredSnapshots.groupBy { it.id.format("yyyy-MM-dd") }.map { (date, group) ->
                    SnapshotGroup(
                        key = date,
                        title = date,
                        count = group.size,
                        snapshots = group,
                    )
                }.sortedByDescending { it.title }
            }
        }
    }
    val isSelectionMode = selectionMode || selectedIds.isNotEmpty()
    val selectionCount = selectedIds.size

    Scaffold(modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection), topBar = {
        PerfTopAppBar(
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                PerfIconButton(imageVector = PerfIcon.ArrowBack, onClick = {
                    mainVm.popBackStack()
                })
            },
            title = {
                val scrollToTop = vm.viewModelScope.launchAsFn {
                    gridState.animateScrollToItem(0)
                }
                Text(
                    text = "快照记录",
                    modifier = Modifier.noRippleClickable(onClick = throttle(scrollToTop)),
                )
            },
            actions = {
                if (isSelectionMode) {
                    PerfIconButton(
                        imageVector = PerfIcon.Close,
                        onClick = {
                            selectedIds = emptySet()
                            selectionMode = false
                        }
                    )
                    val selectAllLabel = if (selectionCount == filteredSnapshots.size) "取消全选" else "全选"
                    PerfIconButton(
                        imageVector = PerfIcon.Check,
                        onClick = {
                            selectedIds = if (selectionCount == filteredSnapshots.size) {
                                emptySet()
                            } else {
                                filteredSnapshots.map { it.id }.toSet()
                            }
                        },
                        onClickLabel = selectAllLabel,
                    )
                    PerfIconButton(
                        imageVector = PerfIcon.Delete,
                        enabled = selectionCount > 0,
                        onClick = throttle(fn = vm.viewModelScope.launchAsFn(Dispatchers.IO) {
                            val selectedSnapshots = snapshots.filter { it.id in selectedIds }
                            if (selectedSnapshots.isEmpty()) return@launchAsFn
                            mainVm.dialogFlow.waitResult(
                                title = "删除快照",
                                text = "确定删除选中的 ${selectedSnapshots.size} 条快照吗?",
                                error = true,
                            )
                            DbSet.snapshotDao.delete(*selectedSnapshots.toTypedArray())
                            withContext(Dispatchers.IO) {
                                selectedSnapshots.forEach { snapshot ->
                                    SnapshotExt.removeSnapshot(snapshot.id)
                                }
                            }
                            selectedIds = emptySet()
                            selectionMode = false
                            toast("删除成功")
                        })
                    )
                } else if (snapshots.isNotEmpty()) {
                    PerfIconButton(
                        imageVector = PerfIcon.Check,
                        onClick = { selectionMode = true },
                        onClickLabel = "多选管理",
                    )
                    PerfIconButton(
                        imageVector = PerfIcon.Delete,
                        onClick = throttle(fn = vm.viewModelScope.launchAsFn(Dispatchers.IO) {
                            mainVm.dialogFlow.waitResult(
                                title = "删除快照",
                                text = "确定删除所有快照记录?",
                                error = true,
                            )
                            snapshots.forEach { s ->
                                SnapshotExt.removeSnapshot(s.id)
                            }
                            DbSet.snapshotDao.deleteAll()
                            toast("删除成功")
                        })
                    )
                }
            })
    }, content = { contentPadding ->
        CompositionLocalProvider(
            LocalNumberCharWidth provides timeTextWidth
        ) {
            LazyVerticalGrid(
                modifier = Modifier.scaffoldPadding(contentPadding),
                state = gridState,
                columns = GridCells.Adaptive(minSize = 140.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        FilterPanel(
                            filterText = filterText,
                            onFilterTextChange = { filterText = it },
                            groupMode = groupMode,
                            onGroupModeChange = { groupMode = it },
                            selectedCount = selectionCount,
                            totalCount = filteredSnapshots.size,
                        )
                    }
                }
                groupedSnapshots.forEach { group ->
                    item(
                        key = "group-${group.key}",
                        span = { GridItemSpan(maxLineSpan) }
                    ) {
                        SnapshotGroupHeader(
                            title = group.title,
                            count = group.count,
                        )
                    }
                    items(group.snapshots, key = { it.id }) { snapshot ->
                        SnapshotGridCard(
                            modifier = Modifier.animateListItem(),
                            snapshot = snapshot,
                            selected = snapshot.id in selectedIds,
                            selectionMode = isSelectionMode,
                            onToggleSelect = {
                                selectedIds = if (snapshot.id in selectedIds) {
                                    selectedIds - snapshot.id
                                } else {
                                    selectedIds + snapshot.id
                                }
                                selectionMode = true
                            },
                            onPreview = {
                                selectedSnapshot = snapshot
                            },
                        )
                    }
                }
                item(ListPlaceholder.KEY, ListPlaceholder.TYPE, span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(modifier = Modifier.height(EmptyHeight))
                    if (snapshots.isEmpty() && !firstLoading) {
                        EmptyText(text = "暂无数据")
                    } else if (filteredSnapshots.isEmpty() && !firstLoading) {
                        EmptyText(text = "暂无匹配快照")
                    }
                }
            }
        }
    })

    selectedSnapshot?.let { snapshotVal ->
        Dialog(onDismissRequest = { selectedSnapshot = null }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                val modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                Text(
                    text = "查看", modifier = Modifier
                        .clickable(onClick = throttle(fn = vm.viewModelScope.launchAsFn {
                            selectedSnapshot = null
                            mainVm.navigatePage(
                                ImagePreviewPageDestination(
                                    title = appInfoMapFlow.value[snapshotVal.appId]?.name
                                        ?: snapshotVal.appId,
                                    uri = snapshotVal.screenshotFile.absolutePath,
                                )
                            )
                        }))
                        .then(modifier)
                )
                HorizontalDivider()
                Text(
                    text = "分享到其他应用",
                    modifier = Modifier
                        .clickable(onClick = throttle(fn = vm.viewModelScope.launchAsFn {
                            selectedSnapshot = null
                            val zipFile = SnapshotExt.snapshotZipFile(
                                snapshotVal.id,
                                snapshotVal.appId,
                                snapshotVal.activityId
                            )
                            context.shareFile(zipFile, "分享快照文件")
                        }))
                        .then(modifier)
                )
                HorizontalDivider()
                Text(
                    text = "保存到下载",
                    modifier = Modifier
                        .clickable(onClick = throttle(fn = vm.viewModelScope.launchAsFn(Dispatchers.IO) {
                            selectedSnapshot = null
                            toast("正在保存...")
                            val zipFile = SnapshotExt.snapshotZipFile(
                                snapshotVal.id,
                                snapshotVal.appId,
                                snapshotVal.activityId
                            )
                            context.saveFileToDownloads(zipFile)
                        }))
                        .then(modifier)
                )
                HorizontalDivider()
                if (snapshotVal.githubAssetId != null) {
                    Text(
                        text = "复制链接", modifier = Modifier
                            .clickable(onClick = throttle {
                                selectedSnapshot = null
                                copyText(IMPORT_SHORT_URL + snapshotVal.githubAssetId)
                            })
                            .then(modifier)
                    )
                } else {
                    Text(
                        text = "生成链接(需科学上网)", modifier = Modifier
                            .clickable(onClick = throttle {
                                selectedSnapshot = null
                                mainVm.uploadOptions.startTask(
                                    getFile = { SnapshotExt.snapshotZipFile(snapshotVal.id) },
                                    showHref = { IMPORT_SHORT_URL + it.id },
                                    onSuccessResult = {
                                        DbSet.snapshotDao.update(snapshotVal.copy(githubAssetId = it.id))
                                    }
                                )
                            })
                            .then(modifier)
                    )
                }
                HorizontalDivider()

                Text(
                    text = "保存截图到相册",
                    modifier = Modifier
                        .clickable(onClick = throttle(fn = vm.viewModelScope.launchAsFn(Dispatchers.IO) {
                            toast("正在保存...")
                            selectedSnapshot = null
                            requiredPermission(context, canWriteExternalStorage)
                            ImageUtils.save2Album(BitmapFactory.decodeFile(snapshotVal.screenshotFile.absolutePath))
                            toast("保存成功")
                        }))
                        .then(modifier)
                )
                HorizontalDivider()
                Text(
                    text = "替换截图(去除隐私)",
                    modifier = Modifier
                        .clickable(onClick = throttle(fn = vm.viewModelScope.launchAsFn(Dispatchers.IO) {
                            val uri = context.pickContentLauncher.launchForImageResult()
                            val oldBitmap =
                                BitmapFactory.decodeFile(snapshotVal.screenshotFile.absolutePath)
                            val newBytes = UriUtils.uri2Bytes(uri)
                            val newBitmap =
                                BitmapFactory.decodeByteArray(newBytes, 0, newBytes.size)
                            if (oldBitmap.width == newBitmap.width && oldBitmap.height == newBitmap.height) {
                                snapshotVal.screenshotFile.writeBytes(newBytes)
                                if (snapshotVal.githubAssetId != null) {
                                    // 当本地快照变更时, 移除快照链接
                                    DbSet.snapshotDao.deleteGithubAssetId(snapshotVal.id)
                                }
                                toast("替换成功")
                                selectedSnapshot = null
                            } else {
                                toast("截图尺寸不一致, 无法替换")
                            }
                        }))
                        .then(modifier)
                )
                HorizontalDivider()
                Text(
                    text = "删除", modifier = Modifier
                        .clickable(onClick = throttle(fn = vm.viewModelScope.launchAsFn {
                            selectedSnapshot = null
                            mainVm.dialogFlow.waitResult(
                                title = "删除快照",
                                text = "确定删除当前快照吗?",
                                error = true,
                            )
                            DbSet.snapshotDao.delete(snapshotVal)
                            withContext(Dispatchers.IO) {
                                SnapshotExt.removeSnapshot(snapshotVal.id)
                            }
                            toast("删除成功")
                        }))
                        .then(modifier), color = colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun SnapshotGridCard(
    modifier: Modifier = Modifier,
    snapshot: Snapshot,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelect: () -> Unit,
    onPreview: () -> Unit,
) {
    Card(
        modifier = modifier
            .padding(horizontal = itemHorizontalPadding / 2, vertical = itemVerticalPadding / 2)
            .combinedClickable(
                onClick = {
                    if (selectionMode) {
                        onToggleSelect()
                    } else {
                        onPreview()
                    }
                },
                onLongClick = onToggleSelect,
            ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SnapshotThumbnail(
                    snapshot = snapshot,
                    dimmed = selectionMode && !selected,
                )
                if (selectionMode) {
                    SelectionBadge(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                        selected = selected,
                    )
                }
            }
            SnapshotMeta(
                snapshot = snapshot,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

@Composable
private fun SnapshotThumbnail(
    snapshot: Snapshot,
    dimmed: Boolean,
) {
    val context = LocalActivity.current
    val model = remember(snapshot.id) {
        ImageRequest.Builder(context)
            .data(snapshot.screenshotFile.absolutePath)
            .diskCachePolicy(CachePolicy.DISABLED)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
    }
    val painter = rememberAsyncImagePainter(model)
    val state by painter.state.collectAsState()
    val placeholderAlpha = if (dimmed) 0.6f else 1f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        when (state) {
            is AsyncImagePainter.State.Success -> {
                androidx.compose.foundation.Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .alpha(placeholderAlpha),
                    contentScale = ContentScale.Crop,
                )
            }

            is AsyncImagePainter.State.Loading -> {
                Text(
                    text = "加载中",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            is AsyncImagePainter.State.Error -> {
                Text(
                    text = "预览失败",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            else -> Unit
        }
    }
}

@Composable
private fun SnapshotMeta(
    snapshot: Snapshot,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        val appInfo = appInfoMapFlow.collectAsState().value[snapshot.appId]
        val showAppName = appInfo?.name ?: snapshot.appId
        Text(
            text = showAppName,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            softWrap = false,
        )
        FixedTimeText(
            text = snapshot.date,
            style = MaterialTheme.typography.bodySmall,
        )
        val showActivityId = snapshot.activityId?.let {
            if (it.startsWith(snapshot.appId)) {
                it.substring(snapshot.appId.length)
            } else {
                it
            }
        }
        if (showActivityId != null) {
            Text(
                modifier = Modifier.height(MaterialTheme.typography.bodyMedium.lineHeight.value.dp),
                text = showActivityId,
                style = MaterialTheme.typography.bodySmall,
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        } else {
            Text(
                text = "null",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.typography.bodySmall.color.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun SelectionBadge(
    modifier: Modifier = Modifier,
    selected: Boolean,
) {
    Box(
        modifier = modifier
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = if (selected) "已选" else "选择",
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun FilterPanel(
    filterText: String,
    onFilterTextChange: (String) -> Unit,
    groupMode: SnapshotGroupMode,
    onGroupModeChange: (SnapshotGroupMode) -> Unit,
    selectedCount: Int,
    totalCount: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = itemHorizontalPadding, vertical = itemVerticalPadding / 2),
    ) {
        TextField(
            value = filterText,
            onValueChange = onFilterTextChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(text = "过滤 appId / activityId") },
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SnapshotGroupMode.values().forEach { mode ->
                    val selected = mode == groupMode
                    Text(
                        text = mode.label,
                        modifier = Modifier
                            .background(
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = RoundedCornerShape(8.dp),
                            )
                            .clickable { onGroupModeChange(mode) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (selectedCount > 0) {
                Text(
                    text = "已选 $selectedCount / $totalCount",
                    style = MaterialTheme.typography.labelMedium,
                )
            } else {
                Text(
                    text = "共 $totalCount",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun SnapshotGroupHeader(
    title: String,
    count: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = itemHorizontalPadding, vertical = itemVerticalPadding / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "($count)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class SnapshotGroup(
    val key: String,
    val title: String,
    val count: Int,
    val snapshots: List<Snapshot>,
)

private enum class SnapshotGroupMode(val label: String) {
    App("按应用"),
    Activity("按界面"),
    Date("按日期"),
}
