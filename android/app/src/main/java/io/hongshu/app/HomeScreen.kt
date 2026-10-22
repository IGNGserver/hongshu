@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package io.hongshu.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the main screen shows; produced by [HongshuUI]. */
class HomeModel(
    val repo: Repository,
    val messages: List<CachedMessage>?,
    val searchQuery: String,
    val simFilter: String,
    val receivers: List<String>,
    val sims: List<SimMapping>,
    val facts: SetupFacts,
    val messageLimit: Int,
    val changes: Long,
    val busy: Boolean,
    val refreshing: Boolean,
    val setup: SetupProgress,
    val group: String = "all",
    val aiEnabled: Boolean = false,
    val groupCounts: Map<String, Int> = emptyMap(),
)

/** Callbacks from the main screen back to its owner. */
class HomeActions(
    val onSearchQueryChange: (String) -> Unit,
    val onSearchActiveChange: (Boolean) -> Unit,
    val onSimFilterChange: (String) -> Unit,
    val onSelectConversation: (String) -> Unit,
    val onBack: () -> Unit,
    val onLoadMore: () -> Unit,
    val onOpenSettings: (SettingsPage) -> Unit,
    val onRefresh: () -> Unit,
    val onDismissSetup: () -> Unit,
    val onMessage: (String) -> Unit,
    val onGroupChange: (String) -> Unit = {},
    val onChangeCategory: (Long, String) -> Unit = { _, _ -> },
    val onMarkAllRead: () -> Unit = {},
)

private fun formatClock(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

@Composable
fun MainSurface(
    destination: AppDestination,
    model: HomeModel,
    actions: HomeActions,
    twoPane: Boolean,
    searching: Boolean = destination == AppDestination.Search,
    layoutSpec: AdaptiveLayoutSpec = rememberAdaptiveLayoutSpec(),
) {
    val conversation = destination as? AppDestination.Conversation
    val compactHeight = layoutSpec.compactHeight
    Box(
        Modifier.fillMaxSize()
            .background(
                if (twoPane) MaterialTheme.colorScheme.surfaceContainerLow
                else MaterialTheme.colorScheme.surface
            )
    ) {
        if (twoPane) {
            Row(
                Modifier.fillMaxSize().padding(end = layoutSpec.outerPadding),
                horizontalArrangement = Arrangement.spacedBy(layoutSpec.paneSpacing),
            ) {
                Box(Modifier.width(layoutSpec.listPaneWidth).fillMaxHeight()) {
                    ConversationListPane(model, actions, searching, conversation, compactHeight = compactHeight)
                }
                Surface(
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(top = 8.dp, bottom = 12.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    val enter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
                    val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
                    AnimatedContent(
                        targetState = conversation,
                        transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) },
                        contentKey = { it?.let { c -> c.sender } },
                        label = "detail pane",
                    ) { selected ->
                        if (selected == null) DetailPlaceholder()
                        else
                            ConversationPane(
                                selected,
                                model,
                                actions,
                                showBack = false,
                                inDetailCard = true,
                                maxContentWidth = layoutSpec.conversationMaxWidth,
                                bubbleMaxWidth = layoutSpec.bubbleMaxWidth,
                            )
                    }
                }
            }
        } else if (conversation != null) {
            ConversationPane(
                conversation,
                model,
                actions,
                showBack = true,
                inDetailCard = false,
                maxContentWidth = layoutSpec.conversationMaxWidth,
                bubbleMaxWidth = layoutSpec.bubbleMaxWidth,
            )
        } else {
            ConversationListPane(model, actions, searching, null, compactHeight = compactHeight)
        }
        AnimatedVisibility(
            visible = model.busy && !model.refreshing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
        ) {
            LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DetailPlaceholder() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(88.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.ChatBubbleOutline,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Text(
            "选择一个对话查看短信",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConversationListPane(
    model: HomeModel,
    actions: HomeActions,
    searching: Boolean,
    selected: AppDestination.Conversation?,
    compactHeight: Boolean = false,
) {
    val f = model.facts
    val showSims = model.receivers.size > 1
    Column(Modifier.fillMaxSize()) {
        SearchHeader(
            searching = searching,
            query = model.searchQuery,
            onQueryChange = actions.onSearchQueryChange,
            onSearchActiveChange = actions.onSearchActiveChange,
            attention = f.connected && attentionItems(f).isNotEmpty(),
            onOpenSettings = { actions.onOpenSettings(SettingsPage.Overview) },
            onMarkAllRead = actions.onMarkAllRead,
            compactHeight = compactHeight,
        )
        AnimatedVisibility(showSims) {
            SimFilterRow(model.receivers, model.sims, model.simFilter, actions.onSimFilterChange)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(visibleMessageGroups(model.aiEnabled), key = { it }) { group ->
                FilterChip(
                    selected = model.group == group,
                    onClick = { actions.onGroupChange(group) },
                    label = { Text("${messageGroupLabels[group].orEmpty()} ${model.groupCounts[group] ?: 0}") },
                )
            }
        }
        if (model.aiEnabled) {
            TextButton(
                onClick = { actions.onOpenSettings(SettingsPage.Analysis) },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text("待分析 ${model.groupCounts["pending"] ?: 0} 条 · 查看队列状态")
            }
        }
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = model.refreshing,
            onRefresh = actions.onRefresh,
            state = pullState,
            modifier = Modifier.weight(1f),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pullState,
                    isRefreshing = model.refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
        ) {
            ConversationList(model, actions, searching, selected, showSims)
        }
    }
}

@Composable
private fun SearchHeader(
    searching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
    attention: Boolean,
    onOpenSettings: () -> Unit,
    onMarkAllRead: () -> Unit = {},
    compactHeight: Boolean = false,
) {
    Surface(
        onClick = { if (!searching) onSearchActiveChange(true) },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier =
            Modifier.statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = if (compactHeight) 4.dp else 8.dp)
                .fillMaxWidth()
                .height(if (compactHeight) 48.dp else 56.dp),
    ) {
        val enter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        AnimatedContent(
            targetState = searching,
            transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) },
            label = "search header",
        ) { active ->
            Row(
                Modifier.fillMaxSize().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (active) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    IconButton(onClick = { onSearchActiveChange(false) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出搜索")
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                "搜索短信、号码或联系人",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            textStyle =
                                MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                    AnimatedVisibility(query.isNotEmpty(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "清除")
                        }
                    }
                } else {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "在鸿枢中搜索",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onMarkAllRead) {
                        Icon(Icons.Default.DoneAll, contentDescription = "全部标记为已读")
                    }
                    IconButton(onClick = onOpenSettings) {
                        BadgedBox(badge = { if (attention) Badge() }) {
                            Icon(Icons.Default.Settings, contentDescription = if (attention) "设置，有需要处理的问题" else "设置")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SimFilterRow(
    receivers: List<String>,
    sims: List<SimMapping>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        item(key = "") {
            FilterChip(
                selected = selected.isEmpty(),
                onClick = { onSelect("") },
                label = { Text("全部") },
            )
        }
        items(receivers, key = { it }) { receiver ->
            FilterChip(
                selected = selected == receiver,
                onClick = { onSelect(if (selected == receiver) "" else receiver) },
                label = { Text(simLabel(receiver, sims)) },
                leadingIcon = {
                    Icon(Icons.Default.SimCard, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize))
                },
            )
        }
    }
}

@Composable
private fun ConversationList(
    model: HomeModel,
    actions: HomeActions,
    searching: Boolean,
    selected: AppDestination.Conversation?,
    showSims: Boolean,
) {
    val messages = model.messages
    val f = model.facts
    val hasBanners =
        !searching &&
            (model.setup.visible || (f.connected && !f.syncHealthy) || (f.upload && f.pending.total > 0))
    val navigation = WindowInsets.navigationBars.asPaddingValues()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = navigation.calculateBottomPadding() + 24.dp),
    ) {
        if (!searching && model.setup.visible) {
            item(key = "banner:setup") {
                Box(Modifier.animateItem()) {
                    SetupCard(model.setup, actions.onOpenSettings, actions.onDismissSetup)
                }
            }
        }
        if (!searching && f.connected && !f.syncHealthy) {
            item(key = "banner:sync") {
                NoticeCard(
                    icon = Icons.Default.CloudOff,
                    title = "同步异常",
                    text = f.syncDetail.ifEmpty { "最近一次同步没有成功" },
                    actionLabel = "查看",
                    onAction = { actions.onOpenSettings(SettingsPage.Hub) },
                    attention = true,
                    modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        if (!searching && f.upload && f.pending.total > 0) {
            item(key = "banner:pending") {
                NoticeCard(
                    icon = Icons.Default.Outbox,
                    title = f.pending.banner(),
                    text = "点击查看原因并处理",
                    actionLabel = "查看",
                    onAction = { actions.onOpenSettings(SettingsPage.Pending) },
                    modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        when {
            messages == null ->
                item(key = "loading") {
                    Box(
                        if (hasBanners) Modifier.fillMaxWidth().padding(vertical = 48.dp)
                        else Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator()
                    }
                }
            messages.isEmpty() ->
                item(key = "empty") {
                    Box(
                        (if (hasBanners) Modifier.fillMaxWidth().padding(vertical = 24.dp)
                        else Modifier.fillParentMaxSize()).animateItem(),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(model, actions, searching)
                    }
                }
            else -> {
                items(messages, key = { it.id }) { msg ->
                    ConversationRow(
                        message = msg,
                        receiverLabel = if (showSims) simLabel(msg.receiver, model.sims) else null,
                        isSelected =
                            selected?.sender == msg.sender,
                        onClick = { actions.onSelectConversation(msg.sender) },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (messages.size >= model.messageLimit) {
                    item(key = "more") {
                        TextButton(
                            onClick = actions.onLoadMore,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        ) {
                            Text("加载更早的对话")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(model: HomeModel, actions: HomeActions, searching: Boolean) {
    val f = model.facts
    val (icon, title, text) =
        when {
            searching && model.searchQuery.isNotEmpty() ->
                Triple(Icons.Default.SearchOff, "没有找到相关短信", "换个关键词，或搜索号码、联系人名称")
            searching -> Triple(Icons.Default.Search, "搜索短信", "输入关键词、号码或联系人名称")
            model.simFilter.isNotEmpty() ->
                Triple(Icons.Default.SimCard, "这张卡还没有短信", "清除筛选查看全部短信")
            model.group != "all" ->
                Triple(Icons.Default.ChatBubbleOutline, "这个分组暂无短信", "未完成分析的消息会保留在待分析中，也可以查看全部短信")
            !f.connected ->
                Triple(Icons.Default.Sms, "连接中枢后开始同步", "短信会保存在你自己部署的中枢里，并同步到各台设备")
            else ->
                Triple(
                    Icons.Default.ChatBubbleOutline,
                    "还没有短信",
                    if (f.upload) "这台手机收到的新短信会出现在这里" else "开启短信采集，或等待其他设备上传短信",
                )
        }
    Column(
        Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(96.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        when {
            model.simFilter.isNotEmpty() && !searching ->
                OutlinedButton(onClick = { actions.onSimFilterChange("") }) { Text("清除筛选") }
            !f.connected && !searching ->
                Button(onClick = { actions.onOpenSettings(SettingsPage.Hub) }) { Text("连接中枢") }
            f.connected && !f.upload && !searching ->
                OutlinedButton(onClick = { actions.onOpenSettings(SettingsPage.Capture) }) { Text("短信采集设置") }
        }
    }
}

/** Contact avatar: initial on a coloured circle, or a cookie shape for service short codes. */
@Composable
fun Avatar(contact: String, sender: String, size: Dp, photo: String? = null) {
    val source = LocalContactSource.current
    val revision = LocalContactRevision.current
    var bitmap by remember(source, photo, revision) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(source, photo, revision) {
        if (photo != null) bitmap = withContext(Dispatchers.IO) { source?.photo(photo) }
    }
    val initial = remember(contact, sender) { avatarInitial(contact, sender) }
    val service = contact.isEmpty() && isServiceNumber(sender)
    val shape = if (service) MaterialShapes.Cookie9Sided.toShape() else CircleShape
    Box(
        Modifier.size(size).clip(shape).background(avatarColorFor(sender)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null && source?.available() == true) {
            Image(bitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size), contentScale = ContentScale.Crop)
        } else if (initial != null) {
            Text(
                initial,
                color = Color.White,
                style = if (size > 44.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
        } else {
            Icon(
                if (service) Icons.Default.Sms else Icons.Default.Person,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

@Composable
private fun ConversationRow(
    message: CachedMessage,
    receiverLabel: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val localContact = rememberLocalContact(message.sender)
    val contact = localContact?.name ?: message.contact
    val displayName = contact.ifEmpty { message.sender }
    val otp = messageOtp(message)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Avatar(contact, message.sender, 48.dp, localContact?.photo)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    listOfNotNull(receiverLabel, formatTimestamp(message.timestamp)).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (otp != null) {
                    Text(
                        "验证码",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier =
                            Modifier.clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.tertiaryContainer)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Text(
                    (if (message.local) "未上传 · " else "") + (if (message.outgoing) "我：" else "") + message.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (message.unreadCount > 0) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(if (message.unreadCount > 99) "99+" else message.unreadCount.toString())
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationPane(
    conversation: AppDestination.Conversation,
    model: HomeModel,
    actions: HomeActions,
    showBack: Boolean,
    inDetailCard: Boolean = false,
    maxContentWidth: Dp = 760.dp,
    bubbleMaxWidth: Dp = 320.dp,
) {
    val repo = model.repo
    val sender = conversation.sender
    val context = LocalContext.current
    // Each conversation screen owns its query, so a leaving screen keeps its own messages for the
    // whole exit animation instead of reloading as soon as the destination changes.
    var messageLimit by remember(conversation) { mutableIntStateOf(200) }
    var snapshot by remember(conversation) { mutableStateOf<ConversationSnapshot?>(null) }
    val messages = snapshot?.messages
    LaunchedEffect(conversation, model.changes, messageLimit, model.group) {
        val loaded =
            withContext(Dispatchers.IO) {
                repo.store.readUnlessClosed(null) {
                    conversationSnapshot(sender, messageLimit, model.group)
                }
            }
        if (loaded != null) {
            snapshot = loaded
        }
    }
    // Run only after the loaded messages have been published to this composition. A filtered
    // or paged view must not mark a newer, unseen hub message as read.
    LaunchedEffect(conversation, snapshot) {
        val shown = snapshot ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val changed = repo.store.readUnlessClosed(false) { markDisplayedRead(shown) }
            if (changed) syncNow(context, manual = true)
        }
    }
    val deviceId = repo.config.deviceId
    val loaded = messages.orEmpty()
    val localContact = rememberLocalContact(sender)
    val contactName = localContact?.name ?: loaded.firstOrNull()?.contact.orEmpty()
    val displayName = contactName.ifEmpty { sender }
    val receiverCount = loaded.map { it.receiver }.filter { it.isNotEmpty() }.distinct().size
    val subtitle =
        listOfNotNull(
                sender.takeIf { contactName.isNotEmpty() },
                if (receiverCount > 0) "来自 $receiverCount 个接收号码" else "全部接收号码",
            )
            .joinToString(" · ")
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showJump by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }
    val bottomInset =
        if (inDetailCard) 12.dp
        else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp
    val horizontalPadding = if (inDetailCard) 24.dp else 16.dp

    fun copyCode(code: String) {
        copyOtp(context, code)
        actions.onMessage("验证码已复制")
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets =
                if (inDetailCard) WindowInsets(0, 0, 0, 0) else TopAppBarDefaults.windowInsets,
            colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            navigationIcon = {
                if (showBack) {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            },
            title = {
                Row(
                    modifier = if (!showBack) Modifier.padding(start = 8.dp) else Modifier,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(contactName, sender, 40.dp, localContact?.photo)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (messages == null) {
                LoadingIndicator(Modifier.align(Alignment.Center))
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxHeight().widthIn(max = maxContentWidth).fillMaxWidth(),
                reverseLayout = true,
                contentPadding =
                    PaddingValues(
                        start = horizontalPadding,
                        end = horizontalPadding,
                        top = 12.dp,
                        bottom = bottomInset,
                    ),
                verticalArrangement =
                    Arrangement.spacedBy(
                        8.dp,
                        if (inDetailCard) Alignment.Top else Alignment.Bottom,
                    ),
            ) {
                // Newest first because the layout is reversed; a day header follows the oldest
                // message of each day so it renders above that day's bubbles.
                loaded.forEachIndexed { index, msg ->
                    item(key = msg.id) {
                        MessageBubble(
                            msg = msg,
                            isOutgoing = msg.outgoing,
                            sourceDevice = if (msg.deviceId == deviceId) repo.config.deviceName.ifEmpty { "本机" } else "来源手机",
                            onCopyCode = ::copyCode,
                            aiEnabled = model.aiEnabled,
                            onChangeCategory = { category -> actions.onChangeCategory(msg.id, category) },
                            maxBubbleWidth = bubbleMaxWidth,
                            modifier = Modifier.animateItem(),
                        )
                    }
                    val older = loaded.getOrNull(index + 1)
                    if (older == null || !sameDay(older.timestamp, msg.timestamp)) {
                        item(key = "day:${msg.id}") { DayHeader(dayLabel(msg.timestamp), Modifier.animateItem()) }
                    }
                }
                if (loaded.size >= messageLimit) {
                    item(key = "more") {
                        TextButton(onClick = { messageLimit += 200 }, modifier = Modifier.fillMaxWidth()) {
                            Text("加载更早的短信")
                        }
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = showJump,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
                modifier =
                    Modifier.align(Alignment.BottomEnd)
                        .then(if (inDetailCard) Modifier else Modifier.navigationBarsPadding())
                        .padding(16.dp),
            ) {
                SmallFloatingActionButton(onClick = { scope.launch { listState.animateScrollToItem(0) } }) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "回到最新")
                }
            }
        }
    }
}

@Composable
private fun DayHeader(label: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier.clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun MessageBubble(
    msg: CachedMessage,
    isOutgoing: Boolean,
    sourceDevice: String,
    onCopyCode: (String) -> Unit,
    modifier: Modifier = Modifier,
    aiEnabled: Boolean = false,
    onChangeCategory: (String) -> Unit = {},
    maxBubbleWidth: Dp = 320.dp,
) {
    val otp = messageOtp(msg)
    val shape =
        if (isOutgoing) RoundedCornerShape(24.dp, 24.dp, 6.dp, 24.dp)
        else RoundedCornerShape(24.dp, 24.dp, 24.dp, 6.dp)
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            shape = shape,
            color =
                if (isOutgoing) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor =
                if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = maxBubbleWidth),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (otp != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            otp,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(onClick = { onCopyCode(otp) }) { Text("复制验证码") }
                    }
                    if (msg.analysis.expiresAt > 0)
                        Text(
                            if (msg.analysis.expiresAt <= System.currentTimeMillis()) "按短信标注的有效期，可能已过期"
                            else "短信标注有效至 ${formatClock(msg.analysis.expiresAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                }
                SelectionContainer {
                    Text(
                        msg.body,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        // The user's own messages are not classified.
        if (aiEnabled && otp == null && !isOutgoing) {
            Text("${messageGroupLabels[msg.analysis.category] ?: "待分析"} · ${if (msg.local) "上传后分析" else msg.analysis.label()}", style = MaterialTheme.typography.labelSmall)
            if (!msg.local) {
                var adjusting by remember(msg.id) { mutableStateOf(false) }
                TextButton(onClick = { adjusting = !adjusting }) { Text("调整分类") }
                if (adjusting) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(listOf("personal", "notification", "promotion", "spam", "auto") + if (msg.analysis.state == "failed") listOf("retry") else emptyList()) { category ->
                            TextButton(onClick = { adjusting = false; onChangeCategory(category) }) {
                                Text(messageGroupLabels[category] ?: if (category == "auto") "恢复自动分类" else "重试分析")
                            }
                        }
                    }
                }
            }
        }
        Text(
            buildString {
                append(formatClock(msg.timestamp))
                append(" · ")
                append(sourceDevice)
                if (msg.receiver.isNotEmpty())
                    append(if (isOutgoing) " · 由 ${msg.receiver} 发出" else " · 接收号码 ${msg.receiver}")
                if (isOutgoing) append(" · 已发送")
                if (msg.historical) append(" · 历史补齐")
                if (msg.local) append(" · 未上传到中枢")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}
