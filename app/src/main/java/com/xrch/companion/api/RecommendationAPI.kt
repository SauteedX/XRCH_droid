package com.xrch.companion.api

import android.location.Location
import com.xrch.companion.auth.CompanionAuthSession
import com.xrch.companion.auth.SupabaseConfiguration
import com.xrch.companion.data.CompanionPOI
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class RecommendationResult(
    val answer: String,
    val places: List<CompanionPOI>,
    val model: String,
    val gpuUsed: Boolean
)

object RecommendationAPI {

    private const val ENDPOINT = "${SupabaseConfiguration.FUNCTIONS_URL}/chat"

    suspend fun recommend(
        question: String,
        latitude: Double,
        longitude: Double,
        radius: Int,
        auth: CompanionAuthSession,
        previousPlaces: List<CompanionPOI>
    ): RecommendationResult {
        val reqJson = JSONObject().apply {
            put("question", question)
            put("lat", latitude)
            put("lng", longitude)
            put("searchRadiusMeters", radius)
            put("userLanguageCode", "ko")
            put("engine", "gpu")

            val placesArray = JSONArray()
            previousPlaces.take(15).forEach { poi ->
                val p = JSONObject().apply {
                    put("id", poi.id)
                    put("name", poi.name)
                    put("category", poi.category)
                    put("address", poi.address)
                    put("lat", poi.latitude)
                    put("lng", poi.longitude)
                    put("distanceMeters", poi.distanceMeters)
                }
                placesArray.put(p)
            }
            put("places", placesArray)

            val recentIdsArray = JSONArray()
            previousPlaces.take(3).forEach { recentIdsArray.put(it.id) }
            put("recent_recommended_ids", recentIdsArray)
        }

        val response = auth.authenticatedRequest(
            url = ENDPOINT,
            method = "POST",
            jsonBody = reqJson.toString()
        )

        val obj = try {
            JSONObject(response.body)
        } catch (e: Exception) {
            throw IOException("추천 응답을 읽을 수 없습니다. (HTTP ${response.statusCode})")
        }

        val success = obj.optBoolean("success", false)
        if (response.statusCode !in 200..299 || !success) {
            val code = obj.optString("code")
            if (response.statusCode == 429 && code == "DAILY_QUOTA_EXCEEDED") {
                val reset = auth.usage.value?.resetDateFormatted?.let { " ${it}부터 다시 사용할 수 있습니다." } ?: ""
                throw IOException("오늘의 무료 AI 추천을 모두 사용했습니다.$reset 검색·지도는 계속 이용할 수 있습니다. 계정 로그인은 기타 탭에서 할 수 있습니다.")
            }
            val message = obj.optString("message", "추천 서버가 응답하지 않았습니다. (HTTP ${response.statusCode})")
            throw IOException(message)
        }

        val answer = obj.optString("answer", "").trim()
        val primaryArray = obj.optJSONArray("top_places") ?: JSONArray()
        val allArray = obj.optJSONArray("places") ?: JSONArray()

        val seen = mutableSetOf<String>()
        val places = mutableListOf<CompanionPOI>()

        fun processArray(arr: JSONArray) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val poi = parsePOI(item, latitude, longitude) ?: continue
                if (seen.add(poi.id)) {
                    places.add(poi)
                }
            }
        }

        processArray(primaryArray)
        processArray(allArray)

        val model = obj.optString("model", "알 수 없음")
        val gpuUsed = obj.optJSONObject("gpuOrchestration")?.optBoolean("used", false) ?: false

        return RecommendationResult(
            answer = if (answer.isEmpty()) "추천 결과를 확인해 주세요." else answer,
            places = places,
            model = model,
            gpuUsed = gpuUsed
        )
    }

    suspend fun probe(auth: CompanionAuthSession): String {
        val reqBody = JSONObject().apply {
            put("question", "연결 확인")
        }.toString()

        val response = auth.authenticatedRequest(
            url = "$ENDPOINT/plan",
            method = "POST",
            jsonBody = reqBody
        )

        val obj = try {
            JSONObject(response.body)
        } catch (_: Exception) {
            null
        }

        val success = obj?.optBoolean("success", false) == true
        if (response.statusCode in 200..299 && success) {
            return "연결됨 · HTTP ${response.statusCode}"
        } else {
            throw IOException("chat/plan HTTP ${response.statusCode}")
        }
    }

    private fun parsePOI(raw: JSONObject, originLat: Double, originLon: Double): CompanionPOI? {
        fun text(vararg keys: String): String? {
            for (k in keys) {
                if (raw.has(k) && !raw.isNull(k)) {
                    val s = raw.optString(k).trim()
                    if (s.isNotEmpty()) return s
                }
            }
            return null
        }

        fun number(vararg keys: String): Double? {
            for (k in keys) {
                if (raw.has(k) && !raw.isNull(k)) {
                    val v = raw.optDouble(k)
                    if (!v.isNaN()) return v
                    val s = raw.optString(k)
                    val parsed = s.toDoubleOrNull()
                    if (parsed != null) return parsed
                }
            }
            return null
        }

        val name = text("displayName", "name", "placeName", "place_name") ?: return null
        val lat = number("lat", "latitude", "y") ?: return null
        val lon = number("lng", "longitude", "x") ?: return null

        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || lat == 0.0 || lon == 0.0) return null

        val id = text("id", "placeId", "place_id") ?: "$name-$lat-$lon"
        val rawCategory = text("category", "subCategory", "majorCategory") ?: "장소"
        val category = when (rawCategory.lowercase()) {
            "cafe", "카페" -> "카페"
            "restaurant", "food", "음식점", "식당" -> "음식점"
            "medical", "약국", "병원" -> "의료"
            "shopping", "shop", "쇼핑" -> "쇼핑"
            "convenience", "편의점" -> "생활"
            else -> rawCategory
        }

        val address = text("address", "roadAddress", "road_address_name", "address_name") ?: "주소 정보 없음"
        val summary = text("oneLine", "summary", "description") ?: "추천 장소"
        val rating = number("rating", "score") ?: 0.0

        val results = FloatArray(1)
        Location.distanceBetween(originLat, originLon, lat, lon, results)
        val distanceMeters = results[0].toInt()

        return CompanionPOI(
            id = id,
            name = name,
            category = category,
            address = address,
            summary = summary,
            latitude = lat,
            longitude = lon,
            distanceMeters = distanceMeters,
            rating = rating
        )
    }
}
