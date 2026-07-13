package com.vmosmosfik.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vmosmosfik.app.ui.MainScreen
import com.vmosmosfik.app.ui.theme.VmosMosfikTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VmosMosfikTheme {
                val viewModel: VmViewModel = viewModel()
                val devices by viewModel.devices.collectAsStateWithLifecycle()
                val masterDeviceId by viewModel.masterDeviceId.collectAsStateWithLifecycle()
                val syncEnabled by viewModel.syncEnabled.collectAsStateWithLifecycle()
                var showAddDialog by remember { mutableStateOf(false) }

                MainScreen(
                    devices = devices,
                    masterDeviceId = masterDeviceId,
                    syncEnabled = syncEnabled,
                    onAddDevice = { showAddDialog = true },
                    onDeviceTap = { index, x, y, w, h ->
                        viewModel.onDeviceTap(index, x, y, w, h)
                    },
                    onToggleSelect = { index ->
                        viewModel.toggleDeviceSelection(index)
                    },
                    onSetMaster = { index ->
                        viewModel.setMasterDevice(index)
                    },
                    onDisconnect = { index ->
                        viewModel.disconnectDevice(index)
                    },
                    onRemove = { index ->
                        viewModel.removeDevice(index)
                    },
                    onKeyEvent = { index, keyCode ->
                        viewModel.sendKeyEvent(index, keyCode)
                    },
                    onToggleSync = { viewModel.toggleSync() },
                    showAddDialog = showAddDialog,
                    onDismissDialog = { showAddDialog = false },
                    onAddDeviceConfirm = { name, host, port, username, key ->
                        viewModel.addDevice(name, host, port, username, key)
                        showAddDialog = false
                    }
                )
            }
        }
    }
}
