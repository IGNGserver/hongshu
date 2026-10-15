@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package io.hongshu.app

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

data class SetupStep(
    val key: String,
    val title: String,
    val detail: String,
    val done: Boolean,
    val page: SettingsPage,
)

data class SetupProgress(val steps: List<SetupStep> = emptyList(), val dismissed: Boolean = false) {
    val completed: Int
        get() = steps.count { it.done }

    /** Shown until every step is done; it can be hidden once the hub is connected. */
    val visible: Boolean
        get() = steps.any { !it.done } && (!dismissed || steps.firstOrNull()?.done == false)

    val dismissible: Boolean
        get() = steps.firstOrNull()?.done == true
}

/**
 * First-run checklist. It reuses the settings pages for each step, so the guide never drifts
 * from what the settings actually do. Capture-specific steps only appear once the user has
 * chosen to capture on this phone; a phone that only reads messages finishes after step two.
 */
fun setupProgress(facts: SetupFacts, simsConfigured: Boolean, dismissed: Boolean): SetupProgress =
    SetupProgress(
        buildList {
            add(SetupStep("hub", "连接中枢", "填写中枢地址和密码", facts.connected, SettingsPage.Hub))
            add(
                SetupStep(
                    "capture",
                    "选择这台手机的用途",
                    if (facts.upload) "已开启短信采集" else "需要上传这台手机的短信时开启采集；只查看可跳过",
                    facts.upload,
                    SettingsPage.Capture,
                )
            )
            if (facts.upload) {
                add(
                    SetupStep(
                        "sims",
                        "设置 SIM 卡号码",
                        "用来区分短信由哪张卡接收",
                        simsConfigured,
                        SettingsPage.Sims,
                    )
                )
                add(
                    SetupStep(
                        "realtime",
                        "保持实时连接",
                        "让新短信立即上传，不必等周期同步",
                        facts.keepAlive,
                        SettingsPage.Background,
                    )
                )
            }
        },
        dismissed,
    )

private const val UiPrefs = "hongshu_ui"
private const val SetupDismissed = "setup_dismissed"

internal fun setupDismissed(context: Context): Boolean =
    context.getSharedPreferences(UiPrefs, Context.MODE_PRIVATE).getBoolean(SetupDismissed, false)

internal fun setSetupDismissed(context: Context, dismissed: Boolean) {
    context.getSharedPreferences(UiPrefs, Context.MODE_PRIVATE).edit {
        putBoolean(SetupDismissed, dismissed)
    }
}

@Composable
fun SetupCard(
    progress: SetupProgress,
    onOpen: (SettingsPage) -> Unit,
    onDismiss: () -> Unit,
) {
    val fraction by
        animateFloatAsState(
            if (progress.steps.isEmpty()) 0f else progress.completed.toFloat() / progress.steps.size,
            animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
            label = "setup progress",
        )
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "开始使用鸿枢",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "已完成 ${progress.completed} / ${progress.steps.size} 步",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (progress.dismissible) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "隐藏设置引导")
                    }
                }
            }
            LinearWavyProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp, end = 12.dp),
            )
            val next = progress.steps.firstOrNull { !it.done }
            progress.steps.forEachIndexed { index, step ->
                SetupStepRow(index + 1, step, highlighted = step == next, onClick = { onOpen(step.page) })
            }
        }
    }
}

@Composable
private fun SetupStepRow(number: Int, step: SetupStep, highlighted: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color =
            if (highlighted) MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
            else androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(end = 12.dp, top = 2.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp).alpha(if (step.done) 0.6f else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(28.dp)
                    .clip(CircleShape)
                    .background(
                        if (step.done) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (step.done) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "已完成",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text("$number", style = MaterialTheme.typography.labelLarge)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(step.title, style = MaterialTheme.typography.titleSmall)
                Text(step.detail, style = MaterialTheme.typography.bodySmall)
            }
            if (!step.done) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
}
