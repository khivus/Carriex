package com.example.carriex.updates

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import com.example.carriex.BuildConfig
import com.example.carriex.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Public GitHub releases only. No account token or private-repository credential is shipped. */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = application.getSharedPreferences("chat_settings", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()
    var onChanged: (() -> Unit)? = null
    var release: ReleaseInfo? = null; private set
    var downloaded: File? = null; private set
    var checking = false; private set
    var downloading = false; private set
    var progress = 0; private set
    var error: String? = null; private set
    var checked = false; private set
    var dismissed = false; private set
    val visible get() = release != null && !dismissed
    val providerAuthority get() = "${BuildConfig.APPLICATION_ID}.updates"
    val releasesUrl get() = "https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases"

    fun check(manual: Boolean = false) {
        if(checking || downloading || (checked && !manual)) return
        checking = true; error = null; onChanged?.invoke()
        worker.execute {
            val result = runCatching {
                client.newCall(Request.Builder().url("https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}/releases/latest")
                    .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2026-03-10")
                    .header("User-Agent", "Carriex/${BuildConfig.VERSION_NAME}").build()).execute().use { response ->
                    if(response.code == 404) return@use null
                    check(response.isSuccessful) { getApplication<Application>().getString(R.string.ui_update_check_failed, response.code) }
                    check(response.body?.source()?.request(1_000_001) != true) { getApplication<Application>().getString(R.string.ui_update_response_large) }
                    ReleaseInfo.parse(JSONObject(response.body!!.string()), BuildConfig.UPDATE_REPOSITORY, Build.SUPPORTED_ABIS.toList())
                        ?.takeIf { ReleaseInfo.newer(it.tag, BuildConfig.VERSION_NAME) && (manual || prefs.getString("skipped_update", null) != it.tag) }
                }
            }
            handler.post {
                checking = false; checked = true
                if(result.isSuccess) {
                    val next = result.getOrNull()
                    if(next?.tag != release?.tag) downloaded = null
                    release = next; dismissed = false
                } else if(manual) error = result.exceptionOrNull()?.message
                onChanged?.invoke()
            }
        }
    }

    fun dismiss(skip: Boolean) {
        if(skip) prefs.edit().putString("skipped_update", release?.tag).apply()
        dismissed = true; onChanged?.invoke()
    }

    fun download() {
        val update = release ?: return
        if(downloading) return
        downloading = true; error = null; progress = 0; onChanged?.invoke()
        worker.execute {
            val folder = File(getApplication<Application>().cacheDir, "updates").apply { mkdirs() }
            val partial = File(folder, "update.part")
            val destination = File(folder, "update.apk")
            val result = runCatching {
                client.newCall(Request.Builder().url(update.downloadUrl).build()).execute().use { response ->
                    check(response.isSuccessful) { getApplication<Application>().getString(R.string.ui_update_download_failed, response.code) }
                    response.body!!.byteStream().use { source -> partial.outputStream().use { output ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        var lastProgress = -1
                        while(true) {
                            val read = source.read(buffer)
                            if(read == -1) break
                            total += read
                            check(total <= update.size && total <= 300_000_000) { getApplication<Application>().getString(R.string.ui_update_size_mismatch) }
                            output.write(buffer, 0, read); digest.update(buffer, 0, read)
                            val value = (total * 100 / update.size).toInt()
                            if(value != lastProgress) { lastProgress = value; handler.post { progress = value; onChanged?.invoke() } }
                        }
                        check(total == update.size) { getApplication<Application>().getString(R.string.ui_update_size_mismatch) }
                        val hash = digest.digest().joinToString("") { "%02x".format(it) }
                        check(update.sha256 == null || hash.equals(update.sha256, true)) { getApplication<Application>().getString(R.string.ui_update_hash_mismatch) }
                    } }
                }
                validatePackage(partial)
                check(partial.renameTo(destination)) { getApplication<Application>().getString(R.string.ui_update_download_unavailable) }
                destination
            }
            if(result.isFailure) partial.delete()
            handler.post {
                downloading = false; downloaded = result.getOrNull(); error = result.exceptionOrNull()?.message; onChanged?.invoke()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun validatePackage(file: File) {
        val app = getApplication<Application>()
        val pm = app.packageManager
        val flags = if(Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags)
        val installed = pm.getPackageInfo(app.packageName, flags)
        val newer = archive != null && (if(Build.VERSION.SDK_INT >= 28) archive.longVersionCode > installed.longVersionCode else archive.versionCode > installed.versionCode)
        check(archive?.packageName == app.packageName && newer && (archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) {
            app.getString(R.string.ui_update_wrong_package)
        }
        fun signatures(info: android.content.pm.PackageInfo) = (if(Build.VERSION.SDK_INT >= 28)
            info.signingInfo?.apkContentsSigners else info.signatures).orEmpty().map { it.toCharsString() }.toSet()
        check(signatures(installed).isNotEmpty() && signatures(archive!!) == signatures(installed)) { app.getString(R.string.ui_update_wrong_signature) }
    }

    override fun onCleared() { onChanged = null; client.dispatcher.cancelAll(); worker.shutdownNow() }
}
