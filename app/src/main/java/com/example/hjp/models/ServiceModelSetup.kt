package com.example.hjp.models

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.example.hjp.AppContainer
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ModelSetupState(val phase: String = "checking", val message: String = "AI 모델을 확인하고 있습니다", val downloaded: Long = 0) {
    val total get() = ModelDownloads.generative.size
    val ready get() = phase == "ready"
    val busy get() = phase in setOf("checking", "downloading", "waiting", "verifying")
}

/** Android owns the transfer across screen/process death; the app verifies before use. */
class ServiceModelSetup(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = context.getSharedPreferences("service_model_setup", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val directory = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
    private val installer = ModelInstaller(directory)
    private val model = ModelDownloads.generative
    private val mutableState = MutableStateFlow(ModelSetupState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var bundledVerified = false
    val termsAccepted get() = preferences.getString("terms", "") == TERMS_VERSION
    init { refresh() }

    @Synchronized fun refresh() {
        if (job?.isActive == true) return
        job = scope.launch { guarded { inspect() } }
    }

    @Synchronized fun acceptAndDownload(allowMobile: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch { guarded {
            check(bundledVerified) { "앱에 포함된 검색 모델 검증이 필요합니다. 앱을 다시 설치해 주세요." }
            check(preferences.edit().putString("terms", TERMS_VERSION).commit()) { "설정 저장에 실패했습니다." }
            if (mutableState.value.ready) {
                mutableState.value = ModelSetupState("ready", "약관 동의 및 AI 모델 준비 완료")
                return@guarded
            }
            check(directory.isDirectory || directory.mkdirs()) { "모델 저장 공간을 만들 수 없습니다." }
            val oldId = preferences.getLong("download_id", -1)
            if (oldId >= 0) { manager.remove(oldId); preferences.edit().remove("download_id").commit() }
            val staging = File(directory, model.fileName + ".download")
            check(!staging.exists() || staging.delete()) { "이전 임시 파일 정리에 실패했습니다." }
            check(directory.usableSpace > model.size + 256L * 1024 * 1024) { "여유 공간이 약 2.9GB 이상 필요합니다." }
            val request = DownloadManager.Request(Uri.parse(model.url))
                .setTitle("HJP AI 모델 준비").setDescription("완료 후 앱에서 파일 검증을 진행합니다")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverRoaming(false)
                .setAllowedNetworkTypes(if (allowMobile) DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE else DownloadManager.Request.NETWORK_WIFI)
                .setDestinationInExternalFilesDir(context, "models", staging.name)
            val id = manager.enqueue(request)
            if (!preferences.edit().putLong("download_id", id).commit()) { manager.remove(id); error("다운로드 상태 저장에 실패했습니다.") }
            observe(id)
        } }
    }

    @Synchronized fun cancelDownload() {
        if (mutableState.value.phase !in setOf("downloading", "waiting")) return
        val previous = job
        previous?.cancel()
        mutableState.value = ModelSetupState("checking", "다운로드를 취소하고 있습니다")
        job = scope.launch { guarded {
            previous?.join()
            val id = preferences.getLong("download_id", -1)
            if (id >= 0) manager.remove(id)
            check(preferences.edit().remove("download_id").commit()) { "다운로드 상태 정리에 실패했습니다." }
            val temporary = File(directory, model.fileName + ".download")
            check(!temporary.exists() || temporary.delete()) { "임시 파일 정리에 실패했습니다." }
            mutableState.value = ModelSetupState("missing", "다운로드가 취소됐습니다. 다시 시작할 수 있습니다.")
        } }
    }

    private suspend fun inspect() {
        mutableState.value = ModelSetupState()
        for (asset in ModelDownloads.bundled) {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            context.assets.open("models/${asset.fileName}").use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count; digest.update(buffer, 0, count)
                }
            }
            check(bytes == asset.size && digest.digest().joinToString("") { "%02x".format(it) } == asset.sha256) {
                "앱에 포함된 검색 모델이 손상됐습니다. 앱을 다시 설치해 주세요."
            }
        }
        bundledVerified = true
        if (installer.verified(AppContainer.resolveGenerativeArtifact(directory), model)) {
            // Recover process death between promotion and DownloadManager bookkeeping.
            val completedId = preferences.getLong("download_id", -1)
            if (completedId >= 0) {
                manager.remove(completedId)
                preferences.edit().remove("download_id").commit()
            }
            mutableState.value = ModelSetupState("ready", "AI 모델 준비 완료")
            return
        }
        val id = preferences.getLong("download_id", -1)
        if (id >= 0) observe(id)
        else mutableState.value = ModelSetupState("missing", "대화 모델을 한 번 다운로드하면 오프라인으로 사용할 수 있습니다.")
    }

    private suspend fun observe(id: Long) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val values = manager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) null else Triple(c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
            } ?: error("다운로드 기록이 없습니다. 다시 다운로드해 주세요.")
            when (values.first) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    mutableState.value = ModelSetupState("verifying", "다운로드 완료 · 파일 무결성을 검증하고 있습니다", model.size)
                    installer.promoteDownload(model)
                    manager.remove(id)
                    preferences.edit().remove("download_id").commit()
                    mutableState.value = ModelSetupState("ready", "AI 모델 준비 완료")
                    return
                }
                DownloadManager.STATUS_FAILED -> error(downloadFailureMessage(values.third))
                DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_PENDING -> mutableState.value = ModelSetupState("waiting", "Wi-Fi 또는 다운로드 재개를 기다리고 있습니다", values.second)
                else -> mutableState.value = ModelSetupState("downloading", "AI 모델 다운로드 중 · 화면을 닫아도 계속됩니다", values.second)
            }
            delay(1000)
        }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            mutableState.value = ModelSetupState(if (bundledVerified) "error" else "broken",
                if (!bundledVerified) "앱에 포함된 검색 모델을 검증하지 못했습니다. 앱을 다시 설치해 주세요."
                else if (e is IllegalStateException) e.message ?: "모델 준비 실패"
                else "모델 준비 중 저장소 또는 네트워크 오류가 발생했습니다. 다시 시도해 주세요.")
        }
    }

    companion object {
        const val TERMS_VERSION = "gemma-2026-04-01_hjp-v1"
        fun downloadFailureMessage(reason: Int): String = when (reason) {
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "저장 공간이 부족합니다. 공간을 확보한 뒤 다시 시도해 주세요."
            401, 403 -> "모델 배포 서버가 다운로드를 거부했습니다. 앱 업데이트 또는 지원이 필요한 배포 오류입니다."
            else -> "다운로드에 실패했습니다(코드 $reason). 네트워크를 확인하고 다시 시도해 주세요."
        }
    }
}
