package com.xrch.companion.auth

import android.content.Context
import android.content.SharedPreferences
import com.xrch.companion.data.CompanionUsage
import com.xrch.companion.data.StoredCompanionSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>>
) {
    fun header(name: String): String? {
        val entry = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
        return entry?.value?.firstOrNull()
    }
}

class CompanionAuthSession(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("xrch_companion_auth_session", Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val tokenMutex = Mutex()

    private val _email = MutableStateFlow<String?>(null)
    val email: StateFlow<String?> = _email.asStateFlow()

    private val _status = MutableStateFlow("게스트 연결 준비 중")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _isWorking = MutableStateFlow(false)
    val isWorking: StateFlow<Boolean> = _isWorking.asStateFlow()

    private val _isGuest = MutableStateFlow(false)
    val isGuest: StateFlow<Boolean> = _isGuest.asStateFlow()

    private val _usage = MutableStateFlow<CompanionUsage?>(null)
    val usage: StateFlow<CompanionUsage?> = _usage.asStateFlow()

    private val _usageStatus = MutableStateFlow("사용량 확인 중")
    val usageStatus: StateFlow<String> = _usageStatus.asStateFlow()

    private var session: StoredCompanionSession? = null

    val isAuthenticated: Boolean
        get() = session != null && !_isGuest.value

    val hasSession: Boolean
        get() = session != null

    init {
        session = readStoredSession("current-user")
        updatePublishedSession()
    }

    private fun updatePublishedSession() {
        val s = session
        _email.value = s?.email
        _isGuest.value = s?.isAnonymous == true
        _status.value = if (s == null) {
            "게스트 연결 준비 중"
        } else if (s.isAnonymous) {
            "게스트로 사용 중"
        } else {
            "로그인됨 (${s.email ?: "사용자"})"
        }
    }

    suspend fun prepareGuestSession() {
        try {
            validAccessToken()
            refreshUsage()
        } catch (e: Exception) {
            _status.value = "연결 실패: ${e.localizedMessage}"
        }
    }

    suspend fun signIn(emailInput: String, passwordInput: String) {
        if (_isWorking.value) throw IllegalStateException("인증 처리 중입니다. 잠시 후 다시 시도해 주세요.")
        _isWorking.value = true
        try {
            val body = JSONObject().apply {
                put("email", emailInput)
                put("password", passwordInput)
            }.toString()

            val resp = sendRequest("${SupabaseConfiguration.FUNCTIONS_URL}/login", "POST", body)
            val json = JSONObject(resp.body)
            if (!json.optBoolean("success", false)) {
                val msg = json.optString("message", "인증 요청에 실패했습니다.")
                throw RuntimeException(msg)
            }

            val userId = json.optString("userId")
            val accessToken = json.optString("accessToken")
            val refreshToken = json.optString("refreshToken")
            val userEmail = if (json.has("email") && !json.isNull("email")) json.optString("email") else emailInput
            val expiresIn = json.optInt("accessExpiresIn", 3600)

            if (userId.isEmpty() || accessToken.isEmpty() || refreshToken.isEmpty()) {
                throw RuntimeException("인증 서버 응답을 읽을 수 없습니다.")
            }

            // If current was a guest session, save to guest storage before switching
            if (session?.isAnonymous == true) {
                writeStoredSession("guest-user", session!!)
            }

            val record = StoredCompanionSession(
                userId = userId,
                email = userEmail,
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtMillis = System.currentTimeMillis() + (expiresIn * 1000L),
                isAnonymous = false
            )
            writeStoredSession("current-user", record)
            session = record
            _usage.value = null
            _usageStatus.value = "사용량 확인 중"
            updatePublishedSession()
            refreshUsage()
        } finally {
            _isWorking.value = false
        }
    }

    suspend fun signUp(emailInput: String, passwordInput: String, nicknameInput: String) {
        if (_isWorking.value) throw IllegalStateException("인증 처리 중입니다. 잠시 후 다시 시도해 주세요.")
        _isWorking.value = true
        try {
            val body = JSONObject().apply {
                put("email", emailInput)
                put("password", passwordInput)
                put("nickname", nicknameInput)
                put("language", "ko")
            }.toString()

            val resp = sendRequest("${SupabaseConfiguration.FUNCTIONS_URL}/signup", "POST", body)
            val json = JSONObject(resp.body)
            if (!json.optBoolean("success", false)) {
                val msg = json.optString("message", "회원가입 요청에 실패했습니다.")
                throw RuntimeException(msg)
            }

            val accessToken = json.optString("accessToken", "")
            val refreshToken = json.optString("refreshToken", "")
            val userId = json.optString("userId", "")
            val expiresIn = json.optInt("accessExpiresIn", 3600)

            if (accessToken.isNotEmpty() && refreshToken.isNotEmpty() && userId.isNotEmpty()) {
                if (session?.isAnonymous == true) {
                    writeStoredSession("guest-user", session!!)
                }
                val record = StoredCompanionSession(
                    userId = userId,
                    email = emailInput,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAtMillis = System.currentTimeMillis() + (expiresIn * 1000L),
                    isAnonymous = false
                )
                writeStoredSession("current-user", record)
                session = record
                _usage.value = null
                _usageStatus.value = "사용량 확인 중"
                updatePublishedSession()
                refreshUsage()
            } else {
                _status.value = "가입되었습니다. 이메일 인증 후 로그인해 주세요."
            }
        } finally {
            _isWorking.value = false
        }
    }

    suspend fun signOut() {
        if (session == null || _isGuest.value || _isWorking.value) return
        _isWorking.value = true
        try {
            val token = validAccessToken()
            try {
                sendRequest(
                    url = "${SupabaseConfiguration.AUTH_URL}/logout?scope=local",
                    method = "POST",
                    body = null,
                    bearerToken = token
                )
            } catch (_: Exception) {
                // Ignore remote logout error and proceed to local session clear
            }

            val savedGuest = readStoredSession("guest-user")
            if (savedGuest != null && savedGuest.isAnonymous) {
                writeStoredSession("current-user", savedGuest)
                session = savedGuest
            } else {
                deleteStoredSession("current-user")
                session = null
            }
            _usage.value = null
            updatePublishedSession()
            prepareGuestSession()
        } finally {
            _isWorking.value = false
        }
    }

    suspend fun validAccessToken(forceRefresh: Boolean = false): String = tokenMutex.withLock {
        val s = session
        val now = System.currentTimeMillis()
        if (!forceRefresh && s != null && (s.expiresAtMillis - now) > 60_000) {
            return@withLock s.accessToken
        }

        _isWorking.value = true
        try {
            val record = withContext(Dispatchers.IO) {
                if (s != null) {
                    // Refresh existing token
                    val reqBody = JSONObject().apply {
                        put("refresh_token", s.refreshToken)
                    }.toString()

                    val resp = sendRequest(
                        url = "${SupabaseConfiguration.AUTH_URL}/token?grant_type=refresh_token",
                        method = "POST",
                        body = reqBody
                    )
                    val json = JSONObject(resp.body)
                    val userObj = json.getJSONObject("user")
                    val userId = userObj.getString("id")
                    if (userId != s.userId) {
                        throw RuntimeException("인증 세션이 일치하지 않습니다.")
                    }
                    val isAnon = userObj.optBoolean("is_anonymous", s.isAnonymous)
                    val emailStr = if (userObj.has("email") && !userObj.isNull("email")) userObj.getString("email") else s.email
                    val newAccessToken = json.getString("access_token")
                    val newRefreshToken = json.getString("refresh_token")
                    val expiresIn = json.getInt("expires_in")

                    StoredCompanionSession(
                        userId = userId,
                        email = emailStr,
                        accessToken = newAccessToken,
                        refreshToken = newRefreshToken,
                        expiresAtMillis = System.currentTimeMillis() + (expiresIn * 1000L),
                        isAnonymous = isAnon
                    )
                } else {
                    // Anonymous guest signup
                    val resp = sendRequest(
                        url = "${SupabaseConfiguration.AUTH_URL}/signup",
                        method = "POST",
                        body = "{}"
                    )
                    val json = JSONObject(resp.body)
                    val userObj = json.getJSONObject("user")
                    val isAnon = userObj.optBoolean("is_anonymous", true)
                    val userId = userObj.getString("id")
                    val emailStr = if (userObj.has("email") && !userObj.isNull("email")) userObj.getString("email") else null
                    val accessToken = json.getString("access_token")
                    val refreshToken = json.getString("refresh_token")
                    val expiresIn = json.getInt("expires_in")

                    StoredCompanionSession(
                        userId = userId,
                        email = emailStr,
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        expiresAtMillis = System.currentTimeMillis() + (expiresIn * 1000L),
                        isAnonymous = isAnon
                    )
                }
            }

            writeStoredSession("current-user", record)
            session = record
            updatePublishedSession()
            return@withLock record.accessToken
        } finally {
            _isWorking.value = false
        }
    }

    suspend fun authenticatedRequest(
        url: String,
        method: String = "GET",
        jsonBody: String? = null
    ): HttpResponse {
        var token = validAccessToken()
        for (attempt in 0..1) {
            val response = withContext(Dispatchers.IO) {
                sendRequest(url = url, method = method, body = jsonBody, bearerToken = token)
            }

            if (response.statusCode == 401 && attempt == 0) {
                token = validAccessToken(forceRefresh = true)
                continue
            }

            val quotaSnapshot = CompanionUsage.fromHeaders { headerName -> response.header(headerName) }
            if (quotaSnapshot != null) {
                _usage.value = quotaSnapshot
                _usageStatus.value = quotaSnapshot.summary
            }
            return response
        }
        throw RuntimeException("인증 요청 실패")
    }

    suspend fun refreshUsage() {
        try {
            val response = authenticatedRequest(
                url = "${SupabaseConfiguration.FUNCTIONS_URL}/usage",
                method = "GET"
            )
            if (response.statusCode in 200..299) {
                val json = JSONObject(response.body)
                val remaining = if (json.has("remaining") && !json.isNull("remaining")) json.getInt("remaining") else null
                val resetAt = json.optString("resetAt", "")
                val isAnon = json.optBoolean("isAnonymous", _isGuest.value)
                val snapshot = CompanionUsage(remaining = remaining, resetAt = resetAt, isAnonymous = isAnon)
                _usage.value = snapshot
                _usageStatus.value = snapshot.summary
            } else {
                _usageStatus.value = "사용량 확인 실패 (HTTP ${response.statusCode})"
            }
        } catch (_: Exception) {
            _usageStatus.value = "사용량을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."
        }
    }

    private fun sendRequest(
        url: String,
        method: String,
        body: String?,
        bearerToken: String? = null
    ): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 45_000
            conn.setRequestProperty("apikey", SupabaseConfiguration.PUBLIC_KEY)
            conn.setRequestProperty("Accept", "application/json")
            if (bearerToken != null) {
                conn.setRequestProperty("Authorization", "Bearer $bearerToken")
            }

            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(body)
                    writer.flush()
                }
            }

            val code = conn.responseCode
            val inputStream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseBody = inputStream?.let { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            } ?: ""

            HttpResponse(
                statusCode = code,
                body = responseBody,
                headers = conn.headerFields ?: emptyMap()
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun readStoredSession(account: String): StoredCompanionSession? {
        val raw = prefs.getString("session_$account", null) ?: return null
        return StoredCompanionSession.fromJson(raw)
    }

    private fun writeStoredSession(account: String, record: StoredCompanionSession) {
        prefs.edit().putString("session_$account", record.toJson()).apply()
    }

    private fun deleteStoredSession(account: String) {
        prefs.edit().remove("session_$account").apply()
    }
}
