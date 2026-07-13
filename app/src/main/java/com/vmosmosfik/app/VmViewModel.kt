package com.vmosmosfik.app

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vmosmosfik.app.data.DeviceConfig
import com.vmosmosfik.app.data.DeviceRepository
import com.vmosmosfik.app.ssh.SshManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DeviceState(
    val config: DeviceConfig,
    val screenshot: Bitmap? = null,
    val isConnecting: Boolean = false,
    val error: String? = null
)

class VmViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DeviceRepository(application)

    // Each active device has its own SshManager
    private val deviceManagers = mutableMapOf<String, SshManager>()

    private val _devices = MutableStateFlow<List<DeviceState>>(emptyList())
    val devices: StateFlow<List<DeviceState>> = _devices.asStateFlow()

    private val _masterDeviceId = MutableStateFlow<String?>(null)
    val masterDeviceId: StateFlow<String?> = _masterDeviceId.asStateFlow()

    private val _syncEnabled = MutableStateFlow(false)
    val syncEnabled: StateFlow<Boolean> = _syncEnabled.asStateFlow()

    private var screenCaptureJobs = mutableMapOf<String, Job>()

    init {
        loadSavedDevices()
    }

    private fun loadSavedDevices() {
        val saved = repository.loadDevices()
        _devices.value = saved.map { DeviceState(config = it) }
    }

    private fun saveDevices() {
        val configs = _devices.value.map { it.config }
        repository.saveDevices(configs)
    }

    /**
     * Add a new device configuration and auto-connect.
     */
    fun addDevice(
        name: String,
        host: String,
        port: Int,
        username: String,
        sshKey: String
    ) {
        val config = DeviceConfig(
            name = name,
            host = host,
            port = port,
            username = username,
            sshKey = sshKey
        )
        _devices.value = _devices.value + DeviceState(config = config)
        saveDevices()
        connectDevice(_devices.value.lastIndex)
    }

    /**
     * Connect to a device via SSH tunnel + ADB protocol.
     */
    fun connectDevice(index: Int) {
        val state = _devices.value.getOrNull(index) ?: return
        if (state.isConnecting || deviceManagers.containsKey(state.config.id)) return

        _devices.value = _devices.value.toMutableList().also { list ->
            list[index] = state.copy(isConnecting = true, error = null)
        }

        viewModelScope.launch {
            val manager = SshManager()
            val result = manager.connect(
                state.config.host,
                state.config.port,
                state.config.username,
                state.config.sshKey
            )

            result.fold(
                onSuccess = {
                    deviceManagers[state.config.id] = manager
                    _devices.value = _devices.value.toMutableList().also { list ->
                        list[index] = list[index].copy(
                            isConnecting = false,
                            config = list[index].config.copy(isConnected = true)
                        )
                    }
                    saveDevices()
                    startScreenCapture(index)
                    getDeviceScreenSize(index)
                },
                onFailure = { error ->
                    manager.disconnect()
                    _devices.value = _devices.value.toMutableList().also { list ->
                        list[index] = list[index].copy(
                            isConnecting = false,
                            error = "Connection failed: ${error.message}"
                        )
                    }
                }
            )
        }
    }

    /**
     * Disconnect a device.
     */
    fun disconnectDevice(index: Int) {
        val state = _devices.value.getOrNull(index) ?: return
        val configId = state.config.id

        screenCaptureJobs[configId]?.cancel()
        screenCaptureJobs.remove(configId)
        deviceManagers[configId]?.disconnect()
        deviceManagers.remove(configId)

        _devices.value = _devices.value.toMutableList().also { list ->
            list[index] = list[index].copy(
                screenshot = null,
                config = list[index].config.copy(isConnected = false, isSelected = false)
            )
        }
        saveDevices()
    }

    /**
     * Remove a device entirely.
     */
    fun removeDevice(index: Int) {
        disconnectDevice(index)
        _devices.value = _devices.value.toMutableList().also { list ->
            list.removeAt(index)
        }
        saveDevices()
    }

    fun toggleDeviceSelection(index: Int) {
        _devices.value = _devices.value.toMutableList().also { list ->
            val current = list[index]
            list[index] = current.copy(
                config = current.config.copy(isSelected = !current.config.isSelected)
            )
        }
        saveDevices()
    }

    fun toggleSync() {
        _syncEnabled.value = !_syncEnabled.value
        if (!_syncEnabled.value) {
            _masterDeviceId.value = null
        }
    }

    fun setMasterDevice(index: Int) {
        val state = _devices.value.getOrNull(index) ?: return
        _masterDeviceId.value = state.config.id
        _syncEnabled.value = true
    }

    /**
     * Handle a tap on a device screen with sync mirroring.
     */
    fun onDeviceTap(deviceIndex: Int, x: Float, y: Float, deviceWidth: Float, deviceHeight: Float) {
        val state = _devices.value.getOrNull(deviceIndex) ?: return
        val configId = state.config.id
        val manager = deviceManagers[configId] ?: return

        val actualX = (x / deviceWidth * state.config.screenWidth).toInt()
        val actualY = (y / deviceHeight * state.config.screenHeight).toInt()

        viewModelScope.launch {
            manager.tap(actualX, actualY)

            // Mirror to synced devices
            if (_syncEnabled.value && configId == _masterDeviceId.value) {
                for (i in _devices.value.indices) {
                    if (i == deviceIndex) continue
                    val slave = _devices.value[i]
                    if (slave.config.isSelected) {
                        deviceManagers[slave.config.id]?.tap(actualX, actualY)
                    }
                }
            }
        }
    }

    fun installApkOnDevice(index: Int, apkData: ByteArray, apkName: String) {
        val state = _devices.value.getOrNull(index) ?: return
        val manager = deviceManagers[state.config.id] ?: return

        viewModelScope.launch {
            manager.installApk(apkData, apkName)

            if (_syncEnabled.value && state.config.id == _masterDeviceId.value) {
                for (i in _devices.value.indices) {
                    if (i == index) continue
                    val slave = _devices.value[i]
                    if (slave.config.isSelected) {
                        deviceManagers[slave.config.id]?.installApk(apkData, apkName)
                    }
                }
            }
        }
    }

    fun launchAppOnDevice(index: Int, packageName: String, activity: String = "") {
        val state = _devices.value.getOrNull(index) ?: return
        val manager = deviceManagers[state.config.id] ?: return

        viewModelScope.launch {
            manager.launchApp(packageName, activity)

            if (_syncEnabled.value && state.config.id == _masterDeviceId.value) {
                for (i in _devices.value.indices) {
                    if (i == index) continue
                    val slave = _devices.value[i]
                    if (slave.config.isSelected) {
                        deviceManagers[slave.config.id]?.launchApp(packageName, activity)
                    }
                }
            }
        }
    }

    fun sendKeyEvent(index: Int, keyCode: Int) {
        val state = _devices.value.getOrNull(index) ?: return
        val manager = deviceManagers[state.config.id] ?: return

        viewModelScope.launch {
            manager.keyEvent(keyCode)

            if (_syncEnabled.value && state.config.id == _masterDeviceId.value) {
                for (i in _devices.value.indices) {
                    if (i == index) continue
                    val slave = _devices.value[i]
                    if (slave.config.isSelected) {
                        deviceManagers[slave.config.id]?.keyEvent(keyCode)
                    }
                }
            }
        }
    }

    private fun startScreenCapture(index: Int) {
        val state = _devices.value.getOrNull(index) ?: return
        val configId = state.config.id

        screenCaptureJobs[configId]?.cancel()

        val job = viewModelScope.launch {
            while (true) {
                val manager = deviceManagers[configId] ?: break

                val result = manager.captureScreen()
                result.onSuccess { bitmap ->
                    _devices.value = _devices.value.toMutableList().also { list ->
                        list[index] = list[index].copy(screenshot = bitmap)
                    }
                }

                delay(1500) // Capture every 1.5 seconds
            }
        }
        screenCaptureJobs[configId] = job
    }

    private fun getDeviceScreenSize(index: Int) {
        viewModelScope.launch {
            val state = _devices.value.getOrNull(index) ?: return@launch
            val manager = deviceManagers[state.config.id] ?: return@launch

            val result = manager.getScreenSize()
            result.onSuccess { (w, h) ->
                _devices.value = _devices.value.toMutableList().also { list ->
                    list[index] = list[index].copy(
                        config = list[index].config.copy(screenWidth = w, screenHeight = h)
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        screenCaptureJobs.values.forEach { it.cancel() }
        deviceManagers.values.forEach { it.disconnect() }
        deviceManagers.clear()
    }
}
