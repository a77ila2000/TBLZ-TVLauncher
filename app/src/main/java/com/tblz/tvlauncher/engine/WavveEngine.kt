package com.tblz.tvlauncher.engine

import android.util.Log
import com.google.gson.JsonParser
import com.tblz.tvlauncher.model.CategoryData
import com.tblz.tvlauncher.model.ChannelData
import com.tblz.tvlauncher.model.EPGData
import com.tblz.tvlauncher.model.PlaybackInfo
import com.tblz.tvlauncher.model.PlaybackResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

object WavveEngine {
    private const val TAG = "WavveEngine"
    private const val API_KEY = "E5F3E0D30947AA5440556471321BB6D9"
    private const val CLIENT_VERSION = "7.1.40"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val guid = UUID.randomUUID().toString().replace("-", "")

    suspend fun login(id: String, pw: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "https://account-api.wavve.com/v1/signin/wavve" +
                    "?apikey=$API_KEY&client_version=$CLIENT_VERSION&device=pc&drm=wm" +
                    "&partner=pooq&pooqzone=none&region=kor&targetage=all"

            val jsonBody = """{"type":"wavve","id":"$id","password":"$pw","device":"pc"}"""
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Content-Type", "application/json; charset=UTF-8")
                .addHeader("Accept", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext false
            val json = JsonParser.parseString(body).asJsonObject

            if (json.has("credential") && !json.get("credential").isJsonNull) {
                val cred = json.get("credential").asString
                ConfigStore.wavveAuthKey = cred
                if (json.has("device_id") && !json.get("device_id").isJsonNull) {
                    ConfigStore.wavveDeviceId = json.get("device_id").asString
                }
                Log.d(TAG, "Wavve login success! Credential saved.")
                return@withContext true
            } else {
                Log.e(TAG, "Wavve login response error: $body")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wavve login exception: ${e.message}")
        }
        false
    }

    suspend fun fetchChannels(): Pair<List<CategoryData>, List<ChannelData>> = withContext(Dispatchers.IO) {
        val categories = mutableListOf<CategoryData>()
        val channels = mutableListOf<ChannelData>()

        try {
            val authKey = ConfigStore.wavveAuthKey
            val url = "https://apis.wavve.com/v1/category/GN55" +
                    "?apikey=$API_KEY&client_version=$CLIENT_VERSION&device=pc&drm=wm" +
                    "&partner=pooq&pooqzone=none&region=kor&targetage=all"

            val reqBuilder = Request.Builder()
                .url(url)
                .addHeader("User-Agent", USER_AGENT)

            if (authKey.isNotEmpty()) {
                reqBuilder.addHeader("Authorization", "Bearer $authKey")
                reqBuilder.addHeader("Wavve-Credential", authKey)
            }

            val resp = httpClient.newCall(reqBuilder.build()).execute()
            val body = resp.body?.string() ?: return@withContext Pair(categories, channels)
            val json = JsonParser.parseString(body).asJsonObject
            val catalogList = json.getAsJsonObject("data")
                ?.getAsJsonObject("layout")
                ?.getAsJsonObject("catalog")
                ?.getAsJsonArray("catalog_list") ?: return@withContext Pair(categories, channels)

            val favs = ConfigStore.getFavorites()

            for (catalogElem in catalogList) {
                val catObj = catalogElem.asJsonObject
                val catId = catObj.get("id")?.asString ?: ""
                val elementList = catObj.getAsJsonArray("element_list") ?: continue
                if (elementList.size() == 0) continue

                val actionList = elementList.get(0).asJsonObject.getAsJsonArray("action_list") ?: continue
                if (actionList.size() == 0) continue

                val executeUrl = actionList.get(0).asJsonObject
                    .getAsJsonObject("execute")
                    ?.getAsJsonObject("data")
                    ?.get("url")?.asString ?: continue

                if (!executeUrl.contains("genre=") && !executeUrl.contains("channelList=")) continue

                val catTitle = if (elementList.size() > 1) {
                    elementList.get(1).asJsonObject
                        .getAsJsonObject("property")
                        ?.get("text")?.asString ?: catId
                } else catId

                categories.add(CategoryData(id = catId, title = catTitle))

                // Fetch channels for this category
                val fetchUrl = "https://" + executeUrl.replace("limit=20", "limit=50") +
                        "&apikey=$API_KEY&device=pc&drm=wm&partner=pooq&pooqzone=none&region=kor&targetage=all"

                val catReq = Request.Builder()
                    .url(fetchUrl)
                    .addHeader("User-Agent", USER_AGENT)
                    .apply {
                        if (authKey.isNotEmpty()) {
                            addHeader("Authorization", "Bearer $authKey")
                            addHeader("Wavve-Credential", authKey)
                        }
                    }.build()

                val catResp = httpClient.newCall(catReq).execute()
                val catBody = catResp.body?.string() ?: continue
                val catJson = JsonParser.parseString(catBody).asJsonObject
                val contextList = catJson.getAsJsonObject("data")?.getAsJsonArray("context_list") ?: continue

                for (item in contextList) {
                    val itemObj = item.asJsonObject
                    val liveObj = itemObj.getAsJsonObject("live") ?: continue
                    val chName = liveObj.get("name")?.asString ?: ""
                    val progTitle = liveObj.get("program_title")?.asString ?: ""
                    val chId = itemObj.get("context_id")?.asString ?: ""
                    var thumbUrl = liveObj.get("thumbnail_url")?.asString ?: ""
                    if (!thumbUrl.startsWith("https://") && thumbUrl.isNotEmpty()) {
                        thumbUrl = "https://$thumbUrl"
                    }
                    if (thumbUrl.contains("?")) {
                        thumbUrl = thumbUrl.split("?")[0]
                    }

                    val isAudio = (chId == "LN106")
                    val isFav = if (favs.contains(chId)) 1 else 0

                    channels.add(
                        ChannelData(
                            id = chId,
                            title = chName,
                            programName = progTitle,
                            stillImageUrl = thumbUrl,
                            categoryId = catId,
                            siteType = ChannelData.SITE_WAVVE,
                            qualityType = ConfigStore.wavveQuality,
                            resolution = "FHD",
                            isAudioChannel = isAudio,
                            favorite = isFav
                        )
                    )
                }
            }
            Log.d(TAG, "Fetched ${channels.size} Wavve channels")
        } catch (e: Exception) {
            Log.e(TAG, "fetchChannels exception: ${e.message}")
        }
        Pair(categories, channels)
    }

    suspend fun getStreamPlaybackInfo(channel: ChannelData): PlaybackResult = withContext(Dispatchers.IO) {
        var authKey = ConfigStore.wavveAuthKey
        if (authKey.isEmpty() && ConfigStore.wavveId.isNotEmpty() && ConfigStore.wavvePw.isNotEmpty()) {
            login(ConfigStore.wavveId, ConfigStore.wavvePw)
            authKey = ConfigStore.wavveAuthKey
        }

        try {
            val qualityStr = when (channel.qualityType) {
                0 -> "5000" // 1080p FHD
                1 -> "2000" // 720p HD
                2 -> "1000" // 480p SD
                3 -> "500"
                else -> "5000"
            }
            val playId = UUID.randomUUID().toString().replace("-", "")

            val url = "https://delivery.wavve.com/v1/streaming/live" +
                    "?device=pc&partner=pooq&apikey=$API_KEY&service=wavve&pooqzone=none&region=kor" +
                    "&drm=wm&targetage=all&contentid=${channel.id}&audioChannel=2ch&hdr=sdr" +
                    "&videocodec=avc&audiocodec=aac&issurround=n&format=normal&withinsubtitle=n" +
                    "&contenttype=live&action=hls&protocol=hls&quality=$qualityStr&deviceModelId=mobile" +
                    "&guid=$guid&lastplayid=$playId&authtype=cookie&isabr=y&ishevc=n"

            val reqBuilder = Request.Builder()
                .url(url)
                .addHeader("User-Agent", USER_AGENT)

            if (authKey.isNotEmpty()) {
                reqBuilder.addHeader("Authorization", "Bearer $authKey")
                reqBuilder.addHeader("Wavve-Credential", authKey)
            }

            val resp = httpClient.newCall(reqBuilder.build()).execute()
            val body = resp.body?.string() ?: return@withContext PlaybackResult.Error("Wavve 서버 응답이 없습니다.")
            val json = JsonParser.parseString(body).asJsonObject

            val streamUrl = when {
                json.has("playurl") && !json.get("playurl").isJsonNull -> json.get("playurl").asString
                json.has("streaming_url") && !json.get("streaming_url").isJsonNull -> json.get("streaming_url").asString
                json.has("url") && !json.get("url").isJsonNull -> json.get("url").asString
                else -> ""
            }

            val awsCookie = if (json.has("awscookie") && !json.get("awscookie").isJsonNull) {
                json.get("awscookie").asString
            } else ""

            if (streamUrl.isNotEmpty()) {
                val res = when {
                    streamUrl.contains("AVC-FHD") || streamUrl.contains("live5000") -> "FHD"
                    streamUrl.contains("AVC-HD") || streamUrl.contains("live2000") -> "HD"
                    streamUrl.contains("AVC-SD") || streamUrl.contains("live1000") -> "SD"
                    else -> "FHD"
                }
                Log.d(TAG, "Wavve stream resolved")
                return@withContext PlaybackResult.Success(
                    PlaybackInfo(
                        url = streamUrl,
                        cookie = awsCookie,
                        resolution = res
                    )
                )
            } else {
                val errCode = json.get("resultcode")?.asString ?: ""
                val errMsg = json.get("resultmessage")?.asString ?: ""
                Log.e(TAG, "Wavve stream error: $errCode - $errMsg")
                if (authKey.isEmpty()) {
                    return@withContext PlaybackResult.AuthRequired("Wavve 로그인이 필요합니다.\n런처 [설정] 메뉴에서 Wavve 계정으로 로그인해 주세요.")
                } else {
                    return@withContext PlaybackResult.AuthRequired("Wavve $errMsg\n런처 [설정]에서 계정 및 이용권을 확인해 주세요.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getStreamPlaybackInfo exception: ${e.message}")
            return@withContext PlaybackResult.Error("Wavve 오류: ${e.message}")
        }
    }

    suspend fun fetchEPG(channelId: String): List<EPGData> = withContext(Dispatchers.IO) {
        val epgList = mutableListOf<EPGData>()
        try {
            val timeZone = TimeZone.getTimeZone("Asia/Seoul")
            val calendar = Calendar.getInstance(timeZone).apply {
                set(Calendar.HOUR_OF_DAY, get(Calendar.HOUR_OF_DAY) / 3 * 3)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).apply {
                this.timeZone = timeZone
            }
            val startDateTime = dateFormat.format(calendar.time)
            calendar.add(Calendar.HOUR_OF_DAY, 3)
            val endDateTime = dateFormat.format(calendar.time)

            val url = "https://apis.wavve.com/live/epgs".toHttpUrl().newBuilder()
                .addQueryParameter("startdatetime", startDateTime)
                .addQueryParameter("enddatetime", endDateTime)
                .addQueryParameter("genre", "all")
                .addQueryParameter("limit", "500")
                .addQueryParameter("offset", "0")
                .addQueryParameter("apikey", API_KEY)
                .addQueryParameter("client_version", CLIENT_VERSION)
                .addQueryParameter("device", "pc")
                .addQueryParameter("drm", "wm")
                .addQueryParameter("partner", "pooq")
                .addQueryParameter("pooqzone", "none")
                .addQueryParameter("region", "kor")
                .addQueryParameter("targetage", "all")
                .build()
            val req = Request.Builder()
                .url(url)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Wavve-Credential", "none")
                .build()

            val resp = httpClient.newCall(req).execute()
            resp.use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "fetchEPG HTTP ${response.code}")
                    return@use
                }

                val body = response.body?.string() ?: return@use
                val channels = JsonParser.parseString(body).asJsonObject.getAsJsonArray("list") ?: return@use

                for (channelElement in channels) {
                    if (!channelElement.isJsonObject) continue
                    val channelObject = channelElement.asJsonObject
                    if (channelObject.get("channelid")?.asString != channelId) continue

                    val programs = channelObject.getAsJsonArray("list") ?: break
                    for (programElement in programs) {
                        if (!programElement.isJsonObject) continue
                        val program = programElement.asJsonObject
                        val title = program.get("title")?.asString ?: ""
                        val startStr = program.get("starttime")?.asString ?: ""
                        val endStr = program.get("endtime")?.asString ?: ""

                        val start = try { dateFormat.parse(startStr) } catch (_: Exception) { null }
                        val end = try { dateFormat.parse(endStr) } catch (_: Exception) { null }

                        if (title.isNotEmpty() && start != null && end != null) {
                            epgList.add(EPGData(programName = title, startTime = start, endTime = end))
                        }
                    }
                    break
                }
            }
            Log.d(TAG, "Fetched ${epgList.size} EPG entries for $channelId")
        } catch (e: Exception) {
            Log.e(TAG, "fetchEPG exception: ${e.message}")
        }
        epgList
    }
}
