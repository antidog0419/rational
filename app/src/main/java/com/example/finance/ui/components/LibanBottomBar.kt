package com.example.finance.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.finance.ui.theme.Radius
import com.example.finance.ui.theme.Spacing
import com.example.finance.ui.theme.libanColors

/** 底栏条目：label + 图标；isCenter = 中央"+"主操作 */
data class BottomBarItem(val label: String, val icon: ImageVector, val isCenter: Boolean = false)

/**
 * 底部导航栏：普通项 + 中央圆形"+"按钮。
 * 结构参考图中的 首页 / 社区 / (+) / 记录 / 我的。
 */
@Composable
fun LibanBottomBar(
    items: List<BottomBarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = libanColors()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = Radius.l, topEnd = Radius.l),
        color = c.surface,
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.s, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                if (item.isCenter) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Surface(
                            onClick = { onSelect(index) },
                            shape = CircleShape,
                            color = c.primary,
                            shadowElevation = 4.dp,
                        ) {
                            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Add, contentDescription = item.label, tint = c.onPrimary)
                            }
                        }
                    }
                } else {
                    val selected = index == selectedIndex
                    NavItem(
                        item = item,
                        selected = selected,
                        onClick = { onSelect(index) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun NavItem(item: BottomBarItem, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = libanColors()
    val tint: Color = if (selected) c.primary else c.textTertiary
    Surface(onClick = onClick, color = Color.Transparent, modifier = modifier) {
        Column(
            Modifier.padding(vertical = Spacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(if (selected) c.primaryContainer else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(22.dp))
            }
            Text(
                item.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = tint,
            )
        }
    }
}
