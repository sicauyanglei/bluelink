package com.bluelink.transfer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

class BluetoothDeviceStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("bluelink_bluetooth_devices", Context.MODE_PRIVATE)

    fun saveDevice(name: String?, address: String) {
        val devices = loadDevices().toMutableList()
        val existing = devices.find { it.address == address }
        if (existing != null) {
            if (existing.name.isNullOrBlank() && !name.isNullOrBlank()) {
                devices.remove(existing)
                devices.add(0, DeviceInfo(name, address))
            }
        } else {
            devices.add(0, DeviceInfo(name, address))
        }
        saveDevices(devices)
    }

    fun loadDevices(): List<DeviceInfo> {
        val json = prefs.getString("devices", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                DeviceInfo(
                    name = obj.optString("name").takeIf { it != "null" && it.isNotBlank() },
                    address = obj.getString("address")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun removeDevice(address: String) {
        val devices = loadDevices().toMutableList()
        devices.removeAll { it.address == address }
        saveDevices(devices)
    }

    private fun saveDevices(devices: List<DeviceInfo>) {
        val arr = JSONArray()
        devices.forEach { d ->
            val obj = org.json.JSONObject()
            obj.put("name", d.name ?: "")
            obj.put("address", d.address)
            arr.put(obj)
        }
        prefs.edit().putString("devices", arr.toString()).apply()
    }

    data class DeviceInfo(val name: String?, val address: String)
}
