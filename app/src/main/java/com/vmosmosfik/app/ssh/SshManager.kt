package com.vmosmosfik.app.ssh

import android.graphics.Bitmap
import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages SSH tunnel + ADB connections to virtual devices.
 *
 * Architecture:
 * 1. SSH to relay server (port forwarding only - the relay does NOT support exec)
 * 2. ADB protocol through the forwarded port to control the virtual device
 */
class SshManager {

    private val tag = "SshManager"
    private var session: Session? = null
    private val adbClient = AdbClient()
    private val jsch = JSch()
    private var forwardedPort = 0

    /**
     * Connect to the relay server via SSH with port forwarding.
     * After connection, the device is reachable via ADB on localhost:forwardedPort.
     */
    suspend fun connect(host: String, port: Int, username: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val s = jsch.getSession(username, host, port)
                s.setPassword(password)
                s.setConfig("StrictHostKeyChecking", "no")
                s.setConfig("PreferredAuthentications", "password")
                s.connect(15000)
                session = s
                Log.i(tag, "SSH connected to $host:$port")

                // Set up port forwarding: localhost:0 → remote:localhost:1
                // (port 0 = auto-assign available port)
                val assignedPort = s.setPortForwardingL(0, "localhost", 1)
                forwardedPort = assignedPort
                Log.i(tag, "Port forwarded: localhost:$assignedPort → device ADB")

                // Connect ADB through the forwarded port
                val adbResult = adbClient.connect("127.0.0.1", assignedPort)
                adbResult.getOrThrow()

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(tag, "Connection failed: ${e.message}", e)
                cleanup()
                Result.failure(e)
            }
        }

    /**
     * Take a screenshot of the device.
     */
    suspend fun captureScreen(): Result<Bitmap> {
        return adbClient.captureScreen()
    }

    /**
     * Send a tap event at the specified coordinates.
     */
    suspend fun tap(x: Int, y: Int): Result<String> {
        return adbClient.tap(x, y)
    }

    /**
     * Send a swipe gesture.
     */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): Result<String> {
        return adbClient.swipe(x1, y1, x2, y2, durationMs)
    }

    /**
     * Send text input.
     */
    suspend fun text(text: String): Result<String> {
        return adbClient.text(text)
    }

    /**
     * Send a hardware key event.
     */
    suspend fun keyEvent(keyCode: Int): Result<String> {
        return adbClient.keyEvent(keyCode)
    }

    /**
     * Get the device screen resolution.
     */
    suspend fun getScreenSize(): Result<Pair<Int, Int>> {
        return adbClient.getScreenSize()
    }

    /**
     * Install an APK on the device.
     */
    suspend fun installApk(apkData: ByteArray, apkName: String = "install.apk"): Result<String> {
        return adbClient.installApk(apkData, apkName)
    }

    /**
     * Launch an app on the device.
     */
    suspend fun launchApp(packageName: String, activity: String = ""): Result<String> {
        return adbClient.launchApp(packageName, activity)
    }

    /**
     * Execute a shell command on the device.
     */
    suspend fun shell(command: String): Result<String> {
        return adbClient.shell(command)
    }

    /**
     * Clean up all connections.
     */
    fun disconnect() {
        cleanup()
    }

    private fun cleanup() {
        try {
            adbClient.disconnect()
        } catch (_: Exception) {}

        try {
            if (forwardedPort > 0) {
                session?.delPortForwardingL(forwardedPort)
            }
        } catch (_: Exception) {}

        try {
            session?.disconnect()
        } catch (_: Exception) {}

        session = null
        forwardedPort = 0
    }
}
