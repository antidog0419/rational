package com.example.finance.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.finance.ui.theme.LibanColors
import com.example.finance.ui.theme.Radius
import com.example.finance.ui.theme.Spacing
import com.example.finance.ui.theme.libanColors

/**
 * 基础卡片容器：圆角 + 可选点击 + 统一内边距。
 * 所有页面卡片都应基于它，保证视觉一致。
 */
@Composable
fun LibanCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = libanColors().surface,
    cornerRadius: Dp = Radius.l,
    contentPadding: Dp = Spacing.l,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = backgroundColor) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = backgroundColor) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/**
 * 带标题 + 右侧动作链接的区块卡片（如"AI 干预记录 / 查看全部"）。
 */
@Composable
fun SectionCard(
    title: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    LibanCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (actionText != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionText, color = libanColors().primaryDeep) }
            }
        }
        Spacer(Modifier.height(Spacing.s))
        content()
    }
}

/** 小标签（如"AI 干预"、"已冷静"） */
@Composable
fun TagChip(
    text: String,
    container: Color = libanColors().primaryContainer,
    contentColor: Color = libanColors().primaryDeep,
) {
    Surface(shape = RoundedCornerShape(Radius.s), color = container) {
        Text(
            text,
            Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 顶部信息横幅语义 */
enum class BannerStyle { SUCCESS, INFO, WARNING, DANGER, DARK }

@Composable
fun InfoBanner(
    text: String,
    style: BannerStyle = BannerStyle.SUCCESS,
    leadingEmoji: String? = null,
    modifier: Modifier = Modifier,
) {
    val c: LibanColors = libanColors()
    val (bg, fg) = when (style) {
        BannerStyle.SUCCESS -> c.successContainer to c.primaryDeep
        BannerStyle.INFO -> c.infoContainer to c.info
        BannerStyle.WARNING -> c.warningContainer to c.warning
        BannerStyle.DANGER -> c.dangerContainer to c.danger
        BannerStyle.DARK -> c.darkCard to c.surface
    }
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.m), color = bg) {
        Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
            leadingEmoji?.let {
                Text(it, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(Spacing.s))
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = fg, fontWeight = FontWeight.Medium)
        }
    }
}

/** label / value 信息行（用于说明页、干预详情） */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    valueColor: Color = libanColors().textPrimary,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Spacing.s),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = libanColors().textSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

/** 场景开关行：图标 + 标题/副标题 + Switch */
@Composable
fun ToggleRow(
    emoji: String,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(libanColors().primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Text(emoji) }
        Spacer(Modifier.size(Spacing.m))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = libanColors().textSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = libanColors().primary),
        )
    }
}

/** 迷你柱状图（近 7 天干预分布等） */
@Composable
fun MiniBars(
    values: List<Float>,
    modifier: Modifier = Modifier,
    highlightIndex: Int = -1,
) {
    val max = (values.maxOrNull() ?: 1f).coerceAtLeast(0.001f)
    Row(
        modifier.fillMaxWidth().height(72.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        values.forEachIndexed { index, v ->
            val color = if (index == highlightIndex) libanColors().primary else libanColors().primaryContainer
            Box(
                Modifier
                    .weight(1f)
                    .height((v / max * 64).dp.coerceAtLeast(6.dp))
                    .clip(RoundedCornerShape(Radius.s))
                    .background(color),
            )
        }
    }
}
