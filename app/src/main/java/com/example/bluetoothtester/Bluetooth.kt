package com.example.bluetoothtester

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.AUDIO_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import com.toggl.komposable.architecture.ReduceResult
import com.toggl.komposable.architecture.Reducer
import com.toggl.komposable.extensions.withoutEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.apply
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ByteBuffer

const val logTag = "Bluetooth"
val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

sealed class BluetoothAction {
    data class BluetoothInitialised(val manager: BluetoothManager? = null) : BluetoothAction()
    data class BluetoothDebugMessaged(val message: String) : BluetoothAction()
    data class ActivityCreated(val activity: Activity) : BluetoothAction()
    data class ContextCreated(val context: Context) : BluetoothAction()
    data class DeviceScanned(val name: String?, val mac: String?) : BluetoothAction()
    data class DeviceConnected(val device: Device) : BluetoothAction()
    data object DeviceDisconnected : BluetoothAction()
    data object DevicesReset : BluetoothAction()
    data class NamedOnlyToggled(val enabled: Boolean) : BluetoothAction()
    data class NoRealgateToggled(val enabled: Boolean) : BluetoothAction()
    data class SoundPlayed(val name: String? = null) : BluetoothAction()
    data class AudioManagerInitialised(val audioManager: AudioManager) : BluetoothAction()
    data class ScopeInitialised(val coroutineScope: CoroutineScope) : BluetoothAction()
    data object RecordingToggled : BluetoothAction()
    data class RecordingSet(val enabled: Boolean) : BluetoothAction()
    data class RecordingStarted(val audioRecord: AudioRecord, val recordingJob: Job) : BluetoothAction()
}

data class Device (
    val name: String? = "",
    val mac: String? = "",
    val gatt: BluetoothGatt? = null
)

data class BluetoothState(
    val manager: BluetoothManager? = null,
    val devices: List<Device> = listOf(),
    val connectedDevice: Device? = null,
    val message: String = "",
    val activity: Activity? = null,
    val context: Context? = null,
    val namedOnly: Boolean = true,
    val noRealgate: Boolean = true,
    val isRecording: Boolean = false,
    val audioManager: AudioManager? = null,
    val audioRecord: AudioRecord? = null,
    val coroutineScope: CoroutineScope? = null,
    val recordingJob: Job? = null
)

private fun writeWavHeader(out: OutputStream, sampleRate: Int, channelConfig: Int) {
    val channels = if (channelConfig == AudioFormat.CHANNEL_IN_MONO) 1 else 2
    val byteRate = sampleRate * channels * 2 // 16-bit

    val header = ByteArray(44)
    ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray())
        putInt(0) // placeholder for file size
        put("WAVE".toByteArray())
        put("fmt ".toByteArray())
        putInt(16) // PCM
        putShort(1) // PCM format
        putShort(channels.toShort())
        putInt(sampleRate)
        putInt(byteRate)
        putShort((channels * 2).toShort()) // block align
        putShort(16) // bits per sample
        put("data".toByteArray())
        putInt(0) // placeholder for data size
    }
    out.write(header)
}

private fun updateWavHeader(file: File) {
    val size = file.length().toInt()
    val dataSize = size - 44
    val buffer = RandomAccessFile(file, "rw")
    buffer.seek(4)
    buffer.writeInt(Integer.reverseBytes(size - 8))
    buffer.seek(40)
    buffer.writeInt(Integer.reverseBytes(dataSize))
    buffer.close()
}

class BluetoothReducer : Reducer<BluetoothState, BluetoothAction> {
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun reduce(state: BluetoothState, action: BluetoothAction): ReduceResult<BluetoothState, BluetoothAction> =
        when (action) {
            is BluetoothAction.BluetoothInitialised ->
                state.copy(manager = action.manager).withoutEffect()
            is BluetoothAction.BluetoothDebugMessaged ->
                state.copy(message = action.message).withoutEffect()
            is BluetoothAction.ActivityCreated ->
                state.copy(activity = action.activity).withoutEffect()
            is BluetoothAction.ContextCreated ->
                state.copy(context = action.context).withoutEffect()
            is BluetoothAction.DeviceScanned -> {
                val deviceFound = state.devices.find { it.mac == action.mac }
                if (deviceFound != null) {
                    state.withoutEffect()
                } else {
                    state.copy(devices = state.devices + listOf(
                        Device(
                            mac = action.mac ?: "",
                            name = action.name ?: ""
                        )
                    )).withoutEffect()
                }
            }
            is BluetoothAction.DeviceConnected ->
                state.copy(connectedDevice = action.device).withoutEffect()
            BluetoothAction.DeviceDisconnected ->
                state.copy(connectedDevice = null).withoutEffect()
            BluetoothAction.DevicesReset ->
                state.copy(message = "", devices = listOf()).withoutEffect()
            is BluetoothAction.NamedOnlyToggled ->
                state.copy(namedOnly = action.enabled).withoutEffect()
            is BluetoothAction.NoRealgateToggled ->
                state.copy(noRealgate = action.enabled).withoutEffect()
            is BluetoothAction.SoundPlayed -> {
                Log.d(logTag, "BluetoothAction.SoundPlayed ${action.name}")
                val sound = when (action.name) {
                    "PTT_Start" -> R.raw.start_rec
                    else -> R.raw.stop_rec
                }
                val mediaPlayer = MediaPlayer.create(state.context, sound)
                mediaPlayer.start()
                state.withoutEffect()
            }
            is BluetoothAction.AudioManagerInitialised -> {
                state.copy(audioManager = action.audioManager).withoutEffect()
            }
            is BluetoothAction.ScopeInitialised -> {
                state.copy(coroutineScope = action.coroutineScope).withoutEffect()
            }
            BluetoothAction.RecordingToggled -> {
                Log.d(logTag, "ath2 BluetoothAction.RecordingToggled ${state.isRecording}")
                if (state.isRecording) {
                    state.recordingJob?.cancel()

                    state.audioManager?.isBluetoothScoOn = false
                    state.audioManager?.mode = AudioManager.MODE_NORMAL

                    Log.d(logTag, "ath2 play stop sound")
                    state.context?.playSound(R.raw.stop_rec)

                    state.copy(
                        recordingJob = null,
                        isRecording = false
                    ).withoutEffect()
                } else {
                    Log.d(logTag, "ath2 play start sound")
                    state.context?.playSound(R.raw.start_rec) {
//                        startBluetoothSco(state)
                    }
                    state.copy(isRecording = true).withoutEffect()
                }
            }
            is BluetoothAction.RecordingSet -> {
                Log.d(logTag, "BluetoothAction.RecordingSet ")
                if (action.enabled && !state.isRecording) startRecording(state)
                state.copy(isRecording = action.enabled).withoutEffect()
            }
            is BluetoothAction.RecordingStarted -> {
                state.copy(audioRecord = action.audioRecord, recordingJob = action.recordingJob).withoutEffect()
            }
        }
}

fun checkBluetoothPermissions(activity: Activity?) : Boolean {
    if(activity == null) return false

    fun getRequiredPermissions(
        permissionsToCheck: List<String>
    ): MutableList<String> {
        val requiredPermissions = mutableListOf<String>()
        permissionsToCheck.forEach {
            if (ContextCompat.checkSelfPermission(activity, it) !=
                PackageManager.PERMISSION_GRANTED) requiredPermissions.add(it)
        }
        return requiredPermissions
    }

    val permissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.MODIFY_AUDIO_SETTINGS
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permissions += listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )
    val requiredPermissions = getRequiredPermissions(permissions.toList())
    if(requiredPermissions.isEmpty()) return true
    ActivityCompat.requestPermissions(activity, requiredPermissions.toTypedArray(), 1)
    return false
}

fun debugMessage(message: String) {
    bluetoothStore.send(BluetoothAction.BluetoothDebugMessaged(message))
}

val audioDeviceCallback: AudioDeviceCallback = object : AudioDeviceCallback() {
    override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
        Log.d(logTag, "$addedDevices")
    }

    override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
        Log.d(logTag, "$removedDevices")
    }
}

fun startBluetoothSco(
    state: BluetoothState
) {
    Log.d(logTag, "ath2 startBluetoothSco() ${state.audioManager}")
    state.audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
    state.audioManager?.startBluetoothSco()
    state.audioManager?.isBluetoothScoOn = true
}

@RequiresPermission(Manifest.permission.RECORD_AUDIO)
fun startRecording(
    state: BluetoothState
) {
    return
    Log.d(logTag, "ath2 startRecording()")
    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val encoding = AudioFormat.ENCODING_PCM_16BIT
    val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
    val audioRecord = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        sampleRate,
        channelConfig,
        encoding,
        bufferSize
    )

    val outputFile = File(state.context?.cacheDir, "bluetooth_recording_${System.currentTimeMillis()}.wav")

    // Launch coroutine to write raw PCM audio
    val recordingJob = state.coroutineScope?.launch(Dispatchers.IO) {
        val outputStream = BufferedOutputStream(FileOutputStream(outputFile))
        writeWavHeader(outputStream, sampleRate, channelConfig)

        val buffer = ByteArray(bufferSize)
        state.audioRecord?.startRecording()

        while (isActive) {
            val read = state.audioRecord?.read(buffer, 0, buffer.size) ?: 0
            if (read > 0) {
                outputStream.write(buffer, 0, read)
            }
        }

        state.audioRecord?.stop()
        state.audioRecord?.release()
        state.copy(audioRecord = null)
        updateWavHeader(outputFile)
        outputStream.close()
    }

    bluetoothStore.send(BluetoothAction.RecordingStarted(audioRecord, recordingJob!!))
}

fun initAudioManager(
    state: BluetoothState,
    context: Context? = null
) {
    if (context == null) {
        Log.d(logTag, "context is null, cancelling")
        return
    }
    if (state.audioManager != null) return

    val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager
    audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
    bluetoothStore.send(BluetoothAction.AudioManagerInitialised(audioManager))
    val scoReceiver = object : BroadcastReceiver() {
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        override fun onReceive(context: Context?, intent: Intent?) {
//            Log.d(logTag, "ath2 onReceive() triggered ${intent?.action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED} ${intent?.action}")
            if (intent?.action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) {
                val audioState = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
//                Log.d(logTag, "ath2 onReceive() triggered ${audioState == AudioManager.SCO_AUDIO_STATE_CONNECTED} $audioState")
                if (audioState == AudioManager.SCO_AUDIO_STATE_CONNECTED && !state.isRecording) {
                    bluetoothStore.send(BluetoothAction.RecordingSet(true))
                }
            }
        }
    }

    Log.d(logTag, "ath2 Audio manager initialised()")
    context.registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
}

fun initBluetooth(
    state: BluetoothState,
    context: Context? = null
) {
    if (context == null) {
        Log.d(logTag, "context is null, cancelling")
        return
    }
    if (state.manager != null) return debugMessage("Bluetooth already initialised.")
    val bluetoothManager: BluetoothManager = context.getSystemService(BluetoothManager::class.java)
    bluetoothStore.send(BluetoothAction.BluetoothInitialised(bluetoothManager))
    debugMessage("Bluetooth initialised successfully.")
}

private val scanCallback = object : ScanCallback() {
    @SuppressLint("MissingPermission")
    override fun onScanResult(callbackType: Int, result: ScanResult?) {
        super.onScanResult(callbackType, result)

        if (result == null) return

        val parcelUuids = result.scanRecord?.serviceUuids
        parcelUuids?.forEach { Log.d(logTag, "onScanResult() ${result.device.name} ${result.device.address} ${it.uuid}") }

        bluetoothStore.send(BluetoothAction.DeviceScanned(
            name = result.device.name,
            mac = result.device.address,
        ))
    }

    override fun onScanFailed(errorCode: Int) {
        super.onScanFailed(errorCode)

        bluetoothStore.send(BluetoothAction.BluetoothDebugMessaged(
            message = "Scanning failed"
        ))
    }
}

@RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
fun scanDevices(
    state: BluetoothState,
    context: Context? = null,
    scanPeriod: Long = 5000,
) {
    if (context == null) return
    if (state.manager == null) {
        debugMessage("Failed to scan - Bluetooth not initialised.")
        return
    }
    debugMessage("Scanning for devices...")
    val scanner = state.manager.adapter.bluetoothLeScanner
    scanner.startScan(scanCallback)
    Handler(Looper.getMainLooper()).postDelayed({
        debugMessage("Scanning complete.")
        scanner.stopScan(scanCallback)
    }, scanPeriod)
}

private val gattCallback = object : BluetoothGattCallback() {
    @SuppressLint("MissingPermission")
    override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
        super.onConnectionStateChange(gatt, status, newState)

        val device = Device(
            name = gatt?.device?.name,
            mac = gatt?.device?.address,
            gatt = gatt
        )

        when(newState){
            BluetoothProfile.STATE_CONNECTED -> {
                bluetoothStore.send(BluetoothAction.DeviceConnected(device))
                debugMessage("Successfully connected to ${device.name}")
                gatt?.discoverServices()
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                bluetoothStore.send(BluetoothAction.DeviceDisconnected)
                debugMessage("Successfully disconnected from ${device.name}")
            }
            BluetoothProfile.STATE_CONNECTING -> {
                debugMessage("Connecting to ${device.name}")
            }
            BluetoothProfile.STATE_DISCONNECTING -> {
                debugMessage("Disconnecting from ${device.name}")
            }
            else -> {
                TODO()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @SuppressLint("MissingPermission")
    override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
        super.onServicesDiscovered(gatt, status)

        val athUUID = UUID.fromString("b283d606-04fd-11ee-be56-0242ac120002")
        val athCharacteristicUUID = UUID.fromString("b283dc64-04fd-11ee-be56-0242ac120002")

        if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) return

        val service = gatt.getService(athUUID)
        val characteristic = service?.getCharacteristic(athCharacteristicUUID)
        gatt.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic?.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        val enableNotificationValue = byteArrayOf(0x01, 0x00)
        gatt.writeDescriptor(descriptor!!, enableNotificationValue)
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int
    ) {
        Log.d(logTag, "ambie onDescriptorWrite() $gatt $descriptor $status")
    }

    private var lastPayload: ByteArray? = null

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        val current = value
        if (lastPayload?.contentEquals(current) == true) return
        lastPayload = current
        Log.d(logTag, "ath onCharacteristicChanged() $value $characteristic")

        if (value.contentEquals(byteArrayOf(1))) {
            // primary button has been pressed
            Log.d(logTag, "ath value.contentEquals(byteArrayOf(1))")
            bluetoothStore.send(BluetoothAction.RecordingToggled)
        } else if (value.contentEquals(byteArrayOf(0))) {
            // any button has been released
            Log.d(logTag, "ath value.contentEquals(byteArrayOf(0))")
            return
        }
    }
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
fun disconnectDevice(
    state: BluetoothState
) {
    try {
        state.connectedDevice?.gatt?.disconnect()
    } catch (e: Exception) {
        debugMessage("Failed to disconnect from ${state.connectedDevice?.name}, error: $e")
    }
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
fun connectToDevice(
    state: BluetoothState,
    device: Device
) {
    if (device.name == "") {
        debugMessage("Cannot connect to specified device")
        return
    }
    try {
        val bluetoothDevice = state.manager?.adapter?.getRemoteDevice(device.mac)
        bluetoothDevice?.connectGatt(
            state.activity,
            true,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    } catch (e: Exception) {
        debugMessage("Failed to connect to ${device.name}, error: $e")
    }
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
@Composable
fun BluetoothDeviceCard(
    state: BluetoothState,
    device: Device,
    index: Int,
    isConnected: Boolean
) {
    Spacer(modifier = Modifier.size(16.dp))
    Card (
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Row (
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "#${index + 1}",
                modifier = Modifier
                    .padding(16.dp)
            )
            Column (
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("Name: ${device.name}")
                Text("Mac: ${device.mac}")
                Text(if (isConnected) "Connected" else "Not connected")
            }
            Button (
                modifier = Modifier
                    .padding(end = 16.dp),
                onClick = {
                    if (isConnected) disconnectDevice(state)
                    else connectToDevice(state, device)
                },
                content = {
                    Text(if (isConnected) "Disconnect" else "Connect")
                }
            )
        }
    }
}

fun getFilteredDevices(state: BluetoothState): List<Device> {
    return state.devices.filter {
        (!state.namedOnly || it.name != "") &&
        (!state.noRealgate || !it.name!!.lowercase().contains("realgate"))
    }
}

@SuppressLint("MissingPermission")
@Composable
fun BluetoothMain (
    state: BluetoothState,
    modifier: Modifier = Modifier,
    context: Context? = null
) {
    checkBluetoothPermissions(state.activity)
    var deviceList = getFilteredDevices(state)
    val namedDevices = state.devices.filter { it.name != if (state.namedOnly) "" else null }
    val scope = rememberCoroutineScope()
    bluetoothStore.send(BluetoothAction.ScopeInitialised(scope))
    if (state.audioManager == null) initAudioManager(state, context)
    Column (
        modifier = modifier
    ){
        Text("Debug message: ${state.message}")
        Text("Debug devices.size: ${state.devices.size}")
        Text("Debug namedDevices.size: ${namedDevices.size}")
        Text("Debug filtered devices.size: ${deviceList.size}")
        Text("Bluetooth Initialised? ${state.manager != null}")
        Row (
            horizontalArrangement = Arrangement.SpaceBetween
        ){
            Column {
                Text("Device name: ${state.connectedDevice?.name}")
                Text("Device mac: ${state.connectedDevice?.mac}")
            }
            if (state.connectedDevice != null) {
                Button(
                    modifier = Modifier.padding(start = 8.dp),
                    onClick = { disconnectDevice(state) }
                ) {
                    Text("Clear")
                }
            }
        }
        if (state.manager == null) {
            Row {
                Button(
                    onClick = { initBluetooth(
                        state = state,
                        context = context
                    ) }
                ) {
                    Text("Initialise Bluetooth")
                }
            }
        } else {
            Row {
                Button(
                    onClick = { scanDevices(
                        context = context,
                        state = state
                    ) }
                ) {
                    Text("Scan")
                }
                Button(
                    modifier = Modifier.padding(start = 8.dp),
                    onClick = {
                        state.manager.adapter.bluetoothLeScanner?.stopScan(scanCallback)
                        bluetoothStore.send(BluetoothAction.DevicesReset)
                    }
                ) {
                    Text("Reset")
                }
            }
            Row {
                Button(
                    onClick = {
                        connectToDevice(
                            state,
                            device = Device(
                                name="Audio Technica",
                                mac="30:53:C1:7D:26:07"
                            )
                        )
                    }
                ) {
                    Text("Audio Technica")
                }
            }
        }
        Row (
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Named devices only")
            Switch(
                modifier = Modifier
                    .padding(start = 8.dp),
                checked = state.namedOnly,
                onCheckedChange = {
                    bluetoothStore.send(BluetoothAction.NamedOnlyToggled(it))
                }
            )
        }
        Row (
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Filter out REALGATE")
            Switch(
                modifier = Modifier
                    .padding(start = 8.dp),
                checked = state.noRealgate,
                onCheckedChange = {
                    bluetoothStore.send(BluetoothAction.NoRealgateToggled(it))
                }
            )
        }
        LazyColumn(modifier = Modifier.padding(start = 6.dp)) {
            for ((index, device) in deviceList.withIndex()) {
                item(key = device.mac) {
                    BluetoothDeviceCard(
                        state = state,
                        device = device,
                        index = index,
                        isConnected = device.mac == state.connectedDevice?.mac
                    )
                }
            }
        }
    }
}