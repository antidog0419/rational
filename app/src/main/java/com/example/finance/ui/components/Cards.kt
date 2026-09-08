package com.example.finance.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.finance.ui.mock.CommunityPost
import com.example.finance.ui.mock.MembershipPlan
import com.example.finance.ui.theme.Radius
import com.example.finance.ui.theme.Spacing
import com.example.finance.ui.theme.libanColors

/**
 * 指标卡（预算/已消费）：标题 + 大数字 + 副文本 + 可选涨跌与进度条。
 */
@Composable
fun StatCard(
    title: String,
    value: String,
    subText: String,
    modifier: Modifier = Modifier,
    deltaText: String? = null,
    deltaGood: Boolean = true,
    progress: Float? = null,
    progressColor: Color = libanColors().primary,
    onClick: (() -> Unit)? = null,
) {
    LibanCard(modifier = modifier, onClick = onClick, contentPadding = Spacing.l) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = libanColors().textSecondary)
        Spacer(Modifier.height(Spacing.xs))
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subText, style = MaterialTheme.typography.bodySmall, color = libanColors().textSecondary)
        deltaText?.let {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = if (deltaGood) libanColors().primaryDeep else libanColors().danger,
                fontWeight = FontWeight.SemiBold,
            )
        }
        progress?.let {
            Spacer(Modifier.height(Spacing.s))
            LinearProgressIndicator(
                progress = { it.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(Radius.s)),
                trackColor = libanColors().primaryContainer,
                color = progressColor,
            )
        }
    }
}

/**
 * 快捷入口瓷片（首页三宫格：消费呼吸道器 / 认知维度更新 / 消费行为报告）。
 */
@Composable
fun ShortcutTile(
    emoji: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LibanCard(modifier = modifier, onClick = onClick, contentPadding = Spacing.l) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(Radius.m))
                .background(libanColors().primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Text(emoji, style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(Spacing.s))
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 会员方案选择卡（会员订阅页三列）。
 */
@Composable
fun PlanCard(
    plan: MembershipPlan,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = libanColors()
    val borderColor = if (selected) c.primary else c.outline
    Surface(
        onClick = onSelect,
        modifier = modifier,
        shape = RoundedCornerShape(Radius.m),
        color = if (selected) c.primaryContainer else c.surface,
        border = androidx.compose.foundation.BorderStroke(2.dp, borderColor),
    ) {
        Column(Modifier.padding(Spacing.m), horizontalAlignment = Alignment.CenterHorizontally) {
            if (plan.badge != null) {
                TagChip(plan.badge, container = c.primary, contentColor = c.onPrimary)
                Spacer(Modifier.height(Spacing.xs))
            }
            Text(plan.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(Spacing.xs))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(plan.price, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = if (selected) c.primaryDeep else c.textPrimary)
                plan.period?.let {
                    Spacer(Modifier.size(2.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                }
            }
            Spacer(Modifier.height(Spacing.s))
            plan.features.forEach { feature ->
                Text(
                    "· $feature",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 社区帖子卡片：头像 + 作者 + 标题/正文 + 标签 + 点赞评论分享。
 */
@Composable
fun CommunityPostCard(
    post: CommunityPost,
    modifier: Modifier = Modifier,
    onLike: (() -> Unit)? = null,
) {
    val c = libanColors()
    LibanCard(modifier = modifier, onClick = onLike, contentPadding = Spacing.l) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(c.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Text(post.avatarEmoji) }
            Spacer(Modifier.size(Spacing.s))
            Column(Modifier.weight(1f)) {
                Text(post.author, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(post.timeAgo, style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
            }
        }
        Spacer(Modifier.height(Spacing.s))
        Text(post.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Spacing.xs))
        Text(post.content, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(Spacing.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            post.tags.forEach { TagChip(it) }
        }
        Spacer(Modifier.height(Spacing.s))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
            Text("❤ ${post.likes}", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
            Text("💬 ${post.comments}", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
            Text("↗ ${post.shares}", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
        }
    }
}
