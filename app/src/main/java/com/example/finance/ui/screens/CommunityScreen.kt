package com.example.finance.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.finance.ui.components.CommunityPostCard
import com.example.finance.ui.components.HomeHeader
import com.example.finance.ui.components.LibanCard
import com.example.finance.ui.components.PillBadge
import com.example.finance.ui.mock.CommunityMock
import com.example.finance.ui.theme.Radius
import com.example.finance.ui.theme.Spacing
import com.example.finance.ui.theme.libanColors

/**
 * 页面 6：理性成长社区（参考图 6）。
 * 本地交互：精选/关注/最新 Tab 切换（Mock 帖子）。
 */
@Composable
fun CommunityScreen() {
    val mock = CommunityMock()
    val c = libanColors()
    var tabIndex by remember { mutableIntStateOf(0) }
    val posts = mock.postsByTab[mock.tabs[tabIndex]].orEmpty()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        // 头部 + 积分
        item {
            HomeHeader(greeting = "理性成长社区", subtitle = "和同样想理性消费的人一起变清醒")
            Spacer(Modifier.height(Spacing.s))
            Row { PillBadge("🪙 ${mock.points}", container = c.primaryContainer, contentColor = c.primaryDeep) }
        }

        // 21 天挑战卡（深色）
        item {
            LibanCard(backgroundColor = c.darkCard) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        mock.challengeTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = c.surface,
                        modifier = Modifier.weight(1f),
                    )
                    PillBadge(mock.challengeDay, container = c.primary, contentColor = c.onPrimary)
                }
                Spacer(Modifier.height(Spacing.m))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(Radius.s))
                        .background(c.surface.copy(alpha = 0.15f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(14f / 21f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(Radius.s))
                            .background(c.primary),
                    )
                }
                Spacer(Modifier.height(Spacing.s))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        mock.challengeProgress,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.surface.copy(alpha = 0.75f),
                        modifier = Modifier.weight(1f),
                    )
                    PillBadge(mock.challengeReward, container = c.gold, contentColor = c.darkCard)
                }
            }
        }

        // Tab 切换（精选 / 关注 / 最新）
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.m))
                    .background(c.surface)
                    .padding(Spacing.xs),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                mock.tabs.forEachIndexed { index, tabName ->
                    val selected = index == tabIndex
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(Radius.s))
                            .background(if (selected) c.primaryContainer else Color.Transparent)
                            .clickable { tabIndex = index }
                            .padding(horizontal = Spacing.xl, vertical = Spacing.s),
                    ) {
                        Text(
                            tabName,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) c.primaryDeep else c.textSecondary,
                        )
                    }
                }
            }
        }

        // 帖子列表
        items(posts.size) { index ->
            CommunityPostCard(posts[index])
        }

        item { Spacer(Modifier.height(Spacing.s)) }
    }
}
