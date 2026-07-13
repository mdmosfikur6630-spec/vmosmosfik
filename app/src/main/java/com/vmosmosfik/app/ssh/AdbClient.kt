package com.vmosmosfik.app.ssh

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.zip.Adler32

/**
 * Pure-Kotlin ADB protocol client.
 *
 * Instead of running ADB binary or using SSH exec channels (which this
 * relay doesn't support), this class implements enough of the ADB wire
 * protocol to control a device through a forwarded TCP port.
 *
 * Protocol: https://android.googlesource.com/platform/packages/modules/adb/
 */
class AdbClient {

    private val tag = "AdbClient"
    private var deviceSocket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var localId = 1

    companion object {
        // ADB command constants
        private const val A_CNXN = 0x4e584e43  // "CNXN"
        private const val A_OPEN = 0x4e45504f  // "OPEN"
        private const val A_OKAY = 0x59414b4f  // "OKAY"
        private const val A_CLSE = 0x45534c43  // "CLSE"
        private const val A_WRTE = 0x45545257  // "WRTE"
        private const val A_AUTH = 0x48545541  // "AUTH"
    }

    /**
     * Connect to the device ADB daemon through the SSH forwarded port.
     * The SSH tunnel should already be established by SshManager.
     */
    suspend fun connect(host: String = "127.0.0.1", port: Int = 61155): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val socket = Socket(host, port)
                socket.soTimeout = 15000
                deviceSocket = socket
                inputStream = socket.getInputStream()
                outputStream = socket.getOutputStream()

                // Send CNXN (connection) packet
                val version = 0x01000000  // ADB version 1.0
                val maxdata = 4096
                val systemType = "device::\0".toByteArray()

                sendPacket(A_CNXN, version, maxdata, systemType)

                // Read response
                val resp = readPacket()
                Log.i(tag, "ADB response: cmd=0x${resp.command.toString(16)}, " +
                        "arg0=${resp.arg0}, arg1=${resp.arg1}")

                when (resp.command) {
                    A_CNXN -> {
                        // Connected without auth
                        Log.i(tag, "ADB connected (no auth)")
                        Result.success(Unit)
                    }
                    A_AUTH -> {
                        // Need authentication - for cloud v devices, auth is usually disabled
                        // We'll try to reconnect or just fail
                        Log.w(tag, "ADB auth required - trying non-auth mode")
                        // Some devices accept non-authenticated mode
                        disconnect()
                        Result.failure(Exception("ADB authentication required - use non-auth device"))
                    }
                    else -> {
                        disconnect()
                        Result.failure(Exception("Unexpected ADB response: cmd=0x${resp.command.toString(16)}"))
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "ADB connect failed: ${e.message}", e)
                disconnect()
                Result.failure(e)
            }
        }

    /**
     * Execute a shell command and return the output.
     */
    suspend fun shell(command: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val service = "shell:$command\0".toByteArray()
                val (localId, remoteId) = openService(service)

                // Read all output
                val output = readUntilClose(localId, remoteId)
                Result.success(output)
            } catch (e: Exception) {
                Log.e(tag, "Shell command '$command' failed: ${e.message}", e)
                Result.failure(e)
            }
        }

    /**
     * Capture a screenshot using `screencap -p` via exec service.
     * Exec service returns raw binary data without shell wrapping.
     */
    suspend fun captureScreen(): Result<Bitmap> =
        withContext(Dispatchers.IO) {
            try {
                val service = "exec:screencap -p\0".toByteArray()
                val (localId, remoteId) = openService(service)

                // Read raw binary data
                val imageData = readRawUntilClose(localId, remoteId)
                if (imageData.isEmpty()) {
                    return@withContext Result.failure(Exception("Empty screenshot"))
                }

                val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                if (bitmap == null) {
                    return@withContext Result.failure(Exception("Failed to decode screenshot"))
                }

                Result.success(bitmap)
            } catch (e: Exception) {
                Log.e(tag, "Screenshot capture failed: ${e.message}", e)
                Result.failure(e)
            }
        }

    /**
     * Send a tap event.
     */
    suspend fun tap(x: Int, y: Int): Result<String> {
        return shell("input tap $x $y")
    }

    /**
     * Send a swipe event.
     */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): Result<String> {
        return shell("input swipe $x1 $y1 $x2 $y2 $durationMs")
    }

    /**
     * Send text input.
     */
    suspend fun text(text: String): Result<String> {
        val escaped = text.replace(" ", "%s")
        return shell("input text '$escaped'")
    }

    /**
     * Send a key event.
     */
    suspend fun keyEvent(keyCode: Int): Result<String> {
        return shell("input keyevent $keyCode")
    }

    /**
     * Get device screen size.
     */
    suspend fun getScreenSize(): Result<Pair<Int, Int>> {
        val result = shell("wm size")
        return result.map { output ->
            val parts = output.trim().split(": ")
            if (parts.size >= 2) {
                val dims = parts[1].trim().split("x")
                Pair(
                    dims.getOrNull(0)?.toIntOrNull() ?: 720,
                    dims.getOrNull(1)?.toIntOrNull() ?: 1280
                )
            } else {
                Pair(720, 1280)
            }
        }
    }

    /**
     * Install an APK (pushes and installs via ADB).
     */
    suspend fun installApk(apkData: ByteArray, apkName: String = "install.apk"): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                // Push the APK to device temp storage
                val encoded = android.util.Base64.encodeToString(apkData, android.util.Base64.NO_WRAP)
                val pushCmd = "base64 -d > /data/local/tmp/$apkName && pm install -r /data/local/tmp/$apkName && rm /data/local/tmp/$apkName"

                // Write base64 data to stdin
                val service = "shell:$pushCmd\0".toByteArray()
                val (localId, remoteId) = openService(service)

                // Send the base64 data
                val encodedBytes = encoded.toByteArray()
                sendPacket(A_WRTE, localId, remoteId, encodedBytes)

                // Read OKAY
                val ack = readPacket()
                if (ack.command != A_OKAY) {
                    closeService(localId, remoteId)
                    return@withContext Result.failure(Exception("Failed to send APK data"))
                }

                // Signal end of input
                sendPacket(A_CLSE, localId, remoteId)
                val output = readUntilClose(localId, remoteId)

                Result.success(output)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Launch an app by package name.
     */
    suspend fun launchApp(packageName: String, activity: String = ""): Result<String> {
        return if (activity.isNotEmpty()) {
            shell("am start -n $packageName/$activity")
        } else {
            shell("monkey -p $packageName 1")
        }
    }

    /**
     * Disconnect from the ADB device.
     */
    fun disconnect() {
        try { deviceSocket?.close() } catch (_: Exception) {}
        deviceSocket = null
        inputStream = null
        outputStream = null
    }

    // ========== ADB Protocol Implementation ==========

    private data class AdbPacket(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray
    )

    private fun sendPacket(command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)) {
        val stream = outputStream ?: throw Exception("Not connected")

        val dataLen = payload.size

        // ADB packet header: 24 bytes
        val header = ByteArray(24)
        writeIntLE(header, 0, command)
        writeIntLE(header, 4, arg0)
        writeIntLE(header, 8, arg1)
        writeIntLE(header, 12, dataLen)
        writeIntLE(header, 16, adler32(payload))
        writeIntLE(header, 20, command xor 0xFFFFFFFF.toInt())

        stream.write(header)
        if (dataLen > 0) {
            stream.write(payload)
        }
        stream.flush()
    }

    private fun readPacket(timeoutMs: Int = 10000): AdbPacket {
        val stream = inputStream ?: throw Exception("Not connected")
        val socket = deviceSocket ?: throw Exception("Not connected")

        socket.soTimeout = timeoutMs

        val header = ByteArray(24)
        var bytesRead = 0
        while (bytesRead < 24) {
            val n = stream.read(header, bytesRead, 24 - bytesRead)
            if (n < 0) throw Exception("Connection closed while reading header")
            bytesRead += n
        }

        val command = readIntLE(header, 0)
        val arg0 = readIntLE(header, 4)
        val arg1 = readIntLE(header, 8)
        val dataLen = readIntLE(header, 12)

        val payload = if (dataLen > 0) {
            val data = ByteArray(dataLen)
            var read = 0
            while (read < dataLen) {
                val n = stream.read(data, read, dataLen - read)
                if (n < 0) throw Exception("Connection closed while reading payload")
                read += n
            }
            data
        } else {
            ByteArray(0)
        }

        return AdbPacket(command, arg0, arg1, payload)
    }

    private fun openService(service: ByteArray): Pair<Int, Int> {
        val localId = this.localId++
        sendPacket(A_OPEN, localId, 0, service)

        val resp = readPacket()
        if (resp.command != A_OKAY) {
            throw Exception("Failed to open service (cmd=0x${resp.command.toString(16)})")
        }

        val remoteId = resp.arg1
        return Pair(localId, remoteId)
    }

    private fun readUntilClose(localId: Int, remoteId: Int): String {
        val output = StringBuilder()

        while (true) {
            val packet = readPacket(30000)

            when (packet.command) {
                A_WRTE -> {
                    val text = String(packet.payload, Charsets.UTF_8).trimEnd('\0', '\n', '\r')
                    output.append(text)
                    // ACK
                    sendPacket(A_OKAY, localId, remoteId)
                }
                A_CLSE -> {
                    output.append(String(packet.payload, Charsets.UTF_8).trimEnd('\0'))
                    break
                }
                A_OKAY -> {
                    // Just ack
                }
                else -> {
                    Log.w(tag, "Unexpected packet in readLoop: 0x${packet.command.toString(16)}")
                    break
                }
            }
        }

        return output.toString()
    }

    private fun readRawUntilClose(localId: Int, remoteId: Int): ByteArray {
        val output = ByteArrayOutputStream()

        while (true) {
            val packet = readPacket(30000)

            when (packet.command) {
                A_WRTE -> {
                    output.write(packet.payload)
                    // ACK
                    sendPacket(A_OKAY, localId, remoteId)
                }
                A_CLSE -> {
                    if (packet.payload.isNotEmpty()) {
                        output.write(packet.payload)
                    }
                    break
                }
                A_OKAY -> {}
                else -> {
                    break
                }
            }
        }

        return output.toByteArray()
    }

    private fun closeService(localId: Int, remoteId: Int) {
        try {
            sendPacket(A_CLSE, localId, remoteId)
        } catch (_: Exception) {}
    }

    private fun readIntLE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeIntLE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        data[offset + 2] = ((value shr 16) and 0xFF).toByte()
        data[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun adler32(data: ByteArray): Int {
        val adler = Adler32()
        adler.update(data)
        return adler.value.toInt()
    }
}
