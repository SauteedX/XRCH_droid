package com.xrch.companion.ui.viewmodel

import android.app.Application
import android.location.Location
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xrch.companion.api.RecommendationAPI
import com.xrch.companion.auth.CompanionAuthSession
import com.xrch.companion.data.CompanionPOI
import com.xrch.companion.data.MessageRole
import com.xrch.companion.data.RecommendationMessage
import kotlinx.coroutines.launch
import java.util.Date

class CompanionViewModel(application: Application) : AndroidViewModel(application) {

    val auth: CompanionAuthSession = CompanionAuthSession(application.applicationContext)

    var query by mutableStateOf("")
    var selectedCategory by mutableStateOf("전체")
    var selectedPOI by mutableStateOf<CompanionPOI?>(null)
    var favoriteIDs by mutableStateOf(setOf<String>())
    var recentSearches by mutableStateOf(listOf("조용한 카페", "점심 맛집", "약국"))

    var aiPrompt by mutableStateOf("")
    var recommendationMessages by mutableStateOf(
        listOf(
            RecommendationMessage(
                role = MessageRole.ASSISTANT,
                text = "안녕하세요! 지금 계신 곳 주변에서 어떤 장소를 찾으세요?",
                places = emptyList()
            )
        )
    )
    var isRecommending by mutableStateOf(false)

    val aiAnswer: String
        get() = recommendationMessages.lastOrNull { it.role == MessageRole.ASSISTANT }?.text
            ?: "원하는 장소의 분위기나 목적을 입력하면 주변 후보를 정리해 드려요."

    // Recommendation metadata
    var lastRecommendationStatus by mutableStateOf("요청 전")
    var lastRecommendationModel by mutableStateOf("-")
    var lastRecommendationGPU by mutableStateOf("-")
    var lastRecommendationAt by mutableStateOf<Date?>(null)
    var lastRecommendationAccuracy by mutableStateOf<Double?>(null)

    // Supabase probe
    var supabaseProbeStatus by mutableStateOf("확인 전")
    var isProbingSupabase by mutableStateOf(false)

    // Profile settings
    var displayName by mutableStateOf("ARCH 사용자")
    var searchRadius by mutableDoubleStateOf(500.0)
    var language by mutableStateOf("한국어")

    val categories = listOf("전체", "카페", "음식점", "쇼핑", "생활", "의료")

    // Default mock places for initial display before any AI query
    private val defaultPois = listOf(
        CompanionPOI("cafe-1", "모노 커피", "카페", "안산시 상록구 광덕1로", "조용한 좌석과 넓은 창이 있는 로스터리", 37.30191, 126.83812, 86, 4.7),
        CompanionPOI("food-1", "담소 키친", "음식점", "안산시 상록구 한양대학로", "가볍게 먹기 좋은 한식과 계절 메뉴", 37.30142, 126.83901, 142, 4.5),
        CompanionPOI("shop-1", "아카이브 문구", "쇼핑", "안산시 상록구 성안길", "디자인 문구와 작은 로컬 굿즈 숍", 37.30218, 126.83744, 205, 4.6),
        CompanionPOI("life-1", "24시 편의점", "생활", "안산시 상록구 석호로", "간단한 식품과 생활용품", 37.30098, 126.83874, 248, 4.2),
        CompanionPOI("medical-1", "중앙 약국", "의료", "안산시 상록구 광덕대로", "처방 조제와 일반 의약품 상담", 37.30242, 126.83922, 331, 4.4)
    )

    var pois by mutableStateOf(defaultPois)

    val allPois: List<CompanionPOI>
        get() = pois

    val filteredPOIs: List<CompanionPOI>
        get() = pois.filter { poi ->
            val categoryMatches = selectedCategory == "전체" || poi.category == selectedCategory
            val normalized = query.trim()
            val queryMatches = normalized.isEmpty() || listOf(poi.name, poi.category, poi.address, poi.summary)
                .any { it.contains(normalized, ignoreCase = true) }
            categoryMatches && queryMatches
        }

    val favorites: List<CompanionPOI>
        get() = pois.filter { favoriteIDs.contains(it.id) }

    init {
        viewModelScope.launch {
            auth.prepareGuestSession()
        }
    }

    fun submitSearch() {
        val value = query.trim()
        if (value.isNotEmpty()) {
            recentSearches = (listOf(value) + recentSearches.filter { it != value }).take(6)
        }
    }

    fun toggleFavorite(poi: CompanionPOI) {
        favoriteIDs = if (favoriteIDs.contains(poi.id)) {
            favoriteIDs - poi.id
        } else {
            favoriteIDs + poi.id
        }
    }

    fun askAI(customPrompt: String? = null, currentLocation: Location? = null) {
        val prompt = (customPrompt ?: aiPrompt).trim()
        if (prompt.isEmpty() || isRecommending) return
        aiPrompt = ""

        recommendationMessages = recommendationMessages + RecommendationMessage(
            role = MessageRole.USER,
            text = prompt
        )

        isRecommending = true
        lastRecommendationStatus = "위치 확인 중"

        val originLat = currentLocation?.latitude ?: 37.3017362
        val originLon = currentLocation?.longitude ?: 126.8385608
        val accuracy = if (currentLocation != null && currentLocation.hasAccuracy()) currentLocation.accuracy.toDouble() else null
        lastRecommendationAccuracy = accuracy

        lastRecommendationStatus = "Supabase 요청 중"

        viewModelScope.launch {
            try {
                val followUpKeywords = listOf("그중", "그 중", "각각", "방금", "위에서", "거기", "그곳", "비교")
                val isFollowUp = followUpKeywords.any { prompt.contains(it) } || pois.any { prompt.contains(it.name) }
                val previousPlaces = if (isFollowUp) pois else emptyList()

                val result = RecommendationAPI.recommend(
                    question = prompt,
                    latitude = originLat,
                    longitude = originLon,
                    radius = searchRadius.toInt(),
                    auth = auth,
                    previousPlaces = previousPlaces
                )

                recommendationMessages = recommendationMessages + RecommendationMessage(
                    role = MessageRole.ASSISTANT,
                    text = result.answer,
                    places = result.places.take(3)
                )

                if (result.places.isNotEmpty()) {
                    pois = result.places
                }

                selectedCategory = "전체"
                query = ""
                lastRecommendationStatus = "성공 · 장소 ${result.places.size}곳"
                lastRecommendationModel = result.model
                lastRecommendationGPU = if (result.gpuUsed) "사용됨" else "미사용 또는 확인 불가"
            } catch (e: Exception) {
                recommendationMessages = recommendationMessages + RecommendationMessage(
                    role = MessageRole.ASSISTANT,
                    text = "추천 요청 실패: ${e.localizedMessage}"
                )
                lastRecommendationStatus = "실패: ${e.localizedMessage}"
            } finally {
                lastRecommendationAt = Date()
                isRecommending = false
            }
        }
    }

    fun probeSupabase() {
        if (isProbingSupabase) return
        isProbingSupabase = true
        supabaseProbeStatus = "연결 확인 중"

        viewModelScope.launch {
            try {
                supabaseProbeStatus = RecommendationAPI.probe(auth)
            } catch (e: Exception) {
                supabaseProbeStatus = "실패: ${e.localizedMessage}"
            } finally {
                isProbingSupabase = false
            }
        }
    }
}
