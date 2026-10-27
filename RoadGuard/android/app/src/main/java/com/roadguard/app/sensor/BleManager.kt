package com.roadguard.app.sensor

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.roadguard.app.BuildConfig
import com.roadguard.app.domain.model.SensorTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * BleManager
 *
 * Handles Bluetooth Low Energy scanning, connection, and
 * data reception from the RoadGuard ESP32 firmware.
 *
 * Data flow: ESP32 → BLE TX characteristic → Android
 *
 * This class does NOT require internet connectivity.
 * Classification and alarms work offline with cached data.
 *
 * Requires: BLUETOOTH_SCAN and BLUETOOTH_CONNECT permissions (Android 12+)
 *           BLUETOOTH and BLUETOOTH_ADMIN (Android < 12)
 *           ACCESS_FINE_LOCATION (Android < 12 for BLE scanning)
 */
class BleManager(private val context: Context) {

    sealed class BleState {
        object Idle           : BleState()
        object Scanning       : BleState()
        data class Connecting(val deviceName: String) : BleState()
        data class Connected(val deviceName: String)  : BleState()
        data class Error(val message: String)         : BleState()
        object Disconnected   : BleState()
    }

    private val _bleState      = MutableStateFlow<BleState>(BleState.Idle)
    val bleState: StateFlow<BleState> = _bleState.asStateFlow()

    private val _telemetry     = MutableStateFlow<SensorTelemetry?>(null)
    val telemetry: StateFlow<SensorTelemetry?> = _telemetry.asStateFlow()

    private val _rawPackets    = MutableStateFlow<String?>(null)
    val rawPackets: StateFlow<String?> = _rawPackets.asStateFlow()

    private val serviceUuid    = UUID.fromString(BuildConfig.BLE_SERVICE_UUID)
    private val txCharUuid     = UUID.fromString(BuildConfig.BLE_CHAR_UUID_TX)
    private val rxCharUuid     = UUID.fromString(BuildConfig.BLE_CHAR_UUID_RX)

    private val btManager      = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val btAdapter      = btManager?.adapter
    private var bleScanner     : BluetoothLeScanner? = null
    private var gatt           : BluetoothGatt? = null
    private val devicePrefix   = BuildConfig.BLE_DEVICE_NAME_PREFIX

    // ============================================================
    // BLE Scan Callback
    // ============================================================
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val name   = device.name ?: return
            if (name.startsWith(devicePrefix)) {
                stopScan()
                _bleState.value = BleState.Connecting(name)
                connectToDevice(device)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            _bleState.value = BleState.Error("BLE scan failed (code $errorCode). Check Bluetooth permissions.")
        }
    }

    // ============================================================
    // GATT Callbacks
    // ============================================================
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _bleState.value = BleState.Connected(gatt?.device?.name ?: "ESP32")
                    gatt?.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _bleState.value = BleState.Disconnected
                    this@BleManager.gatt?.close()
                    this@BleManager.gatt = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _bleState.value = BleState.Error("Service discovery failed (status $status).")
                return
            }
            val txChar = gatt?.getService(serviceUuid)?.getCharacteristic(txCharUuid)
            if (txChar == null) {
                _bleState.value = BleState.Error("TX characteristic not found. Check firmware UUIDs.")
                return
            }
            // Enable notifications on the TX characteristic
            gatt.setCharacteristicNotification(txChar, true)
            val descriptor = txChar.getDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") // CCCD
            )
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == txCharUuid) {
                val raw = characteristic.value?.toString(Charsets.UTF_8) ?: return
                _rawPackets.value = raw
                parseTelemetry(raw)?.let { _telemetry.value = it }
            }
        }
    }

    // ============================================================
    // Public API
    // ============================================================

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (btAdapter == null || !btAdapter.isEnabled) {
            _bleState.value = BleState.Error("Bluetooth is not enabled.")
            return
        }
        bleScanner = btAdapter.bluetoothLeScanner
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(serviceUuid))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        _bleState.value = BleState.Scanning
        bleScanner?.startScan(filters, settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        bleScanner?.stopScan(scanCallback)
        bleScanner = null
        if (_bleState.value is BleState.Scanning) {
            _bleState.value = BleState.Idle
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _bleState.value = BleState.Idle
        _telemetry.value = null
    }

    /** Send a text command to the ESP32 (e.g., "calibrate", "ping"). */
    @SuppressLint("MissingPermission")
    fun sendCommand(command: String) {
        val rxChar = gatt?.getService(serviceUuid)?.getCharacteristic(rxCharUuid) ?: return
        rxChar.value = command.toByteArray(Charsets.UTF_8)
        gatt?.writeCharacteristic(rxChar)
    }

    val isConnected: Boolean get() = _bleState.value is BleState.Connected

    // ============================================================
    // Internal
    // ============================================================

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * Parse a JSON telemetry string into a SensorTelemetry object.
     * Returns null for any malformed or physically impossible packet.
     */
    internal fun parseTelemetry(raw: String): SensorTelemetry? {
        return try {
            val json = Json.parseToJsonElement(raw).jsonObject
            val telemetry = SensorTelemetry(
                deviceId  = json["deviceId"]?.jsonPrimitive?.content ?: "",
                timestamp = json["timestamp"]?.jsonPrimitive?.long ?: 0L,
                accelX    = json["accelX"]?.jsonPrimitive?.float  ?: 0f,
                accelY    = json["accelY"]?.jsonPrimitive?.float  ?: 0f,
                accelZ    = json["accelZ"]?.jsonPrimitive?.float  ?: 9.81f,
                gyroX     = json["gyroX"]?.jsonPrimitive?.float   ?: 0f,
                gyroY     = json["gyroY"]?.jsonPrimitive?.float   ?: 0f,
                gyroZ     = json["gyroZ"]?.jsonPrimitive?.float   ?: 0f,
                latitude  = json["latitude"]?.jsonPrimitive?.double  ?: 0.0,
                longitude = json["longitude"]?.jsonPrimitive?.double ?: 0.0,
                speedKmh  = json["speedKmh"]?.jsonPrimitive?.float   ?: 0f,
                gpsFix    = json["gpsFix"]?.jsonPrimitive?.boolean    ?: false,
                sensorOk  = json["sensorOk"]?.jsonPrimitive?.boolean  ?: false
            )
            if (SensorTelemetry.isValidPacket(telemetry)) telemetry else null
        } catch (e: Exception) {
            null // Malformed packet — discard silently
        }
    }
}
