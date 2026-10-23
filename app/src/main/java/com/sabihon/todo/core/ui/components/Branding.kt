package com.sabihon.todo.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabihon.todo.R
import com.sabihon.todo.core.ui.theme.AccentBlue

/**
 * daymark brand mark – lavender tile with the navy tick + smile.
 */
@Composable
fun DaymarkMark(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp
) {
    Image(
        painter = painterResource(id = R.drawable.ic_daymark_logo),
        contentDescription = "daymark logo",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.24f))
    )
}

/**
 * daymark wordmark – lowercase "daymark" plus the detached blue dot from the brand lockup.
 */
@Composable
fun DaymarkWordmark(
    modifier: Modifier = Modifier,
    fontSize: Int = 28,
    color: Color = MaterialTheme.colorScheme.onBackground,
    dotColor: Color = AccentBlue
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "daymark",
            style = TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = fontSize.sp,
                letterSpacing = (-0.6).sp
            ),
            color = color
        )
        Spacer(Modifier.width((fontSize * 0.36f).dp))
        Canvas(modifier = Modifier.size((fontSize * 0.20f).dp)) {
            drawCircle(color = dotColor)
        }
    }
}

/**
 * Mark + wordmark lockup, used on splash / profile / about surfaces.
 */
@Composable
fun DaymarkLockup(
    modifier: Modifier = Modifier,
    markSize: Dp = 72.dp,
    fontSize: Int = 30,
    color: Color = MaterialTheme.colorScheme.onBackground,
    dotColor: Color = AccentBlue
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        DaymarkMark(size = markSize)
        Spacer(Modifier.width(12.dp))
        DaymarkWordmark(fontSize = fontSize, color = color, dotColor = dotColor)
    }
}
