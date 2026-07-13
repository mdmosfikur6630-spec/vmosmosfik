package com.vmosmosfik.app

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.jcraft.jsch.Session
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
    val session: Session? = null,
    val screenshot: Bitmap? = null,
    val isConnecting: Boolean = false,
    val error: String? = null
)

class VmViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DeviceRepository(application)
    private val sshManager = SshManager()

    private val _devices = MutableStateFlow<List<DeviceState>>(emptyList())
    val devices: StateFlow<List<DeviceState>> = _devices.asStateFlow()

    private val _masterDeviceId = MutableStateFlow<String?>(null)
    val masterDeviceId: StateFlow<String?> = _masterDeviceId.asStateFlow()

    private val _syncEnabled = MutableStateFlow(false)
    val syncEnabled: StateFlow<Boolean> = _syncEnabled.asStateFlow()

    private var screenCaptureJobs = mutableMapOf<String, Job>()
    private var lastTapPositions = mutableMapOf<String, Pair<Int, Int>>()

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
     * Add a new device configuration.
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

        // Auto-connect to the new device
        connectDevice(_devices.value.lastIndex)
    }

    /**
     * Connect to a device at the given index.
     */
    fun connectDevice(index: Int) {
        val state = _devices.value.getOrNull(index) ?: return
        if (state.isConnecting || state.session != null) return

        _devices.value = _devices.value.toMutableList().also { list ->
            list[index] = state.copy(isConnecting = true, error = null)
        }

        viewModelScope.launch {
            val result = sshManager.connect(
                state.config.host,
                state.config.port,
                state.config.username,
                state.config.sshKey
            )

            result.fold(
                onSuccess = { session ->
                    _devices.value = _devices.value.toMutableList().also { list ->
                        list[index] = list[index].copy(
                            session = session,
                            isConnecting = false,
                            config = list[index].config.copy(isConnected = true)
                        )
                    }
                    saveDevices()
                    // Start screen capture loop
                    startScreenCapture(index)
                    // Get screen size
                    getDeviceScreenSize(index)
                },
                onFailure = { error ->
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
        screenCaptureJobs[index.toString()]?.cancel()
        screenCaptureJobs.remove(index.toString())

        val state = _devices.value.getOrNull(index) ?: return
        // SSH session is managed by the relay, we just update state
        _devices.value = _devices.value.toMutableList().also { list ->
            list[index] = state.copy(
                session = null,
                screenshot = null,
                config = state.config.copy(isConnected = false, isSelected = false)
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

    /**
     * Toggle device selection for sync.
     */
    fun toggleDeviceSelection(index: Int) {
        _devices.value = _devices.value.toMutableList().also { list ->
            val current = list[index]
            list[index] = current.copy(
                config = current.config.copy(isSelected = !current.config.isSelected)
            )
        }
        saveDevices()
    }

    /**
     * Toggle sync mode.
     */
    fun toggleSync() {
        _syncEnabled.value = !_syncEnabled.value
        if (!_syncEnabled.value) {
            _masterDeviceId.value = null
        }
    }

    /**
     * Set the master device for sync.
     */
    fun setMasterDevice(index: Int) {
        val state = _devices.value.getOrNull(index) ?: return
        _masterDeviceId.value = state.config.id
        _syncEnabled.value = true
    }

    /**
     * Handle a tap on a device screen.
     */
    fun onDeviceTap(deviceIndex: Int, x: Float, y: Float, deviceWidth: Float, deviceHeight: Float) {
        val state = _devices.value.getOrNull(deviceIndex) ?: return
        val session = state.session ?: return
        val config = state.config

        // Calculate actual device coordinates
        val actualX = (x / deviceWidth * config.screenWidth).toInt()
        val actualY = (y / deviceHeight * config.screenHeight).toInt()

        viewModelScope.launch {
            // Tap on the target device
            sshManager.tap(session, actualX, actualY)

            // If sync is enabled, also tap on all other synced devices
            if (_syncEnabled.value && config.id == _masterDeviceId.value) {
                val slaveIndices = _devices.value.indices.filter { i ->
                    i != deviceIndex && _devices.value[i].config.isSelected && _devices.value[i].session != null
                }
                for (i in slaveIndices) {
                    val slaveSession = _devices.value[i].session
                    if (slaveSession != null) {
                        sshManager.tap(slaveSession, actualX, actualY)
                    }
                }
            }
        }
    }

    /**
     * Install an APK on a specific device.
     */
    fun installApkOnDevice(index: Int, apkData: ByteArray, apkName: String) {
        val state = _devices.value.getOrNull(index) ?: return
        val session = state.session ?: return

        viewModelScope.launch {
            sshManager.installApk(session, apkData, apkName)

            // Mirror to synced devices
            if (_syncEnabled.value && state.config.id == _masterDeviceId.value) {
                val slaveIndices = _devices.value.indices.filter { i ->
                    i != index && _devices.value[i].config.isSelected && _devices.value[i].session != null
                }
                for (i in slaveIndices) {
                    val slaveSession = _devices.value[i].session
                    if (slaveSession != null) {
                        sshManager.installApk(slaveSession, apkData, apkName)
                    }
                }
            }
        }
    }

    /**
     * Launch an app on a specific device.
     */
    fun launchAppOnDevice(index: Int, packageName: String, activity: String = "") {
        val state = _devices.value.getOrNull(index) ?: return
        val session = state.session ?: return

        viewModelScope.launch {
            sshManager.launchApp(session, packageName, activity)

            // Mirror to synced devices
            if (_syncEnabled.value && state.config.id == _masterDeviceId.value) {
                val slaveIndices = _devices.value.indices.filter { i ->
                    i != index && _devices.value[i].config.isSelected && _devices.value[i].session != null
                }
                for (i in slaveIndices) {
                    val slaveSession = _devices.value[i].session
                    if (slaveSession != null) {
                        sshManager.launchApp(slaveSession, packageName, activity)
                    }
                }
            }
        }
    }

    /**
     * Send a key event (HOME, BACK, etc.) to device.
     */
    fun sendKeyEvent(index: Int, keyCode: Int) {
        val state = _devices.value.getOrNull(index) ?: return
        val session = state.session ?: return

        viewModelScope.launch {
            sshManager.keyEvent(session, keyCode)

            // Mirror to synced devices
            if (_syncEnabled.value && state.config.id == _masterDeviceId.value) {
                val slaveIndices = _devices.value.indices.filter { i ->
                    i != index && _devices.value[i].config.isSelected && _devices.value[i].session != null
                }
                for (i in slaveIndices) {
                    val slaveSession = _devices.value[i].session
                    if (slaveSession != null) {
                        sshManager.keyEvent(slaveSession, keyCode)
                    }
                }
            }
        }
    }

    private fun startScreenCapture(index: Int) {
        screenCaptureJobs[index.toString()]?.cancel()

        val job = viewModelScope.launch {
            while (true) {
                val state = _devices.value.getOrNull(index) ?: break
                val session = state.session ?: break

                val result = sshManager.captureScreen(session)
                result.onSuccess { bitmap ->
                    _devices.value = _devices.value.toMutableList().also { list ->
                        list[index] = list[index].copy(screenshot = bitmap)
                    }
                }

                delay(1000) // Capture every 1 second
            }
        }
        screenCaptureJobs[index.toString()] = job
    }

    private suspend fun getDeviceScreenSize(index: Int) {
        val state = _devices.value.getOrNull(index) ?: return
        val session = state.session ?: return

        val result = sshManager.getScreenSize(session)
        result.onSuccess { (w, h) ->
            _devices.value = _devices.value.toMutableList().also { list ->
                list[index] = list[index].copy(
                    config = list[index].config.copy(screenWidth = w, screenHeight = h)
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        screenCaptureJobs.values.forEach { it.cancel() }
    }
}
