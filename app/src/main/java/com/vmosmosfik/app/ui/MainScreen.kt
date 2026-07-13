package com.vmosmosfik.app.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vmosmosfik.app.DeviceState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    devices: List<DeviceState>,
    masterDeviceId: String?,
    syncEnabled: Boolean,
    onAddDevice: () -> Unit,
    onDeviceTap: (index: Int, x: Float, y: Float, w: Float, h: Float) -> Unit,
    onToggleSelect: (index: Int) -> Unit,
    onSetMaster: (index: Int) -> Unit,
    onDisconnect: (index: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    onKeyEvent: (index: Int, keyCode: Int) -> Unit,
    onToggleSync: () -> Unit,
    showAddDialog: Boolean,
    onDismissDialog: () -> Unit,
    onAddDeviceConfirm: (name: String, host: String, port: Int, username: String, sshKey: String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "VmosMosfik",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    if (devices.any { it.config.isConnected }) {
                        // Sync toggle
                        IconButton(onClick = onToggleSync) {
                            Icon(
                                if (syncEnabled) Icons.Default.Sync else Icons.Default.SyncDisabled,
                                contentDescription = "Toggle sync",
                                tint = if (syncEnabled)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Device count badge
                    if (devices.isNotEmpty()) {
                        BadgedBox(badge = {
                            Badge { Text("${devices.size}") }
                        }) {
                            Icon(
                                Icons.Default.Dns,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddDevice,
                icon = { Icon(Icons.Default.Add, contentDescription = "Add") },
                text = { Text("Device") },
                containerColor = MaterialTheme.colorScheme.primary
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Sync mode banner
            if (syncEnabled && devices.any { it.config.isConnected }) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (masterDeviceId != null)
                                "Sync ON — Master device controls all selected devices"
                            else
                                "Sync ON — Select a master device by tapping ★ below",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            if (devices.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            Icons.Default.PhoneIphone,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "No devices connected",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Tap + to add a virtual device\nusing its SSH connection key",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                }
            } else {
                // Device list
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    itemsIndexed(devices) { index, state ->
                        DevicePanel(
                            deviceState = state,
                            index = index,
                            isMaster = masterDeviceId == state.config.id,
                            isSyncEnabled = syncEnabled,
                            onTap = { x, y, w, h ->
                                onDeviceTap(index, x, y, w, h)
                            },
                            onToggleSelect = { onToggleSelect(index) },
                            onSetMaster = { onSetMaster(index) },
                            onDisconnect = { onDisconnect(index) },
                            onRemove = { onRemove(index) },
                            onKeyEvent = { keyCode -> onKeyEvent(index, keyCode) }
                        )
                    }
                }
            }
        }

        // Add device dialog
        if (showAddDialog) {
            AddDeviceDialog(
                onDismiss = onDismissDialog,
                onConfirm = onAddDeviceConfirm
            )
        }
    }
}
