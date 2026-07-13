package com.vmosmosfik.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manages persistent storage of device configurations.
 */
class DeviceRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("vmosmosfik", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun loadDevices(): List<DeviceConfig> {
        val json = prefs.getString("devices", "[]") ?: "[]"
        val type = object : TypeToken<List<DeviceConfig>>() {}.type
        return gson.fromJson(json, type)
    }

    fun saveDevices(devices: List<DeviceConfig>) {
        prefs.edit().putString("devices", gson.toJson(devices)).apply()
    }
}
