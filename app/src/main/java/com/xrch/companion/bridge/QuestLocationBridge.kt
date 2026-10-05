package com.xrch.companion.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import com.xrch.companion.data.AiRecommendationItem
import com.xrch.companion.data.AiRecommendationPacket
import com.xrch.companion.data.BridgeState
import com.xrch.companion.data.CompanionLocationMode
import com.xrch.companion.data.CompanionPOI
import com.xrch.companion.data.LocationPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@SuppressLint("MissingPermission")
class QuestLocationBridge(private val context: Context) : LocationListener, SensorEventListener {

    companion object {
        const val PORT: Int = 47777
        val BLE_SERVICE_UUID: UUID = UUID.fromString("A6C40001-7D2A-4B2E-9E31-8A6F310A1201")
        val BLE_LOCATION_UUID: UUID = UUID.fromString("A6C40002-7D2A-4B2E-9E31-8A6F310A1201")
        private val TIME_FORMATTER = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(BridgeState())
    val state: StateFlow<BridgeState> = _state.asStateFlow()

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var bluetoothGatt: BluetoothGatt? = null
    private var locationCharacteristic: BluetoothGattCharacteristic? = null
    private var bleScanner: BluetoothLeScanner? = null

    private var latestLocation: Location? = null
    private var latestHeadingDegrees: Double = -1.0
    private var latestHeadingAccuracy: Double = -1.0
    private var sequence: Long = 0
    private var lastSentAt: Date? = null
    private var currentMtu: Int = 20 // Default ATT MTU payload is 23 - 3 = 20 bytes
    private var usesBLE: Boolean = true

    private var virtualTimerJob: Job? = null
    private var isScanningBLE: Boolean = false

    // Sensor calculation buffers
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private var hasAccelerometer = false
    private var hasMagnetometer = false

    init {
        startNetworkMonitoring()
        startSensorListeners()
        startRelativeTimeTicker()
        addEvent("진단 화면 시작")
    }

    // --- Public API ---

    fun setLocationMode(mode: CompanionLocationMode) {
        _state.update { it.copy(locationMode = mode) }
        applyLocationMode()
    }

    fun setVirtualLocation(lat: Double, lon: Double) {
        val latStr = String.format(Locale.US, "%.7f", lat)
        val lonStr = String.format(Locale.US, "%.7f", lon)
        _state.update {
            it.copy(
                virtualLatitude = latStr,
                virtualLongitude = lonStr
            )
        }

        if (_state.value.isRunning && _state.value.locationMode == CompanionLocationMode.VIRTUAL) {
            val loc = getSelectedLocation() ?: return
            updateLocationDisplay(loc, packetSourceLabel)
            if (usesBLE) sendBLE(loc) else sendUDP(loc)
        }
    }

    fun setQuestHost(host: String) {
        _state.update { it.copy(questHost = host.trim()) }
    }

    fun startBLE() {
        usesBLE = true
        _state.update {
            it.copy(
                isRunning = true,
                connectionStateText = "Quest 검색 중",
                statusText = "ARCH Quest BLE 검색 중",
                bleDeviceText = "주변 Quest 검색 중"
            )
        }
        applyLocationMode()
        startBLEScan()
        addEvent("BLE 위치 전송 시작")
    }

    fun startUDP(host: String) {
        usesBLE = false
        val trimmed = host.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(statusText = "Quest IP를 입력하세요") }
            addEvent("연결 실패 · Quest IP 없음")
            return
        }

        _state.update {
            it.copy(
                questHost = trimmed,
                isRunning = true,
                connectionStateText = "준비됨",
                endpointText = "$trimmed:$PORT / UDP",
                statusText = "전송 준비됨: $trimmed"
            )
        }
        applyLocationMode()
        addEvent("UDP 전송 시작 · $trimmed:$PORT")
    }

    fun stop() {
        stopBLEScan()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        locationCharacteristic = null

        virtualTimerJob?.cancel()
        virtualTimerJob = null

        try {
            locationManager.removeUpdates(this)
        } catch (_: SecurityException) {}

        _state.update {
            it.copy(
                isRunning = false,
                headingCalibrationModeActive = false,
                connectionStateText = "연결 안 됨",
                statusText = "전송 중지됨"
            )
        }
        addEvent("GPS 전송 중지")
    }

    fun sendCurrentOrTestLocation() {
        val location = getSelectedLocation() ?: return
        updateLocationDisplay(location, packetSourceLabel)
        if (usesBLE) {
            sendBLE(location)
        } else {
            sendUDP(location)
        }
    }

    fun getLatestLocation(): Location? = latestLocation

    fun sendAiRecommendationsOnQuest(pois: List<CompanionPOI>) {
        if (!_state.value.isRunning) {
            _state.update { it.copy(statusText = "먼저 Quest 연결을 시작하세요") }
            addEvent("AI 추천 전송 실패 · Quest 연결 없음")
            return
        }

        val selected = pois.take(10)
        if (selected.isEmpty()) {
            _state.update { it.copy(statusText = "전송할 AI 추천 장소가 없습니다") }
            addEvent("AI 추천 전송 실패 · 추천 장소 없음")
            return
        }

        sequence += 1
        val currentSeq = sequence
        val packet = AiRecommendationPacket(
            type = "arch.ai_recommendations.v1",
            source = "android",
            sequence = currentSeq,
            timestamp = System.currentTimeMillis() / 1000.0,
            clearDebugRecommendations = true,
            recommendations = selected.mapIndexed { index, poi ->
                AiRecommendationItem(
                    poiId = poi.id,
                    name = poi.name,
                    category = poi.category,
                    address = poi.address,
                    description = poi.summary,
                    latitude = poi.latitude,
                    longitude = poi.longitude,
                    distanceMeters = maxOf(0, poi.distanceMeters).toFloat(),
                    score = maxOf(0.7f, minOf(1.0f, 0.96f - index * 0.04f)),
                    reason = poi.summary
                )
            }
        )

        val wireData = packet.toJson().toByteArray(Charsets.UTF_8)
        val prettyJson = packet.toJson(pretty = true)
        _state.update {
            it.copy(
                lastPacketText = prettyJson,
                lastPacketSizeText = "${wireData.size} B"
            )
        }

        if (usesBLE) {
            sendAiRecommendationsBLE(wireData, currentSeq, selected.size)
        } else {
            sendAiRecommendationsUDP(wireData, currentSeq, selected.size)
        }
    }

    fun calibrateQuestHeading() {
        if (!_state.value.isRunning) {
            _state.update { it.copy(statusText = "먼저 Quest GPS 연결을 시작하세요") }
            addEvent("방향 보정 실패 · Quest 연결 없음")
            return
        }
        if (latestHeadingDegrees < 0) {
            _state.update { it.copy(statusText = "스마트폰 방향을 아직 읽지 못했습니다") }
            addEvent("방향 보정 실패 · heading 없음")
            return
        }
        val loc = getSelectedLocation()
        if (loc == null) {
            _state.update { it.copy(statusText = "보정에 사용할 위치가 없습니다") }
            addEvent("방향 보정 실패 · 위치 없음")
            return
        }

        if (usesBLE) {
            sendBLE(loc, calibration = true)
        } else {
            sendUDP(loc, headingCalibration = true)
        }
    }

    fun startHeadingCalibrationMode() {
        if (!_state.value.isRunning) {
            _state.update { it.copy(statusText = "먼저 Quest GPS 연결을 시작하세요") }
            return
        }
        if (latestHeadingDegrees < 0) {
            _state.update { it.copy(statusText = "스마트폰 방향을 아직 읽지 못했습니다") }
            return
        }

        _state.update { it.copy(headingCalibrationModeActive = true) }
        addEvent("실시간 헤딩 보정 시작")
        sendHeadingUpdate()
    }

    fun stopHeadingCalibrationMode() {
        if (!_state.value.headingCalibrationModeActive) return
        _state.update { it.copy(headingCalibrationModeActive = false) }
        addEvent("실시간 헤딩 보정 중지")
    }

    fun clearEvents() {
        _state.update { it.copy(events = emptyList()) }
        addEvent("로그 초기화")
    }

    // --- Private Engine Logic ---

    private val packetSource: String
        get() = if (_state.value.locationMode == CompanionLocationMode.REAL) "real" else "virtual"

    private val packetSourceLabel: String
        get() = if (_state.value.locationMode == CompanionLocationMode.REAL) "Android GPS" else "가상 GPS"

    private fun applyLocationMode() {
        if (!_state.value.isRunning) return

        if (_state.value.locationMode == CompanionLocationMode.REAL) {
            virtualTimerJob?.cancel()
            virtualTimerJob = null
            startLocationUpdates()
            addEvent("실제 GPS 모드")
        } else {
            try {
                locationManager.removeUpdates(this)
            } catch (_: SecurityException) {}

            startVirtualTimer()
            val loc = getSelectedLocation()
            if (loc != null) {
                updateLocationDisplay(loc, packetSourceLabel)
                if (usesBLE) sendBLE(loc) else sendUDP(loc)
            }
            addEvent("가상 GPS 모드")
        }
    }

    private fun startVirtualTimer() {
        virtualTimerJob?.cancel()
        virtualTimerJob = scope.launch {
            while (isActive) {
                delay(1000)
                if (_state.value.isRunning && _state.value.locationMode == CompanionLocationMode.VIRTUAL) {
                    val loc = getSelectedLocation()
                    if (loc != null) {
                        updateLocationDisplay(loc, packetSourceLabel)
                        if (usesBLE) sendBLE(loc) else sendUDP(loc)
                    }
                }
            }
        }
    }

    private fun getSelectedLocation(): Location? {
        if (_state.value.locationMode == CompanionLocationMode.REAL) {
            val loc = latestLocation
            if (loc == null) {
                _state.update { it.copy(statusText = "실제 GPS 수신을 기다리는 중") }
                return null
            }
            return loc
        }

        val lat = _state.value.virtualLatitude.toDoubleOrNull()
        val lon = _state.value.virtualLongitude.toDoubleOrNull()
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            _state.update { it.copy(statusText = "가상 GPS 좌표를 확인하세요") }
            return null
        }

        return Location("virtual").apply {
            latitude = lat
            longitude = lon
            altitude = 0.0
            accuracy = 1.0f
            time = System.currentTimeMillis()
        }
    }

    private fun startLocationUpdates() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    1.0f,
                    this
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L,
                    2.0f,
                    this
                )
            }
            val lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (lastKnown != null) {
                onLocationChanged(lastKnown)
            }
        } catch (e: SecurityException) {
            _state.update { it.copy(statusText = "위치 권한 필요", authorizationText = "권한 없음") }
            addEvent("위치 권한 오류 · ${e.localizedMessage}")
        }
    }

    // --- Transmission ---

    private fun sendUDP(location: Location, headingCalibration: Boolean = false) {
        val host = _state.value.questHost
        if (host.isEmpty()) {
            _state.update { it.copy(statusText = "Quest IP를 입력하세요") }
            return
        }

        sequence += 1
        val currentSeq = sequence
        val packet = LocationPacket(
            type = "arch.location.v1",
            source = packetSource,
            sequence = currentSeq,
            timestamp = (if (headingCalibration) System.currentTimeMillis() else location.time) / 1000.0,
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.altitude,
            horizontalAccuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 1.0,
            trueHeading = if (headingCalibration) latestHeadingDegrees else null,
            headingAccuracy = if (headingCalibration) latestHeadingAccuracy else null,
            headingCalibration = if (headingCalibration) true else null,
            headingSource = if (headingCalibration) "android_compass_live" else null
        )

        val wireData = packet.toJson().toByteArray(Charsets.UTF_8)
        val prettyJson = packet.toJson(pretty = true)

        _state.update {
            it.copy(
                lastPacketText = prettyJson,
                lastPacketSizeText = "${wireData.size} B"
            )
        }

        scope.launch(Dispatchers.IO) {
            try {
                DatagramSocket().use { socket ->
                    val address = InetAddress.getByName(host)
                    val datagram = DatagramPacket(wireData, wireData.size, address, PORT)
                    socket.send(datagram)
                }
                withContext(Dispatchers.Main) {
                    lastSentAt = Date()
                    _state.update {
                        it.copy(
                            sentCount = it.sentCount + 1,
                            lastSentAgoText = "방금",
                            statusText = "#$currentSeq UDP 전송 완료"
                        )
                    }
                    addEvent("#$currentSeq UDP 전송 완료")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(statusText = "전송 실패: ${e.localizedMessage}") }
                    addEvent("#$currentSeq 전송 실패 · ${e.localizedMessage}")
                }
            }
        }
    }

    private fun sendBLE(location: Location, calibration: Boolean = false) {
        val gatt = bluetoothGatt
        val char = locationCharacteristic
        if (gatt == null || char == null) {
            _state.update { it.copy(statusText = "Quest BLE 연결을 기다리는 중") }
            return
        }

        sequence += 1
        val currentSeq = sequence
        val packet = LocationPacket(
            type = "arch.location.v1",
            source = packetSource,
            sequence = currentSeq,
            timestamp = (if (calibration) System.currentTimeMillis() else location.time) / 1000.0,
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.altitude,
            horizontalAccuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 1.0,
            trueHeading = if (calibration) latestHeadingDegrees else null,
            headingAccuracy = if (calibration) latestHeadingAccuracy else null,
            headingCalibration = if (calibration) true else null,
            headingSource = if (calibration) "android_compass" else null
        )

        val prettyJson = packet.toJson(pretty = true)
        val rawBytes = packet.toJson().toByteArray(Charsets.UTF_8)
        val wireData = ByteArray(rawBytes.size + 1).apply {
            System.arraycopy(rawBytes, 0, this, 0, rawBytes.size)
            this[rawBytes.size] = 0x0A // '\n' terminator
        }

        _state.update {
            it.copy(
                lastPacketText = prettyJson,
                lastPacketSizeText = "${wireData.size} B"
            )
        }

        scope.launch(Dispatchers.IO) {
            try {
                val chunkSize = maxOf(20, currentMtu)
                var offset = 0
                while (offset < wireData.size) {
                    val end = minOf(offset + chunkSize, wireData.size)
                    val chunk = wireData.copyOfRange(offset, end)

                    writeCharacteristic(gatt, char, chunk)
                    offset = end
                    if (offset < wireData.size) delay(8)
                }

                withContext(Dispatchers.Main) {
                    lastSentAt = Date()
                    val status = if (calibration) {
                        "#$currentSeq 초기 방향 보정 전송 완료"
                    } else {
                        "#$currentSeq BLE 전송 완료"
                    }
                    _state.update {
                        it.copy(
                            sentCount = it.sentCount + 1,
                            lastSentAgoText = "방금",
                            statusText = status
                        )
                    }
                    addEvent(
                        if (calibration) {
                            "#$currentSeq 초기 방향 보정 · ${String.format(Locale.US, "%.1f°", latestHeadingDegrees)}"
                        } else {
                            "#$currentSeq BLE 전송 · ${wireData.size} B"
                        }
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(statusText = "BLE 전송 오류: ${e.localizedMessage}") }
                    addEvent("BLE 전송 실패 · ${e.localizedMessage}")
                }
            }
        }
    }

    private fun sendAiRecommendationsUDP(wireData: ByteArray, currentSeq: Long, count: Int) {
        val host = _state.value.questHost
        if (host.isEmpty()) {
            _state.update { it.copy(statusText = "UDP 연결이 없습니다") }
            addEvent("AI 추천 전송 실패 · UDP 연결 없음")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                DatagramSocket().use { socket ->
                    val address = InetAddress.getByName(host)
                    val datagram = DatagramPacket(wireData, wireData.size, address, PORT)
                    socket.send(datagram)
                }
                withContext(Dispatchers.Main) {
                    lastSentAt = Date()
                    _state.update {
                        it.copy(
                            sentCount = it.sentCount + 1,
                            lastSentAgoText = "방금",
                            statusText = "AI 추천 ${count}곳을 Quest에 표시"
                        )
                    }
                    addEvent("#$currentSeq AI 추천 ${count}곳 전송 완료")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(statusText = "AI 추천 전송 실패: ${e.localizedMessage}") }
                    addEvent("AI 추천 전송 실패 · ${e.localizedMessage}")
                }
            }
        }
    }

    private fun sendAiRecommendationsBLE(rawBytes: ByteArray, currentSeq: Long, count: Int) {
        val gatt = bluetoothGatt
        val char = locationCharacteristic
        if (gatt == null || char == null) {
            _state.update { it.copy(statusText = "Quest BLE 연결을 기다리는 중") }
            addEvent("AI 추천 전송 보류 · Quest 미연결")
            return
        }

        val wireData = ByteArray(rawBytes.size + 1).apply {
            System.arraycopy(rawBytes, 0, this, 0, rawBytes.size)
            this[rawBytes.size] = 0x0A // '\n' terminator
        }

        scope.launch(Dispatchers.IO) {
            try {
                val chunkSize = maxOf(20, currentMtu)
                var offset = 0
                while (offset < wireData.size) {
                    val end = minOf(offset + chunkSize, wireData.size)
                    val chunk = wireData.copyOfRange(offset, end)
                    writeCharacteristic(gatt, char, chunk)
                    offset = end
                    if (offset < wireData.size) delay(8)
                }

                withContext(Dispatchers.Main) {
                    lastSentAt = Date()
                    _state.update {
                        it.copy(
                            sentCount = it.sentCount + 1,
                            lastSentAgoText = "방금",
                            statusText = "AI 추천 ${count}곳을 Quest에 표시"
                        )
                    }
                    addEvent("#$currentSeq BLE AI 추천 전송 완료")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(statusText = "AI 추천 전송 실패: ${e.localizedMessage}") }
                    addEvent("AI 추천 전송 실패 · ${e.localizedMessage}")
                }
            }
        }
    }

    private fun writeCharacteristic(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                char,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
        } else {
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            char.value = value
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    private fun sendHeadingUpdate() {
        if (!_state.value.headingCalibrationModeActive || !_state.value.isRunning || latestHeadingDegrees < 0) return
        val loc = getSelectedLocation() ?: return
        if (usesBLE) {
            sendBLE(loc, calibration = true)
        } else {
            sendUDP(loc, headingCalibration = true)
        }
    }

    // --- BLE Scan & GATT Callback ---

    private fun startBLEScan() {
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _state.update {
                it.copy(
                    connectionStateText = "Bluetooth 꺼짐",
                    statusText = "Bluetooth를 켜주세요"
                )
            }
            return
        }

        bleScanner = adapter.bluetoothLeScanner
        if (bleScanner == null) {
            _state.update { it.copy(statusText = "BLE Scanner를 사용할 수 없습니다") }
            return
        }

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BLE_SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        isScanningBLE = true
        addEvent("BLE 스캔 시작")
        bleScanner?.startScan(filters, settings, scanCallback)
    }

    private fun stopBLEScan() {
        if (isScanningBLE) {
            try {
                bleScanner?.stopScan(scanCallback)
            } catch (_: Exception) {}
            isScanningBLE = false
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val name = device.name ?: result.scanRecord?.deviceName ?: "ARCH Quest"
            val rssi = result.rssi

            stopBLEScan()
            _state.update {
                it.copy(
                    bleDeviceText = name,
                    bleRSSIText = "$rssi dBm",
                    connectionStateText = "연결 중",
                    statusText = "$name 에 연결 중"
                )
            }
            addEvent("BLE 발견 · $name · $rssi dBm")

            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _state.update {
                it.copy(
                    connectionStateText = "실패",
                    statusText = "스캔 실패: 코드 $errorCode"
                )
            }
            addEvent("BLE 스캔 실패 · 코드 $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.update {
                        it.copy(
                            connectionStateText = "서비스 확인 중",
                            statusText = "Quest BLE 서비스 검색 중"
                        )
                    }
                    addEvent("BLE 연결됨 · MTU 요청 및 서비스 검색")
                    gatt?.requestMtu(512)
                    gatt?.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    locationCharacteristic = null
                    bluetoothGatt = null
                    _state.update {
                        it.copy(
                            connectionStateText = "연결 끊김",
                            statusText = "Quest BLE 연결 끊김"
                        )
                    }
                    addEvent("BLE 연결 끊김")
                    if (_state.value.isRunning && usesBLE) {
                        startBLEScan()
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentMtu = maxOf(20, mtu - 3)
                addEvent("BLE MTU 협상 완료 · $mtu (가용 $currentMtu B)")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                _state.update { it.copy(statusText = "서비스 검색 실패: $status") }
                return
            }

            val service = gatt.getService(BLE_SERVICE_UUID)
            if (service == null) {
                _state.update { it.copy(statusText = "ARCH BLE 서비스를 찾지 못함") }
                return
            }

            val char = service.getCharacteristic(BLE_LOCATION_UUID)
            if (char == null) {
                _state.update { it.copy(statusText = "위치 Characteristic을 찾지 못함") }
                return
            }

            locationCharacteristic = char
            _state.update {
                it.copy(
                    connectionStateText = "준비됨",
                    endpointText = "BLE · $BLE_SERVICE_UUID",
                    statusText = "Quest BLE 전송 준비됨"
                )
            }
            addEvent("BLE 위치 채널 준비 완료")
        }
    }

    // --- LocationListener ---

    override fun onLocationChanged(location: Location) {
        latestLocation = location
        if (_state.value.locationMode != CompanionLocationMode.REAL) return

        updateLocationDisplay(location, packetSourceLabel)
        if (_state.value.isRunning) {
            if (usesBLE) sendBLE(location) else sendUDP(location)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) { addEvent("위치 프로바이더 활성화: $provider") }
    override fun onProviderDisabled(provider: String) { addEvent("위치 프로바이더 비활성화: $provider") }

    private fun updateLocationDisplay(loc: Location, source: String) {
        _state.update {
            it.copy(
                latitudeText = String.format(Locale.US, "%.7f", loc.latitude),
                longitudeText = String.format(Locale.US, "%.7f", loc.longitude),
                altitudeText = String.format(Locale.US, "%.1f m", loc.altitude),
                accuracyText = if (loc.hasAccuracy()) String.format(Locale.US, "%.1f m", loc.accuracy) else "테스트 값",
                locationSourceText = source,
                locationTimestampText = TIME_FORMATTER.format(Date(loc.time))
            )
        }
    }

    // --- Compass / SensorEventListener ---

    private fun startSensorListeners() {
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            // Fallback to accelerometer + magnetometer
            val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val magnet = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            if (accel != null) sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
            if (magnet != null) sensorManager.registerListener(this, magnet, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                val azimuthRad = orientationAngles[0]
                var azimuthDeg = Math.toDegrees(azimuthRad.toDouble())
                if (azimuthDeg < 0) azimuthDeg += 360.0

                latestHeadingDegrees = azimuthDeg
                latestHeadingAccuracy = 5.0 // Estimated accuracy

                _state.update {
                    it.copy(
                        headingDegrees = azimuthDeg,
                        headingText = String.format(Locale.US, "%.1f°", azimuthDeg),
                        headingAccuracyText = "±5.0°"
                    )
                }
                sendHeadingUpdate()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accelerometerReading, 0, 3)
                hasAccelerometer = true
                computeOrientationFromSensors()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magnetometerReading, 0, 3)
                hasMagnetometer = true
                computeOrientationFromSensors()
            }
        }
    }

    private fun computeOrientationFromSensors() {
        if (!hasAccelerometer || !hasMagnetometer) return
        val success = SensorManager.getRotationMatrix(
            rotationMatrix,
            null,
            accelerometerReading,
            magnetometerReading
        )
        if (success) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            var azimuthDeg = Math.toDegrees(orientationAngles[0].toDouble())
            if (azimuthDeg < 0) azimuthDeg += 360.0

            latestHeadingDegrees = azimuthDeg
            latestHeadingAccuracy = 10.0

            _state.update {
                it.copy(
                    headingDegrees = azimuthDeg,
                    headingText = String.format(Locale.US, "%.1f°", azimuthDeg),
                    headingAccuracyText = "±10.0°"
                )
            }
            sendHeadingUpdate()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --- Network & Relative Time ---

    private fun startNetworkMonitoring() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateNetworkStatus()
            }
            override fun onLost(network: Network) {
                _state.update { it.copy(networkPathText = "오프라인") }
            }
        })
        updateNetworkStatus()
    }

    private fun updateNetworkStatus() {
        val active = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(active)
        val text = when {
            caps == null -> "오프라인"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi 연결됨"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "셀룰러 연결됨"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "유선 연결됨"
            else -> "네트워크 연결됨"
        }
        _state.update { it.copy(networkPathText = text) }
    }

    private fun startRelativeTimeTicker() {
        scope.launch {
            while (isActive) {
                delay(1000)
                val lastLoc = latestLocation
                val locAge = if (lastLoc != null) {
                    relativeAge(Date(lastLoc.time))
                } else if (_state.value.locationSourceText != "위치 없음") {
                    "방금"
                } else {
                    "-"
                }

                val sentAgo = lastSentAt?.let { relativeAge(it) } ?: "-"

                _state.update {
                    it.copy(
                        locationAgeText = locAge,
                        lastSentAgoText = sentAgo
                    )
                }
            }
        }
    }

    private fun addEvent(message: String) {
        val formatted = "[${TIME_FORMATTER.format(Date())}] $message"
        _state.update {
            val list = mutableListOf(formatted).apply {
                addAll(it.events)
            }
            it.copy(events = list.take(12))
        }
    }

    private fun relativeAge(date: Date): String {
        val seconds = maxOf(0, ((System.currentTimeMillis() - date.time) / 1000).toInt())
        return when {
            seconds < 2 -> "방금"
            seconds < 60 -> "${seconds}초 전"
            else -> "${seconds / 60}분 전"
        }
    }
}
