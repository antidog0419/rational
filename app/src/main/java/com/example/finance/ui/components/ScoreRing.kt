package com.example.finance.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.finance.ui.theme.libanColors

/**
 * 理性指数圆环：分数居中，可配置尺寸/进度色。
 * 参考：首页"本月理性指数 78/100"。
 */
@Composable
fun ScoreRing(
    score: Int,
    maxScore: Int = 100,
    modifier: Modifier = Modifier,
    ringSize: Dp = 110.dp,
    stroke: Dp = 12.dp,
    progressColor: Color = libanColors().primary,
    trackColor: Color = libanColors().primaryContainer,
    centerSubLabel: String = "/ $maxScore 分",
) {
    Box(modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(ringSize)) {
            val strokePx = stroke.toPx()
            val inset = strokePx / 2
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            drawArc(
                color = progressColor,
                startAngle = -90f,
                sweepAngle = 360f * (score.toFloat() / maxScore),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$score",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = libanColors().textPrimary,
            )
            Text(
                centerSubLabel,
                style = MaterialTheme.typography.labelSmall,
                color = libanColors().textSecondary,
            )
        }
    }
}
