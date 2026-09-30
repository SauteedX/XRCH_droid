package com.xrch.companion.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.xrch.companion.data.CompanionPOI

class CompanionViewModel : ViewModel() {

    var query by mutableStateOf("")
    var selectedCategory by mutableStateOf("전체")
    var selectedPOI by mutableStateOf<CompanionPOI?>(null)
    var favoriteIDs by mutableStateOf(setOf<String>())
    var recentSearches by mutableStateOf(listOf("조용한 카페", "점심 맛집", "약국"))
    var aiPrompt by mutableStateOf("")
    var aiAnswer by mutableStateOf("원하는 장소의 분위기나 목적을 입력하면 주변 후보를 정리해 드려요.")

    // Profile settings
    var displayName by mutableStateOf("ARCH 사용자")
    var searchRadius by mutableDoubleStateOf(500.0)
    var language by mutableStateOf("한국어")

    val categories = listOf("전체", "카페", "음식점", "쇼핑", "생활", "의료")

    val allPois: List<CompanionPOI> = listOf(
        CompanionPOI("cafe-1", "모노 커피", "카페", "안산시 상록구 광덕1로", "조용한 좌석과 넓은 창이 있는 로스터리", 37.30191, 126.83812, 86, 4.7),
        CompanionPOI("food-1", "담소 키친", "음식점", "안산시 상록구 한양대학로", "가볍게 먹기 좋은 한식과 계절 메뉴", 37.30142, 126.83901, 142, 4.5),
        CompanionPOI("shop-1", "아카이브 문구", "쇼핑", "안산시 상록구 성안길", "디자인 문구와 작은 로컬 굿즈 숍", 37.30218, 126.83744, 205, 4.6),
        CompanionPOI("life-1", "24시 편의점", "생활", "안산시 상록구 석호로", "간단한 식품과 생활용품", 37.30098, 126.83874, 248, 4.2),
        CompanionPOI("medical-1", "중앙 약국", "의료", "안산시 상록구 광덕대로", "처방 조제와 일반 의약품 상담", 37.30242, 126.83922, 331, 4.4)
    )

    val filteredPOIs: List<CompanionPOI>
        get() = allPois.filter { poi ->
            val categoryMatches = selectedCategory == "전체" || poi.category == selectedCategory
            val normalized = query.trim()
            val queryMatches = normalized.isEmpty() || listOf(poi.name, poi.category, poi.address, poi.summary)
                .any { it.contains(normalized, ignoreCase = true) }
            categoryMatches && queryMatches
        }

    val favorites: List<CompanionPOI>
        get() = allPois.filter { favoriteIDs.contains(it.id) }

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

    fun askAI() {
        val prompt = aiPrompt.trim()
        if (prompt.isEmpty()) return
        val candidates = filteredPOIs.take(3).joinToString(", ") { it.name }
        aiAnswer = if (candidates.isEmpty()) {
            "조건에 맞는 후보가 아직 없습니다. 검색 범위를 넓혀보세요."
        } else {
            "현재 위치와 조건을 기준으로 ${candidates}을 먼저 살펴보는 것을 추천해요."
        }
        aiPrompt = ""
    }
}
