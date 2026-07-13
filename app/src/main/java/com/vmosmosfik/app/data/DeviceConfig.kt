package com.vmosmosfik.app.data

import java.util.UUID

/**
 * Represents a single virtual device connection configuration.
 *
 * The SSH connection goes to a relay server which forwards ADB
 * commands to the actual virtual device.
 */
data class DeviceConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Device",
    val host: String = "162.128.224.130",
    val port: Int = 1824,
    val username: String = "s",
    val sshKey: String = "",
    val isConnected: Boolean = false,
    val isSelected: Boolean = false,
    val screenWidth: Int = 720,
    val screenHeight: Int = 1280
)
