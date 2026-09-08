package com.example.finance.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.finance.ui.theme.Radius
import com.example.finance.ui.theme.Spacing
import com.example.finance.ui.theme.libanColors

/**
 * 首页式头部：问候语 + 副标题 + 通知铃铛 + 头像。
 */
@Composable
fun HomeHeader(
    greeting: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    avatarEmoji: String = "🧑‍🎓",
    onBellClick: (() -> Unit)? = null,
    onAvatarClick: (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(greeting, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = libanColors().textSecondary)
        }
        Spacer(Modifier.size(Spacing.s))
        ActionCircle(emoji = "🔔", onClick = onBellClick)
        Spacer(Modifier.size(Spacing.s))
        ActionCircle(emoji = avatarEmoji, onClick = onAvatarClick, size = 40)
    }
}

/**
 * 二级页头部：返回箭头 + 标题。
 */
@Composable
fun PageHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = libanColors().textPrimary)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ActionCircle(emoji: String, onClick: (() -> Unit)?, size: Int = 36) {
    if (onClick != null) {
        Surface(onClick = onClick, shape = CircleShape, color = libanColors().surface) {
            EmojiCircle(emoji, size)
        }
    } else {
        Surface(shape = CircleShape, color = libanColors().surface) {
            EmojiCircle(emoji, size)
        }
    }
}

@Composable
private fun EmojiCircle(emoji: String, size: Int) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(libanColors().primaryContainer)
            .padding(Spacing.s),
        contentAlignment = Alignment.Center,
    ) { Text(emoji) }
}

/** 胶囊徽章（社区积分、DAY 标签等） */
@Composable
fun PillBadge(
    text: String,
    container: Color,
    contentColor: Color,
) {
    Surface(shape = RoundedCornerShape(Radius.sheet), color = container) {
        Text(
            text,
            Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
