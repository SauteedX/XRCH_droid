package com.xrch.companion.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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

data class AiRecommendationPacket(
    val type: String = "arch.ai_recommendations.v1",
    val source: String = "android",
    val sequence: Long,
    val timestamp: Double,
    val clearDebugRecommendations: Boolean = true,
    val recommendations: List<AiRecommendationItem>
) {
    fun toJson(pretty: Boolean = false): String {
        val json = JSONObject()
        json.put("type", type)
        json.put("source", source)
        json.put("sequence", sequence)
        json.put("timestamp", timestamp)
        json.put("clearDebugRecommendations", clearDebugRecommendations)
        val array = JSONArray()
        for (item in recommendations) {
            array.put(item.toJSONObject())
        }
        json.put("recommendations", array)
        return if (pretty) json.toString(2) else json.toString()
    }
}

data class AiRecommendationItem(
    val poiId: String,
    val name: String,
    val category: String,
    val address: String,
    val description: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Float,
    val score: Float,
    val reason: String
) {
    fun toJSONObject(): JSONObject {
        val json = JSONObject()
        json.put("poiId", poiId)
        json.put("name", name)
        json.put("category", category)
        json.put("address", address)
        json.put("description", description)
        json.put("latitude", latitude)
        json.put("longitude", longitude)
        json.put("distanceMeters", distanceMeters.toDouble())
        json.put("score", score.toDouble())
        json.put("reason", reason)
        return json
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

enum class MessageRole {
    USER, ASSISTANT
}

data class RecommendationMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val text: String,
    val places: List<CompanionPOI> = emptyList()
)

data class CompanionUsage(
    val remaining: Int?,
    val resetAt: String,
    val isAnonymous: Boolean
) {
    val summary: String
        get() = if (isAnonymous) "오늘 무료 AI 추천 ${remaining ?: 0}회 남음" else "계정으로 이용 중"

    val resetDateFormatted: String?
        get() {
            return try {
                // ISO8601 parsing fallback
                val clean = resetAt.replace("Z", "+0000")
                val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                val date = parser.parse(clean) ?: return null
                val display = SimpleDateFormat("M월 d일 a h:mm", Locale.KOREA)
                display.format(date)
            } catch (_: Exception) {
                null
            }
        }

    companion object {
        fun fromHeaders(getHeader: (String) -> String?): CompanionUsage? {
            val remainingHeader = getHeader("X-GPU-Quota-Remaining") ?: return null
            val resetHeader = getHeader("X-GPU-Quota-Reset-At") ?: return null
            val anonHeader = getHeader("X-GPU-Quota-Anonymous") ?: return null
            if (anonHeader != "true" && anonHeader != "false") return null

            val remInt = if (remainingHeader == "unlimited") null else remainingHeader.toIntOrNull()
            return CompanionUsage(
                remaining = remInt,
                resetAt = resetHeader,
                isAnonymous = anonHeader == "true"
            )
        }
    }
}

data class StoredCompanionSession(
    val userId: String,
    val email: String?,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val isAnonymous: Boolean
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("userId", userId)
        if (email != null) json.put("email", email)
        json.put("accessToken", accessToken)
        json.put("refreshToken", refreshToken)
        json.put("expiresAtMillis", expiresAtMillis)
        json.put("isAnonymous", isAnonymous)
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): StoredCompanionSession? {
            return try {
                val json = JSONObject(jsonStr)
                StoredCompanionSession(
                    userId = json.getString("userId"),
                    email = if (json.has("email") && !json.isNull("email")) json.getString("email") else null,
                    accessToken = json.getString("accessToken"),
                    refreshToken = json.getString("refreshToken"),
                    expiresAtMillis = json.getLong("expiresAtMillis"),
                    isAnonymous = json.optBoolean("isAnonymous", false)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

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
