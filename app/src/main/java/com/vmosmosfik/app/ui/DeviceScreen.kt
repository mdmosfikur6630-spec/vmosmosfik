package com.vmosmosfik.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vmosmosfik.app.DeviceState

@Composable
fun DevicePanel(
    deviceState: DeviceState,
    index: Int,
    isMaster: Boolean,
    isSyncEnabled: Boolean,
    onTap: (x: Float, y: Float, width: Float, height: Float) -> Unit,
    onToggleSelect: () -> Unit,
    onSetMaster: () -> Unit,
    onDisconnect: () -> Unit,
    onRemove: () -> Unit,
    onKeyEvent: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val config = deviceState.config
    val isConnected = config.isConnected
    val isSelected = config.isSelected
    val isConnecting = deviceState.isConnecting
    val error = deviceState.error

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isMaster && isSyncEnabled)
                MaterialTheme.colorScheme.primaryContainer
            else if (isConnected)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Header bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Connection indicator
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isConnecting -> Color(0xFFFFA000)
                                    isConnected -> Color(0xFF4CAF50)
                                    else -> Color(0xFFBDBDBD)
                                }
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = config.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Master badge
                    if (isMaster && isSyncEnabled) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Master device",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Selection checkbox
                    if (isConnected && isSyncEnabled) {
                        Checkbox(
                            checked = isSelected || isMaster,
                            onCheckedChange = { onToggleSelect() },
                            enabled = !isMaster,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Connection button
                    if (isConnected) {
                        IconButton(
                            onClick = onDisconnect,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.PowerSettingsNew,
                                contentDescription = "Disconnect",
                                tint = Color(0xFFE53935),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Screen display area
            val screenshot = deviceState.screenshot
            if (screenshot != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(8.dp)
                        )
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val containerWidth = size.width.toFloat()
                                val containerHeight = size.height.toFloat()
                                onTap(offset.x, offset.y, containerWidth, containerHeight)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val imageBitmap = screenshot.asImageBitmap()
                        val scaleX = size.width / imageBitmap.width
                        val scaleY = size.height / imageBitmap.height
                        val scale = minOf(scaleX, scaleY)
                        val offsetX = (size.width - imageBitmap.width * scale) / 2f
                        val offsetY = (size.height - imageBitmap.height * scale) / 2f

                        scale(scale, scale, android.graphics.Point(imageBitmap.width / 2, imageBitmap.height / 2)) {
                            drawImage(
                                image = imageBitmap,
                                topLeft = androidx.compose.ui.geometry.Offset(
                                    offsetX / scale,
                                    offsetY / scale
                                )
                            )
                        }
                    }
                }
            } else if (isConnecting) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Connecting...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else if (error != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }

            // Control buttons
            if (isConnected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // HOME button
                    FilledTonalIconButton(
                        onClick = { onKeyEvent(3) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Home, "HOME", modifier = Modifier.size(18.dp))
                    }

                    // BACK button
                    FilledTonalIconButton(
                        onClick = { onKeyEvent(4) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, "BACK", modifier = Modifier.size(18.dp))
                    }

                    // RECENT APPS button
                    FilledTonalIconButton(
                        onClick = { onKeyEvent(187) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Apps, "RECENT", modifier = Modifier.size(18.dp))
                    }

                    // Volume UP
                    FilledTonalIconButton(
                        onClick = { onKeyEvent(24) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.VolumeUp, "VOL_UP", modifier = Modifier.size(18.dp))
                    }

                    // Volume DOWN
                    FilledTonalIconButton(
                        onClick = { onKeyEvent(25) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.VolumeDown, "VOL_DOWN", modifier = Modifier.size(18.dp))
                    }

                    // POWER
                    FilledTonalIconButton(
                        onClick = { onKeyEvent(26) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.PowerSettingsNew, "POWER", modifier = Modifier.size(18.dp))
                    }

                    // Set as master (if sync enabled)
                    if (isSyncEnabled && !isMaster) {
                        FilledTonalIconButton(
                            onClick = onSetMaster,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Star, "MASTER", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // Footer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${config.screenWidth}x${config.screenHeight}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isConnected) {
                    Text(
                        text = "● Connected",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF4CAF50)
                    )
                }
            }
        }
    }
}
