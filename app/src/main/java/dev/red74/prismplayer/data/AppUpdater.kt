package dev.red74.prismplayer.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Downloads a newer release of the app and hands it to Android's own installer, which asks the user to confirm.
 *
 * Everything runs off the main thread and talks only to GitHub, one download at a time; the lists' provider is
 * never contacted. Before the installer opens, the file is checked twice: its SHA-256 must match the one published
 * with the release, and it must be signed by the same key as the installed app (or by a newer key that the
 * installed one handed over to, see the signing notes in docs/RELEASE.md). Anything else is refused, so the update
 * can never replace Prism with something that is not Prism.
 */
class AppUpdater(private val context: Context) {
    enum class Problem { DOWNLOAD, CORRUPT, SIGNATURE, INSTALLER, OLD_ANDROID }

    class Failure(val problem: Problem) : Exception(problem.name)

    private val dir get() = File(context.cacheDir, DIR)

    /** The downloaded APK, kept until the next start so "Update" again reopens the installer without downloading. */
    val downloaded: File get() = File(dir, FILE)

    /** Removes a copy left from an earlier update (after an update the app starts fresh, so it is no longer needed). */
    fun cleanUp() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Downloads and checks [release]; [onProgress] gets 0..100 (or -1 while the size is unknown). */
    suspend fun download(release: UpdateCheck.AppRelease, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) throw Failure(Problem.OLD_ANDROID)
        dir.mkdirs()
        cleanUp()
        val expectedSha = release.shaUrl?.let { UpdateCheck.fetchText(it) }?.let(UpdateCheck::shaFromFile)
        val part = File(dir, "$FILE.part")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val connection = URL(release.apkUrl).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "Prism/1 (Android TV)")
                if (connection.responseCode !in 200..299) throw Failure(Problem.DOWNLOAD)
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
                if (total > MAX_APK_BYTES) throw Failure(Problem.CORRUPT)
                var read = 0L
                var lastShown = -2
                connection.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            read += n
                            if (read > MAX_APK_BYTES) throw Failure(Problem.CORRUPT)
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            val percent = if (total > 0) (read * 100 / total).toInt().coerceIn(0, 100) else -1
                            if (percent != lastShown) {
                                lastShown = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Failure) {
            part.delete()
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            part.delete()
            throw e
        } catch (e: Exception) {
            part.delete()
            throw Failure(Problem.DOWNLOAD)
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (expectedSha != null && sha != expectedSha) {
            part.delete()
            throw Failure(Problem.CORRUPT)
        }
        val apk = downloaded
        if (!part.renameTo(apk)) {
            part.delete()
            throw Failure(Problem.DOWNLOAD)
        }
        when (checkSigner(apk)) {
            SignerCheck.MISMATCH -> {
                apk.delete()
                throw Failure(Problem.SIGNATURE)
            }
            SignerCheck.NOT_AN_APK -> {
                apk.delete()
                throw Failure(Problem.CORRUPT)
            }
            SignerCheck.SAME, SignerCheck.UNKNOWN -> apk
        }
    }

    /** Opens Android's installer on [apk]. The first time Android may ask to allow Prism to install apps. */
    fun openInstaller(apk: File) {
        if (!apk.isFile) throw Failure(Problem.DOWNLOAD)
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            throw Failure(Problem.INSTALLER)
        }
    }

    private enum class SignerCheck { SAME, MISMATCH, NOT_AN_APK, UNKNOWN }

    private fun checkSigner(apk: File): SignerCheck {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val flags = PackageManager.GET_SIGNING_CERTIFICATES
                val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return SignerCheck.NOT_AN_APK
                if (archive.packageName != context.packageName) return SignerCheck.MISMATCH
                val installed = pm.getPackageInfo(context.packageName, flags)
                val mine = currentSigners(installed) ?: return SignerCheck.UNKNOWN
                val theirs = acceptedSigners(archive) ?: return SignerCheck.UNKNOWN
                if (mine.isNotEmpty() && mine.all { it in theirs }) SignerCheck.SAME else SignerCheck.MISMATCH
            } else {
                @Suppress("DEPRECATION")
                val flags = PackageManager.GET_SIGNATURES
                @Suppress("DEPRECATION")
                val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return SignerCheck.NOT_AN_APK
                if (archive.packageName != context.packageName) return SignerCheck.MISMATCH
                @Suppress("DEPRECATION")
                val mine = pm.getPackageInfo(context.packageName, flags).signatures?.toSet()
                @Suppress("DEPRECATION")
                val theirs = archive.signatures?.toSet()
                if (mine.isNullOrEmpty() || theirs.isNullOrEmpty()) SignerCheck.UNKNOWN
                else if (mine == theirs) SignerCheck.SAME else SignerCheck.MISMATCH
            }
        } catch (e: Exception) {
            SignerCheck.UNKNOWN
        }
    }

    /** The key(s) the installed app is signed with now. */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun currentSigners(info: PackageInfo): Set<Signature>? {
        val signing = info.signingInfo ?: return null
        return if (signing.hasMultipleSigners()) signing.apkContentsSigners?.toSet()
        else signing.signingCertificateHistory?.lastOrNull()?.let { setOf(it) }
    }

    /** Every key the new APK speaks for: its signer plus the older keys it rotated away from (v3 lineage). */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun acceptedSigners(info: PackageInfo): Set<Signature>? {
        val signing = info.signingInfo ?: return null
        val list = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        return list?.toSet()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val DIR = "updates"
        private const val FILE = "prism-update.apk"
        private const val APK_MIME = "application/vnd.android.package-archive"
        const val AUTHORITY_SUFFIX = ".updates"
        /** Far above the real size (a few MB): only a guard against filling the stick's storage. */
        private const val MAX_APK_BYTES = 200L * 1024 * 1024
    }
}
