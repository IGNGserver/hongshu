@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package io.hongshu.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** How a setting or subsystem is doing, shown as a coloured dot plus a short label. */
enum class StatusLevel {
    Ok,
    Attention,
    Off,
}

@Composable
fun StatusPill(level: StatusLevel, label: String, modifier: Modifier = Modifier) {
    val dot =
        when (level) {
            StatusLevel.Ok -> MaterialTheme.colorScheme.primary
            StatusLevel.Attention -> MaterialTheme.colorScheme.error
            StatusLevel.Off -> MaterialTheme.colorScheme.outline
        }
    Row(
        modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Icon in a tonal circle, the leading element of settings rows. */
@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Box(
        modifier.size(40.dp).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
    }
}

/**
 * Full-screen settings page: a large collapsing title with back navigation, a wavy progress bar
 * while an action runs, and a lazily built body.
 */
@Composable
fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    busy: Boolean,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        val navigation = WindowInsets.navigationBars.asPaddingValues()
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        start = padding.calculateStartPadding(direction) + 16.dp,
                        end = padding.calculateEndPadding(direction) + 16.dp,
                        top = padding.calculateTopPadding() + 4.dp,
                        bottom = navigation.calculateBottomPadding() + 32.dp,
                    ),
                content = content,
            )
            AnimatedVisibility(
                visible = busy,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.padding(top = padding.calculateTopPadding()),
            ) {
                LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

/** A row in a settings group; [key] keeps item animations stable as rows come and go. */
class SettingsRow(val key: String, val content: @Composable () -> Unit)

private val GroupOuterCorner = 24.dp
private val GroupInnerCorner = 4.dp

/** M3 Expressive segmented-list shape: rounded group ends, tight corners between rows. */
internal fun segmentShape(index: Int, count: Int): Shape =
    RoundedCornerShape(
        topStart = if (index == 0) GroupOuterCorner else GroupInnerCorner,
        topEnd = if (index == 0) GroupOuterCorner else GroupInnerCorner,
        bottomStart = if (index == count - 1) GroupOuterCorner else GroupInnerCorner,
        bottomEnd = if (index == count - 1) GroupOuterCorner else GroupInnerCorner,
    )

/**
 * Emits a titled group of rows as individual lazy items, so rows that appear or disappear
 * (for example a permission prompt) animate instead of popping.
 */
fun LazyListScope.settingsGroup(key: String, title: String?, rows: List<SettingsRow>) {
    if (rows.isEmpty()) return
    if (title != null) {
        item(key = "$key:title") {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.animateItem().padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
            )
        }
    } else {
        item(key = "$key:spacer") { Spacer(Modifier.animateItem().size(12.dp)) }
    }
    rows.forEachIndexed { index, row ->
        item(key = "$key:${row.key}") {
            Surface(
                shape = segmentShape(index, rows.size),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.animateItem().fillMaxWidth().padding(bottom = 2.dp),
            ) {
                row.content()
            }
        }
    }
}

/** Free-standing item (cards, notes) that still animates with the list. */
fun LazyListScope.settingsItem(key: String, content: @Composable LazyItemScope.() -> Unit) {
    item(key = key) { Box(Modifier.animateItem()) { content() } }
}

@Composable
fun SettingsItem(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    status: Pair<StatusLevel, String>? = null,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent =
            summary?.let { { Text(it, modifier = Modifier.animateContentSize()) } },
        leadingContent = icon?.let { { IconBadge(it) } },
        trailingContent =
            if (status != null || showChevron) {
                {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        status?.let { (level, label) -> StatusPill(level, label) }
                        if (showChevron) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier =
            Modifier.alpha(if (enabled) 1f else 0.38f)
                .then(
                    if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick)
                    else Modifier
                ),
    )
}

/** A switch row where the whole row toggles, as M3 requires for list-item switches. */
@Composable
fun SettingsSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent =
            summary?.let { { Text(it, modifier = Modifier.animateContentSize()) } },
        leadingContent = icon?.let { { IconBadge(it) } },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                thumbContent =
                    if (checked) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize),
                            )
                        }
                    } else null,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier =
            Modifier.alpha(if (enabled) 1f else 0.38f)
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ),
    )
}

/** Collapsed explanation; the long background text stays available without crowding a page. */
@Composable
fun ExpandableHelp(text: String, modifier: Modifier = Modifier, title: String = "了解更多") {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rotation by
        animateFloatAsState(
            if (expanded) 180f else 0f,
            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            label = "help chevron",
        )
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(title)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ExpandMore, contentDescription = null, Modifier.rotate(rotation))
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(),
            exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/** Tonal banner that points at something needing the user's action. */
@Composable
fun NoticeCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    text: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    attention: Boolean = false,
) {
    val container =
        if (attention) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.secondaryContainer
    val content =
        if (attention) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = content,
        onClick = onAction ?: {},
        enabled = onAction != null,
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                text?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel, color = content) }
            }
        }
    }
}
