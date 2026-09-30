package com.xrch.companion.data

import org.json.JSONObject
import java.util.Locale

enum class CompanionLocationMode(val label: String) {
    REAL("실제 GPS"),
    VIRTUAL("가상 GPS");

    companion object {
        fun fromLabel(label: String): CompanionLocationMode =
            entries.firstOrNull { it.label == label } ?: REAL
    }
}

data class LocationPacket(
    val type: String = "arch.location.v1",
    val source: String, // "real" or "virtual"
    val sequence: Long,
    val timestamp: Double,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val horizontalAccuracy: Double,
    val trueHeading: Double? = null,
    val headingAccuracy: Double? = null,
    val headingCalibration: Boolean? = null,
    val headingSource: String? = null
) {
    fun toJson(pretty: Boolean = false): String {
        val json = JSONObject()
        json.put("type", type)
        json.put("source", source)
        json.put("sequence", sequence)
        json.put("timestamp", timestamp)
        json.put("latitude", latitude)
        json.put("longitude", longitude)
        json.put("altitude", altitude)
        json.put("horizontalAccuracy", horizontalAccuracy)

        if (trueHeading != null) json.put("trueHeading", trueHeading)
        if (headingAccuracy != null) json.put("headingAccuracy", headingAccuracy)
        if (headingCalibration != null) json.put("headingCalibration", headingCalibration)
        if (headingSource != null) json.put("headingSource", headingSource)

        return if (pretty) json.toString(2) else json.toString()
    }
}

data class CompanionPOI(
    val id: String,
    val name: String,
    val category: String,
    val address: String,
    val summary: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Int,
    val rating: Double
)

data class BridgeState(
    val statusText: String = "대기 중",
    val connectionStateText: String = "연결 안 됨",
    val isRunning: Boolean = false,
    val latitudeText: String = "-",
    val longitudeText: String = "-",
    val altitudeText: String = "-",
    val accuracyText: String = "-",
    val headingText: String = "-",
    val headingDegrees: Double = -1.0,
    val headingAccuracyText: String = "-",
    val headingCalibrationModeActive: Boolean = false,
    val locationSourceText: String = "위치 없음",
    val locationTimestampText: String = "-",
    val locationAgeText: String = "-",
    val authorizationText: String = "확인 중",
    val networkPathText: String = "확인 중",
    val endpointText: String = "-",
    val bleDeviceText: String = "검색 전",
    val bleRSSIText: String = "-",
    val sentCount: Long = 0,
    val lastSentAgoText: String = "-",
    val lastPacketSizeText: String = "-",
    val lastPacketText: String = "아직 송신한 패킷이 없습니다.",
    val events: List<String> = emptyList(),
    val locationMode: CompanionLocationMode = CompanionLocationMode.REAL,
    val virtualLatitude: String = "37.3017362",
    val virtualLongitude: String = "126.8385608",
    val questHost: String = "192.168.1.100"
)
