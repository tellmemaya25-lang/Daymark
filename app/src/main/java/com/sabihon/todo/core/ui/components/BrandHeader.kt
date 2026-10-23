package com.sabihon.todo.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Shared brand row – daymark mark + wordmark on the left, an optional trailing
 * avatar on the right. Used by Home, Activity and any other top-level screen so the
 * lockup never drifts between pages.
 */
@Composable
fun BrandHeader(
    modifier: Modifier = Modifier,
    markSize: Int = 26,
    fontSize: Int = 19,
    avatarInitials: String? = null,
    avatarUrl: String? = null,
    onAvatarClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DaymarkMark(size = markSize.dp)
        Spacer(Modifier.width(8.dp))
        DaymarkWordmark(fontSize = fontSize, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.weight(1f))

        if (actions != null) {
            actions()
            Spacer(Modifier.width(6.dp))
        }

        when {
            onAvatarClick != null || avatarInitials != null -> ProfileAvatar(
                initials = avatarInitials ?: "DM",
                photoUrl = avatarUrl,
                onClick = onAvatarClick
            )
            trailing != null -> trailing()
        }
    }
}

/**
 * 40.dp circle showing the account photo when available, otherwise initials.
 */
@Composable
fun ProfileAvatar(
    initials: String,
    photoUrl: String?,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                CircleShape
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (!photoUrl.isNullOrBlank()) {
            AsyncImage(
                model = photoUrl,
                contentDescription = "Profile",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
            )
        } else {
            Text(
                text = initials,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
