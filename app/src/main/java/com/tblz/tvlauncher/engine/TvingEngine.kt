package com.tblz.tvlauncher.engine

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import com.google.gson.JsonParser
import com.tblz.tvlauncher.model.CategoryData
import com.tblz.tvlauncher.model.ChannelData
import com.tblz.tvlauncher.model.EPGData
import com.tblz.tvlauncher.model.PlaybackInfo
import com.tblz.tvlauncher.model.PlaybackResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

object TvingEngine {
    private const val TAG = "TvingEngine"
    private const val API_KEY = "1e7952d0917d6aab1f0293a063697610"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val deviceId = UUID.randomUUID().toString().replace("-", "").take(16)
    private val guid = UUID.randomUUID().toString().replace("-", "").take(8)

    /** For the offscreen WebView that renews accessToken ([TvingSessionRefresher]). */
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun fetchChannels(): Pair<List<CategoryData>, List<ChannelData>> = withContext(Dispatchers.IO) {
        val categories = mutableListOf<CategoryData>()
        val channels = mutableListOf<ChannelData>()

        try {
            val token = ConfigStore.tvingToken
            val channelTypes = listOf("CPCS0100,CPCS0400", "CPCS0300")
            val favs = ConfigStore.getFavorites()
            val seenIds = mutableSetOf<String>()

            for (type in channelTypes) {
                var page = 1
                var hasMore = true

                while (hasMore && page <= 3) {
                    val url = "https://api.tving.com/v2/media/lives" +
                            "?pageSize=50&pageNo=$page&order=chno&adult=all&free=all&guest=all&scope=all" +
                            "&channelType=$type" +
                            "&screenCode=CSSD0100&networkCode=CSND0900&osCode=CSOD0900&teleCode=CSCD0900" +
                            "&apiKey=$API_KEY"

                    val reqBuilder = Request.Builder()
                        .url(url)
                        .addHeader("User-Agent", USER_AGENT)

                    if (token.isNotEmpty()) {
                        val cookieHeader = if (token.contains("=")) token else "_tving_token=$token"
                        reqBuilder.addHeader("Cookie", cookieHeader)
                    }

                    val resp = httpClient.newCall(reqBuilder.build()).execute()
                    val body = resp.body?.string() ?: break
                    val json = JsonParser.parseString(body).asJsonObject

                    val bodyElem = json.get("body")
                    if (bodyElem == null || !bodyElem.isJsonObject) break
                    val bodyObj = bodyElem.asJsonObject
                    val resultList = bodyObj.getAsJsonArray("result") ?: break

                    hasMore = bodyObj.get("has_more")?.asString?.equals("Y", ignoreCase = true) ?: false

                    for (elem in resultList) {
                        val item = elem.asJsonObject
                        val sched = item.getAsJsonObject("schedule") ?: continue
                        val chObj = sched.getAsJsonObject("channel") ?: continue
                        val chCode = chObj.get("code")?.asString ?: continue

                        if (seenIds.contains(chCode)) continue
                        seenIds.add(chCode)

                        val chName = chObj.getAsJsonObject("name")?.get("ko")?.asString ?: ""
                        val progName = sched.getAsJsonObject("program")?.getAsJsonObject("name")?.get("ko")?.asString
                            ?: item.getAsJsonObject("program")?.getAsJsonObject("name")?.get("ko")?.asString
                            ?: ""

                        val catCode = chObj.get("category_code")?.asString ?: "TVING"
                        val catNameObj = chObj.getAsJsonObject("category_name")
                        val catName = catNameObj?.get("ko")?.asString ?: "TVING"

                        if (categories.none { it.id == catCode }) {
                            categories.add(CategoryData(id = catCode, title = "$catName (TVING)"))
                        }

                        val thumbUrl = "http://stillshot.tving.com/thumbnail_current/${chCode}_320x180.jpg"
                        val isFav = if (favs.contains(chCode)) 1 else 0

                        channels.add(
                            ChannelData(
                                id = chCode,
                                title = chName,
                                programName = progName,
                                stillImageUrl = thumbUrl,
                                categoryId = catCode,
                                siteType = ChannelData.SITE_TVING,
                                qualityType = ConfigStore.tvingQuality,
                                resolution = "FHD",
                                isAudioChannel = false,
                                favorite = isFav
                            )
                        )
                    }
                    page++
                }
            }
            Log.d(TAG, "Fetched ${channels.size} Tving channels")
        } catch (e: Exception) {
            Log.e(TAG, "fetchChannels exception: ${e.message}")
        }
        Pair(categories, channels)
    }

    /** `/v3/media/stream/info` refused our session -- in practice an expired accessToken. */
    private class SessionRejected(val serverMessage: String) : Exception(serverMessage)

    suspend fun getStreamPlaybackInfo(channel: ChannelData): PlaybackResult = withContext(Dispatchers.IO) {
        syncTvingSessionFromWebView()
        if (ConfigStore.tvingToken.isEmpty() || ConfigStore.tvingAccessToken.isEmpty()) {
            return@withContext PlaybackResult.AuthRequired("TVING 로그인이 필요합니다.\n런처 [설정] 메뉴에서 TVING 계정으로 로그인해 주세요.")
        }

        val rejection = try {
            return@withContext requestStream(channel)
        } catch (e: SessionRejected) {
            e
        }

        // accessToken is short-lived and tving.com renews it from the long-lived refreshToken.
        // Let the site do that, then retry once. 1.0.11 discarded the session here instead, but
        // the WebView kept the dead cookie, the login window re-imported it and reported success,
        // and playback looped on "토큰 만료".
        val context = appContext
        if (context == null || !TvingSessionRefresher.refresh(context, ConfigStore.tvingAccessToken)) {
            return@withContext sessionRejectedResult(rejection.serverMessage)
        }
        syncTvingSessionFromWebView()
        try {
            requestStream(channel)
        } catch (e: SessionRejected) {
            sessionRejectedResult(e.serverMessage)
        }
    }

    private fun sessionRejectedResult(serverMessage: String) = PlaybackResult.AuthRequired(
        "TVING 인증을 자동으로 갱신하지 못했습니다.\n런처 [설정] → TVING 로그인을 다시 열어 주세요.\n(서버 응답: $serverMessage)"
    )

    private suspend fun requestStream(channel: ChannelData): PlaybackResult = withContext(Dispatchers.IO) {
        val token = ConfigStore.tvingToken
        val accessToken = ConfigStore.tvingAccessToken

        try {
            val nowSec = (System.currentTimeMillis() / 1000).toString()
            // Current TVING player posts an empty JSON body and supplies stream options
            // as query parameters. Sending these as a form body returns Unauthorized.
            val url = "https://api.tving.com/v3/media/stream/info".toHttpUrl().newBuilder()
                .addQueryParameter("mediaCode", channel.id)
                .addQueryParameter("deviceId", deviceId)
                .addQueryParameter("uuid", "$deviceId-$guid")
                .addQueryParameter("deviceInfo", "PC_Chrome")
                .addQueryParameter("noCache", nowSec)
                .addQueryParameter("callingFrom", "HTML5")
                .addQueryParameter("model", "windows_chrome_120.0.0.0")
                .addQueryParameter("videoTypes", "h264|av1")
                .addQueryParameter("audioTypes", "aac")
                .addQueryParameter("authType", "header")
                .addQueryParameter("xr", "108")
                .addQueryParameter("screenCode", "CSSD0100")
                .addQueryParameter("networkCode", "CSND0900")
                .addQueryParameter("osCode", "CSOD0900")
                .addQueryParameter("teleCode", "CSCD0900")
                .addQueryParameter("apiKey", API_KEY)
                .build()

            val reqBuilder = Request.Builder()
                .url(url)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Access-Token", accessToken)
                .post("".toRequestBody("application/json".toMediaType()))

            val resp = httpClient.newCall(reqBuilder.build()).execute()
            val bodyStr = resp.body?.string() ?: return@withContext PlaybackResult.Error("TVING 서버 응답이 없습니다.")
            val json = JsonParser.parseString(bodyStr).asJsonObject

            val bodyElem = json.get("body")
            if (bodyElem == null || !bodyElem.isJsonObject) {
                val headerObj = json.getAsJsonObject("header")
                val msg = headerObj?.get("message")?.takeUnless { it.isJsonNull }?.asString ?: "응답 오류"
                Log.e(TAG, "TVING stream response body is null (HTTP ${resp.code}): $msg")
                throw SessionRejected(msg)
            }

            val bodyObj = bodyElem.asJsonObject
            val resultObj = bodyObj.getAsJsonObject("result")
            val resultCode = resultObj?.get("code")?.takeUnless { it.isJsonNull }?.asString ?: ""

            if (resultCode != "000") {
                val resultMsg = resultObj?.get("message")?.takeUnless { it.isJsonNull }?.asString
                    ?: "코드: $resultCode"
                Log.e(TAG, "TVING stream response error code: $resultCode ($resultMsg)")
                if (resultCode == "010" || resultCode == "030" || resultCode == "040") {
                    return@withContext PlaybackResult.AuthRequired("TVING $resultMsg\n런처 [설정]에서 로그인해 주세요.")
                }
                if (resultCode == "080") {
                    return@withContext PlaybackResult.Error(
                        "${channel.title} 재생 불가\n현재 프로그램은 방송사의 판권 제한 요청으로 재생할 수 없습니다.\n다른 채널을 선택해 주세요.",
                        retryable = false
                    )
                }
                return@withContext PlaybackResult.Error("TVING 오류: $resultMsg")
            }

            val streamObj = bodyObj.getAsJsonObject("stream")
                ?: return@withContext PlaybackResult.Error("TVING 스트림 정보가 없습니다.")
            val drmYn = streamObj.get("drm_yn")?.asString ?: "N"

            if (drmYn.equals("Y", ignoreCase = true)) {
                // Widevine DRM (SVOD 채널: OCN 등). 실제 응답 구조는
                //   playback.drm.widevine = "<prefix>|<base64>"  (non_drm 과 동일하게 암호화됨)
                //   playback.drm.license.drm_license_data = [ {drm_type, drm_server_url,
                //                                              drm_header_key, drm_header_value}, ... ]
                // 이전 구현은 playback.widevine / playback.license 를 봤으나 그런 경로는 없어
                // 항상 빈 URL 로 떨어져 재시도만 반복했다.
                val drmObj = streamObj.getAsJsonObject("playback")?.getAsJsonObject("drm")
                val encWidevine = drmObj?.get("widevine")?.takeUnless { it.isJsonNull }?.asString ?: ""

                var streamUrl = ""
                var cookie = ""
                if (encWidevine.isNotEmpty()) {
                    val decrypted = TblzCipher.decrypt(channel.id, encWidevine)
                    if (decrypted.isNotEmpty()) {
                        streamUrl = decrypted[0]
                        cookie = if (decrypted.size > 1) decrypted[1] else ""
                    }
                }

                // drm_license_data 는 widevine/playready/fairplay 3개 배열 → widevine 만 사용.
                var licenseUrl = ""
                var headerKey = ""
                var headerVal = ""
                val drmData = drmObj?.getAsJsonObject("license")?.getAsJsonArray("drm_license_data")
                if (drmData != null) {
                    val wv = (0 until drmData.size())
                        .map { drmData.get(it).asJsonObject }
                        .firstOrNull { it.get("drm_type")?.asString.equals("widevine", ignoreCase = true) }
                        ?: drmData.get(0).asJsonObject
                    licenseUrl = wv.get("drm_server_url")?.takeUnless { it.isJsonNull }?.asString ?: ""
                    headerKey = wv.get("drm_header_key")?.takeUnless { it.isJsonNull }?.asString ?: ""
                    headerVal = wv.get("drm_header_value")?.takeUnless { it.isJsonNull }?.asString ?: ""
                }

                if (streamUrl.isNotEmpty() && licenseUrl.isNotEmpty()) {
                    Log.d(TAG, "TVING DRM stream resolved")
                    return@withContext PlaybackResult.Success(
                        PlaybackInfo(
                            url = streamUrl,
                            cookie = cookie,
                            drmServerUrl = licenseUrl,
                            drmHeaderKey = headerKey,
                            drmHeaderValue = headerVal,
                            resolution = "FHD"
                        )
                    )
                }
            } else {
                // Non-DRM: Decrypt in Kotlin.
                val playbackObj = streamObj.getAsJsonObject("playback")
                val broadcastObj = streamObj.getAsJsonObject("broadcast")
                val encryptedUrl = playbackObj?.get("non_drm")
                    ?.takeUnless { it.isJsonNull }?.asString
                    ?: broadcastObj?.get("broad_url")?.takeUnless { it.isJsonNull }?.asString
                    ?: ""

                if (encryptedUrl.isNotEmpty()) {
                    val decrypted = TblzCipher.decrypt(channel.id, encryptedUrl)
                    if (decrypted.isNotEmpty()) {
                        val streamUrl = decrypted[0]
                        val cookie = if (decrypted.size > 1) decrypted[1] else ""
                        Log.d(TAG, "TVING stream decrypted")
                        return@withContext PlaybackResult.Success(
                            PlaybackInfo(
                                url = streamUrl,
                                cookie = cookie,
                                resolution = "FHD"
                            )
                        )
                    }
                }
            }
        } catch (e: SessionRejected) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "getStreamPlaybackInfo exception: ${e.message}")
            return@withContext PlaybackResult.Error("TVING 오류: ${e.message}")
        }
        PlaybackResult.Error("TVING 스트림 URL을 생성하지 못했습니다.")
    }

    /**
     * Mirror the WebView's TVING cookies into [ConfigStore]. Runs before every play -- not only
     * when nothing is stored -- so a renewal by the login window or the refresher is picked up.
     */
    private fun syncTvingSessionFromWebView() {
        try {
            val cookieManager = CookieManager.getInstance()
            val combined = listOf(
                cookieManager.getCookie("https://www.tving.com/"),
                cookieManager.getCookie("https://user.tving.com/"),
                cookieManager.getCookie("https://api.tving.com/")
            ).filterNotNull().filter(String::isNotEmpty).joinToString("; ")
            ConfigStore.importTvingCookies(combined)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to restore TVING WebView session: ${e.javaClass.simpleName}")
        }
    }

    suspend fun fetchEPG(channelId: String): List<EPGData> = withContext(Dispatchers.IO) {
        val epgList = mutableListOf<EPGData>()
        try {
            // TVING의 /v2/media/schedules 는 파라미터가 바뀌었다. 이전 구현이 쓰던
            // startScheduleDate/endScheduleDate + pageSize=50 조합은 400("필수 파라미터 누락
            // (BroadDate)", "pageSize 제한 범위 20 초과", "시간대 제한 범위 3시간 초과")을 내며
            // 아무 EPG도 못 받아 왔다. 현재 요구사항:
            //   broadDate=yyyyMMdd, startBroadTime/endBroadTime(HHmmss, 두 값의 간격 ≤ 3시간),
            //   pageSize ≤ 20, channelCode.
            // 응답도 result 배열 안 각 채널의 schedules[] 로 바뀌었다:
            //   body.result[].schedules[] { broadcast_start_time, broadcast_end_time, program.name.ko }
            val cal = java.util.Calendar.getInstance(Locale.KOREA)
            val broadDate = SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(cal.time)
            // 현재 시각을 포함하는 3시간 창(직전 1시간 ~ 이후 2시간). API 상한이 3시간이라 이 폭을 넘기면 안 된다.
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val startH = (hour - 1).coerceIn(0, 21)
            val endH = startH + 3
            val startBroadTime = String.format(Locale.US, "%02d0000", startH)
            val endBroadTime = String.format(Locale.US, "%02d0000", endH)

            val url = "https://api.tving.com/v2/media/schedules" +
                    "?screenCode=CSSD0100&networkCode=CSND0900&osCode=CSOD0900&teleCode=CSCD0900" +
                    "&apiKey=$API_KEY&broadDate=$broadDate" +
                    "&startBroadTime=$startBroadTime&endBroadTime=$endBroadTime" +
                    "&pageNo=1&pageSize=20&channelCode=$channelId"

            val req = Request.Builder()
                .url(url)
                .addHeader("User-Agent", USER_AGENT)
                .build()

            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext epgList
            val json = JsonParser.parseString(body).asJsonObject
            val bodyElement = json.get("body")
            if (bodyElement == null || !bodyElement.isJsonObject) return@withContext epgList
            val resultElement = bodyElement.asJsonObject.get("result")
            if (resultElement == null || !resultElement.isJsonArray) return@withContext epgList
            val resultArr = resultElement.asJsonArray

            val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.KOREA)
            for (chElem in resultArr) {
                if (!chElem.isJsonObject) continue
                val schedulesElement = chElem.asJsonObject.get("schedules")
                if (schedulesElement == null || !schedulesElement.isJsonArray) continue
                val schedules = schedulesElement.asJsonArray
                for (sElem in schedules) {
                    if (!sElem.isJsonObject) continue
                    val obj = sElem.asJsonObject
                    val programElement = obj.get("program")
                    val program = programElement?.takeIf { it.isJsonObject }?.asJsonObject
                    val nameElement = program?.get("name")
                    val name = nameElement?.takeIf { it.isJsonObject }?.asJsonObject
                    val titleElement = name?.get("ko")
                    val title = titleElement?.takeUnless { it.isJsonNull }?.asString ?: ""
                    val startElement = obj.get("broadcast_start_time")
                    val endElement = obj.get("broadcast_end_time")
                    val startStr = startElement?.takeUnless { it.isJsonNull }?.asString ?: ""
                    val endStr = endElement?.takeUnless { it.isJsonNull }?.asString ?: ""

                    val start = try { sdf.parse(startStr) } catch (_: Exception) { null }
                    val end = try { sdf.parse(endStr) } catch (_: Exception) { null }

                    if (title.isNotEmpty() && start != null && end != null) {
                        epgList.add(EPGData(programName = title, startTime = start, endTime = end))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchEPG exception: ${e.message}")
        }
        epgList
    }
}
