package com.vmosmosfik.app.ssh

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * Manages SSH connections to virtual devices via a relay server.
 *
 * Each device connection:
 * 1. SSH to relay server
 * 2. Execute `adb shell <command>` on the relay to control the virtual device
 * 3. Capture screen via `adb exec-out screencap -p`
 */
class SshManager {

    private val tag = "SshManager"
    private var session: Session? = null
    private val jsch = JSch()

    /**
     * Connect to the relay server and return the SSH session.
     */
    suspend fun connect(host: String, port: Int, username: String, password: String): Result<Session> =
        withContext(Dispatchers.IO) {
            try {
                val s = jsch.getSession(username, host, port)
                s.setPassword(password)
                s.setConfig("StrictHostKeyChecking", "no")
                s.setConfig("PreferredAuthentications", "password")
                s.connect(15000)  // 15 second timeout
                session = s
                Log.i(tag, "Connected to $host:$port")
                Result.success(s)
            } catch (e: Exception) {
                Log.e(tag, "Failed to connect: ${e.message}", e)
                Result.failure(e)
            }
        }

    /**
     * Execute a shell command on the relay server via SSH.
     * The relay should have ADB configured to reach the virtual device.
     */
    suspend fun executeCommand(session: Session, command: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val channel = session.openChannel("exec") as ChannelExec
                channel.setCommand(command)

                val outputStream = ByteArrayOutputStream()
                val errorStream = ByteArrayOutputStream()

                channel.setOutputStream(outputStream)
                channel.setErrStream(errorStream)

                channel.connect(30000)  // 30 second timeout

                // Wait for command to finish
                while (!channel.isClosed) {
                    Thread.sleep(100)
                }

                val output = outputStream.toString("UTF-8").trim()
                val error = errorStream.toString("UTF-8").trim()

                channel.disconnect()

                if (error.isNotEmpty()) {
                    Log.w(tag, "Command stderr: $error")
                }

                Log.d(tag, "Command '$command' executed, exit=${channel.exitStatus}")
                Result.success(output)
            } catch (e: Exception) {
                Log.e(tag, "Command failed: ${e.message}", e)
                Result.failure(e)
            }
        }

    /**
     * Execute ADB command on the relay to control the virtual device.
     * @param adbSerial The device serial or identifier (empty if only one device)
     */
    suspend fun adbShell(session: Session, command: String, adbSerial: String = ""): Result<String> {
        val serialFlag = if (adbSerial.isNotEmpty()) " -s $adbSerial" else ""
        return executeCommand(session, "adb$serialFlag shell $command")
    }

    /**
     * Capture a screenshot of the virtual device and return as Bitmap.
     * Uses `adb exec-out screencap -p` for raw PNG data.
     */
    suspend fun captureScreen(session: Session): Result<Bitmap> =
        withContext(Dispatchers.IO) {
            try {
                val channel = session.openChannel("exec") as ChannelExec
                channel.setCommand("adb exec-out screencap -p")

                val pipedInput = PipedInputStream()
                val pipedOutput = PipedOutputStream(pipedInput)
                channel.outputStream = pipedOutput

                channel.connect(15000)

                // Read the raw PNG bytes
                val buffer = ByteArray(65536)
                val imageData = ByteArrayOutputStream()
                var bytesRead: Int
                try {
                    while (pipedInput.read(buffer).also { bytesRead = it } >= 0) {
                        imageData.write(buffer, 0, bytesRead)
                    }
                } catch (e: Exception) {
                    // Stream closed, that's ok
                }

                channel.disconnect()

                val data = imageData.toByteArray()
                if (data.isEmpty()) {
                    return@withContext Result.failure(Exception("Empty screenshot data"))
                }

                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                if (bitmap == null) {
                    return@withContext Result.failure(Exception("Failed to decode screenshot"))
                }

                Result.success(bitmap)
            } catch (e: Exception) {
                Log.e(tag, "Screenshot failed: ${e.message}", e)
                Result.failure(e)
            }
        }

    /**
     * Send a tap event to the virtual device at specified coordinates.
     */
    suspend fun tap(session: Session, x: Int, y: Int): Result<String> {
        return adbShell(session, "input tap $x $y")
    }

    /**
     * Send a swipe event.
     */
    suspend fun swipe(session: Session, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): Result<String> {
        return adbShell(session, "input swipe $x1 $y1 $x2 $y2 $durationMs")
    }

    /**
     * Send a text input.
     */
    suspend fun text(session: Session, text: String): Result<String> {
        // Escape special chars for shell
        val escaped = text.replace(" ", "%s")
            .replace("'", "\\'")
            .replace("!", "\\!")
        return adbShell(session, "input text '$escaped'")
    }

    /**
     * Press a key (e.g. HOME, BACK, KEYCODE_ENTER).
     */
    suspend fun keyEvent(session: Session, keyCode: Int): Result<String> {
        return adbShell(session, "input keyevent $keyCode")
    }

    /**
     * Install an APK on the virtual device.
     * @param apkData The raw APK file bytes
     */
    suspend fun installApk(session: Session, apkData: ByteArray, apkName: String = "install.apk"): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                // Push APK to device
                val pushResult = executeCommand(session,
                    "adb push - /data/local/tmp/$apkName")
                // Note: For actual push via stdin, we need a different approach
                // For now, we'll use base64 encoding

                val base64Apk = Base64.encodeToString(apkData, Base64.NO_WRAP)
                val cmd = "echo '$base64Apk' | base64 -d | adb push - /data/local/tmp/$apkName && adb shell pm install -r /data/local/tmp/$apkName"

                val result = executeCommand(session, cmd)
                // Clean up
                executeCommand(session, "adb shell rm /data/local/tmp/$apkName")
                result
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Launch an app on the virtual device by package name.
     */
    suspend fun launchApp(session: Session, packageName: String, activity: String = ""): Result<String> {
        val intent = if (activity.isNotEmpty()) {
            "$packageName/$activity"
        } else {
            val resolved = executeCommand(session, "adb shell pm resolve-activity --brief $packageName")
            if (resolved.isSuccess) resolved.getOrDefault(packageName) else packageName
        }
        return adbShell(session, "am start -n $intent")
    }

    /**
     * Get the device screen resolution.
     */
    suspend fun getScreenSize(session: Session): Result<Pair<Int, Int>> {
        val result = adbShell(session, "wm size")
        return result.map { output ->
            val parts = output.split(": ")
            if (parts.size >= 2) {
                val dims = parts[1].trim().split("x")
                if (dims.size == 2) {
                    Pair(dims[0].toIntOrNull() ?: 720, dims[1].toIntOrNull() ?: 1280)
                } else {
                    Pair(720, 1280)
                }
            } else {
                Pair(720, 1280)
            }
        }
    }

    /**
     * Disconnect the SSH session.
     */
    fun disconnect() {
        try {
            session?.disconnect()
        } catch (_: Exception) {}
        session = null
    }
}
