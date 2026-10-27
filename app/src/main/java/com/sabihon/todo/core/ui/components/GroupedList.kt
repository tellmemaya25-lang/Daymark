package com.sabihon.todo.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabihon.todo.core.ui.theme.LocalDaymarkSurfaces

/**
 * The grouped-list idiom shared by Profile and Settings.
 *
 * One card holds related rows, the card is separated from the next by a small-caps
 * heading and whitespace rather than by a divider between every row (Android's
 * settings guidance is explicit that dividers cluster *within* a group and whitespace
 * divides the groups). Extracted from ProfileScreen so the two screens cannot drift
 * apart, and so the dark-mode hairline is defined once.
 */

/** Small-caps heading above a [GroupedCard]. */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 1.4.sp
        ),
        color = LocalDaymarkSurfaces.current.eyebrow,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 10.dp)
    )
}

/**
 * Card for related rows.
 *
 * The 1 dp edge is always drawn, not just in dark mode: in dark it separates #1A1C22
 * from a #121214 canvas (a difference the eye does not resolve), and in light it keeps
 * the card from bleeding into the page. It comes from the animated token set, so it
 * fades with everything else instead of snapping at the midpoint.
 */
@Composable
fun GroupedCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val surfaces = LocalDaymarkSurfaces.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(surfaces.groupedCard)
            .border(1.dp, surfaces.cardBorder, RoundedCornerShape(18.dp))
    ) {
        content()
    }
}

/**
 * One row inside a [GroupedCard].
 *
 * Emphasis follows Material's list pattern: the label is the primary line and the
 * current value is secondary, on the trailing edge. A chevron only appears when the
 * row actually goes somewhere - an arrow on a read-only row is what makes people try
 * to edit an email address.
 */
@Composable
fun GroupedRow(
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    value: String? = null,
    leading: (@Composable () -> Unit)? = null,
    /**
     * A control on the trailing edge, e.g. a switch. When present it replaces the
     * chevron: a row that toggles something in place is not a row that goes anywhere,
     * and an arrow beside a switch reads as two competing affordances.
     */
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .heightIn(min = if (leading != null) 64.dp else 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (supporting != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Two lines only when there is a trailing control: the switch row
                    // carries an honest sentence about training data, and one line
                    // would ellipsis it into noise.
                    maxLines = if (trailing != null) 3 else 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp)
            )
        }

        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        } else if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** Inset divider between two rows of the same card. */
@Composable
fun GroupedDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(LocalDaymarkSurfaces.current.cardBorder)
    )
}
