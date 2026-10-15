package com.phonedeck.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import androidx.core.content.ContextCompat

/** Makes the phone/tablet act as a real Bluetooth keyboard + mouse (HID device profile, Android 9+). */
@SuppressLint("MissingPermission")
class HidManager(private val ctx: Context, private val onStatus: (String) -> Unit) {

    private val adapter: BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null

    val isConnected: Boolean get() = host != null

    fun nameOf(d: BluetoothDevice): String = try { d.name ?: d.address } catch (e: Exception) { "PC" }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (registered) onStatus("Ready. Tap Make visible and pair from the PC, or pick the PC under Devices.")
            else onStatus("Keyboard mode could not start. Your device may block it.")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> { host = device; onStatus("Connected: " + nameOf(device)) }
                BluetoothProfile.STATE_CONNECTING -> onStatus("Connecting to " + nameOf(device) + "…")
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host == device) host = null
                    onStatus("Disconnected. Reconnect from the PC or pick it under Devices.")
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)   // keyboard LEDs: ignored
        }
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val h = proxy as BluetoothHidDevice
            hid = h
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "PhoneDeck Keyboard", "PhoneDeck keyboard and mouse", "PhoneDeck",
                BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR
            )
            val ok = h.registerApp(sdp, null, null, ContextCompat.getMainExecutor(ctx), callback)
            if (!ok) onStatus("Keyboard mode could not start. Your device may block it.")
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) { hid = null; host = null }
        }
    }

    fun start() {
        val a = adapter
        if (a == null) { onStatus("This device has no Bluetooth."); return }
        if (!a.isEnabled) { onStatus("Bluetooth is off. Turn it on and reopen this screen."); return }
        onStatus("Starting Bluetooth keyboard…")
        if (!a.getProfileProxy(ctx, listener, BluetoothProfile.HID_DEVICE)) {
            onStatus("This device does not support Bluetooth keyboard mode.")
        }
    }

    fun stop() {
        try { sendKeyboard(0, emptyList()); sendMouse(0, 0, 0, 0) } catch (e: Exception) { }
        val h = hid
        if (h != null) {
            try { h.unregisterApp() } catch (e: Exception) { }
            try { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h) } catch (e: Exception) { }
        }
        hid = null
        host = null
    }

    fun bonded(): List<BluetoothDevice> =
        try { adapter?.bondedDevices?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }

    fun connectTo(d: BluetoothDevice) { try { hid?.connect(d) } catch (e: Exception) { } }

    fun disconnect() { val d = host ?: return; try { hid?.disconnect(d) } catch (e: Exception) { } }

    /** mods = modifier bitmask, keys = up to 6 HID usage codes currently held. */
    fun sendKeyboard(mods: Int, keys: List<Int>) {
        val d = host ?: return
        val b = ByteArray(8)
        b[0] = mods.toByte()
        for (i in 0 until minOf(6, keys.size)) b[2 + i] = keys[i].toByte()
        try { hid?.sendReport(d, 1, b) } catch (e: Exception) { }
    }

    /** buttons: bit0 left, bit1 right, bit2 middle. dx/dy/wheel: -127..127. */
    fun sendMouse(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        val d = host ?: return
        val b = byteArrayOf(buttons.toByte(), dx.toByte(), dy.toByte(), wheel.toByte())
        try { hid?.sendReport(d, 2, b) } catch (e: Exception) { }
    }

    companion object {
        private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

        // Report ID 1 = keyboard (8 bytes), Report ID 2 = mouse (buttons, x, y, wheel)
        val DESCRIPTOR: ByteArray = bytes(
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02,
            0x95, 0x01, 0x75, 0x03, 0x91, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
            0xC0,
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x02, 0x09, 0x01, 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x05, 0x81, 0x03,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
            0xC0, 0xC0
        )
    }
}
