package com.tiger.usbmanager.auth

import android.content.Context
import android.util.Base64
import com.tiger.usbmanager.policy.UsbMode
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

data class KnownComputer(val id: String, val label: String, val lastSeen: Long, val mode: UsbMode?, val adb: Boolean)
data class AuthResult(val status: String, val id: String = "", val label: String = "", val mode: UsbMode? = null,
                      val adb: Boolean = false, val detail: String = "")

/** Executes the imported scheme's control interface; no computer authentication lives here. */
object RootAuthManager {
    private val schemeFilesLock = ReentrantReadWriteLock()
    enum class DetectionFailure { ROOT_REQUIRED, UNSUPPORTED }

    data class Paths(val script: String, val state: String, val abi: String, val appProcess: String)
    data class Detection(
        val supported: Boolean,
        val backend: String = RecognitionSettings.BACKEND_NONE,
        val detail: String = "",
        val failure: DetectionFailure? = null,
    )

    private fun prepare(context: Context): Paths {
        val installed = SchemeStore.current(context) ?: throw SchemeException(SchemeError.SCHEME_REQUIRED)
        schemeRequire(android.os.Build.VERSION.SDK_INT >= installed.manifest.minSdk, SchemeError.MIN_SDK_REQUIRED, installed.manifest.minSdk)
        val abi = SchemeStore.selectAbi(installed.manifest) ?: throw SchemeException(SchemeError.UNSUPPORTED_DEVICE)
        val is64Bit = when (abi) {
            "any" -> android.os.Process.is64Bit()
            "armeabi-v7a", "x86" -> false
            else -> true
        }
        val matchingProcess = "/system/bin/app_process${if (is64Bit) "64" else "32"}"
        val appProcess = when {
            File(matchingProcess).canExecute() -> matchingProcess
            is64Bit == android.os.Process.is64Bit() -> "/system/bin/app_process"
            else -> throw SchemeException(SchemeError.RUNTIME_REQUIRED, if (is64Bit) 64 else 32)
        }
        return Paths(File(installed.directory, "entry.sh").absolutePath,
            "/data/adb/usbmanager-schemes/${installed.manifest.storageId}", abi, appProcess)
    }

    @Synchronized
    fun detect(context: Context): Detection {
        val installed = SchemeStore.current(context)
            ?: return Detection(false, detail = "Import a recognition scheme first", failure = DetectionFailure.UNSUPPORTED)
        RecognitionSettings.setEnabled(context, false)
        RecognitionSettings.saveDetection(context, installed.revision, false)
        if (!isRootAuthorized()) {
            RecognitionSettings.saveDetection(context, installed.revision, false)
            return Detection(false, detail = "Root access was not granted", failure = DetectionFailure.ROOT_REQUIRED)
        }
        RecognitionSettings.markTransition(context, 50_000)
        RecognitionSettings.markSchemeExecution(context)
        return try {
            val result = runRoot(prepare(context), "detect", "closed", 45_000)
            val supported = result.code == 0 && result.output.lineSequence().any { it.trim() == "USBMGR_SUPPORTED 1" }
            RecognitionSettings.saveDetection(context, installed.revision, supported)
            Detection(supported, installed.manifest.id, result.output,
                if (supported) null else DetectionFailure.UNSUPPORTED)
        } finally {
            RecognitionSettings.markTransition(context, 3_000)
        }
    }

    @Synchronized
    fun start(context: Context, mode: UsbMode, adb: Boolean): AuthResult {
        if (!RecognitionSettings.isEnabled(context)) return AuthResult("FAILED", detail = "Recognition disabled")
        // The scheme supplies the initial computer name after successful pairing.
        val encodedProfile = profile("", mode, adb, allowEmptyLabel = true)
        val paths = prepare(context)
        RecognitionSettings.markTransition(context, 125_000L)
        return try {
            val result = runRoot(paths, "start", "pair", 120_000, encodedProfile)
            if (result.code == 0) parseAuthResult(result.output) else AuthResult("FAILED", detail = result.output.takeLast(240))
        } finally {
            RecognitionSettings.markTransition(context, 3_000L)
        }
    }

    @Synchronized
    fun recognize(context: Context): AuthResult {
        if (!RecognitionSettings.isEnabled(context)) return AuthResult("FAILED", detail = "Recognition disabled")
        val paths = prepare(context)
        val result = runRoot(paths, "start", "closed", 80_000)
        return if (result.code == 0) parseAuthResult(result.output) else AuthResult("FAILED", detail = result.output.takeLast(240))
    }

    fun restore(context: Context): Boolean = schemeFilesLock.readLock().withLock {
        restoreCurrent(context)
    }

    fun restoreIfCurrent(context: Context, revision: String): Boolean = schemeFilesLock.readLock().withLock {
        if (RecognitionSettings.schemeRevision(context) != revision) return@withLock true
        restoreCurrent(context)
    }

    private fun restoreCurrent(context: Context): Boolean {
        if (SchemeStore.current(context) == null) return true
        RecognitionSettings.markTransition(context, 20_000L)
        val paths = prepare(context)
        val result = runRoot(paths, "restore", "closed", 20_000)
        return result.code == 0 && result.output.lineSequence().any { it.trim() == "USBMGR_RESTORED" }
    }

    @Synchronized
    fun list(context: Context): List<KnownComputer> {
        val paths = prepare(context)
        val result = runRoot(paths, "list", "closed", 10_000)
        if (result.code != 0) error(result.output.takeLast(240))
        return result.output.lineSequence().mapNotNull { line ->
            val fields = line.trim().split('|')
            if (fields.size != 5 || !fields[0].matches(Regex("[0-9a-f]{64}")) || fields[4] !in setOf("true", "false")) return@mapNotNull null
            runCatching {
                KnownComputer(
                    fields[0],
                    String(Base64.decode(fields[1], Base64.DEFAULT), StandardCharsets.UTF_8),
                    fields[2].toLong(),
                    UsbMode.entries.firstOrNull { it.wireValue == fields[3] },
                    fields[4] == "true",
                )
            }.getOrNull()
        }.toList()
    }

    @Synchronized
    fun delete(context: Context, id: String): Boolean {
        require(id.matches(Regex("[0-9a-f]{64}")))
        val paths = prepare(context)
        val result = runRoot(paths, "delete", id, 10_000)
        return result.code == 0 && result.output.lineSequence().any { it.trim() == "DELETED" }
    }

    @Synchronized
    fun update(context: Context, id: String, label: String, mode: UsbMode, adb: Boolean): Boolean {
        require(id.matches(Regex("[0-9a-f]{64}")))
        val result = runRoot(prepare(context), "edit", id, 10_000, profile(label, mode, adb))
        return result.code == 0 && result.output.lineSequence().any { it.trim() == "UPDATED" }
    }

    @Synchronized
    fun installScheme(context: Context, candidate: SchemeStore.Candidate) {
        schemeFilesLock.writeLock().withLock {
            val restoreNeeded = RecognitionSettings.hasExecutedScheme(context)
            RecognitionSettings.setEnabled(context, false)
            if (restoreNeeded) schemeRequire(restore(context), SchemeError.RESTORE_IMPORT_FAILED)
            SchemeStore.activate(context, candidate)
            RecognitionSettings.clearTransition(context)
        }
    }

    @Synchronized
    fun removeScheme(context: Context) {
        schemeFilesLock.writeLock().withLock {
            val restoreNeeded = RecognitionSettings.hasExecutedScheme(context)
            RecognitionSettings.setEnabled(context, false)
            if (restoreNeeded) schemeRequire(restore(context), SchemeError.RESTORE_REMOVE_FAILED)
            SchemeStore.remove(context)
            RecognitionSettings.clearTransition(context)
        }
    }

    private fun profile(label: String, mode: UsbMode, adb: Boolean, allowEmptyLabel: Boolean = false): String {
        require((allowEmptyLabel || label.trim().isNotEmpty()) && label.trim().length <= 64 && label.none { it.isISOControl() })
        return Base64.encodeToString(label.trim().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP) +
            ",${mode.wireValue},$adb"
    }

    private fun parseAuthResult(output: String): AuthResult {
        val value = output.lineSequence().firstOrNull { it.startsWith("AUTH_RESULT ") }
            ?.removePrefix("AUTH_RESULT ")?.trim()
            ?: return AuthResult(if (output.contains("timeout", ignoreCase = true)) "TIMEOUT" else "FAILED",
                detail = output.takeLast(240))
        if (value == "TIMEOUT") return AuthResult("TIMEOUT")
        val fields = value.split('|')
        if (fields.size != 5 || !fields[1].matches(Regex("[0-9a-f]{64}")) || fields[4] !in setOf("true", "false")) return AuthResult("FAILED", detail = value)
        val label = runCatching { String(Base64.decode(fields[2], Base64.DEFAULT), StandardCharsets.UTF_8) }
            .getOrDefault("")
        if (fields[0] !in setOf("KNOWN", "PAIRED", "UNKNOWN")) return AuthResult("FAILED", detail = value)
        val mode = UsbMode.entries.firstOrNull { it.wireValue == fields[3] }
        if (fields[0] != "UNKNOWN" && mode == null) return AuthResult("FAILED", detail = value)
        return AuthResult(fields[0], fields[1], label, mode, fields[4] == "true")
    }

    private data class Result(val code: Int, val output: String)

    private fun isRootAuthorized(): Boolean {
        val process = runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "id -u")) }.getOrNull() ?: return false
        return try {
            if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroy()
                process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (process.isAlive) process.destroyForcibly()
                false
            } else {
                process.inputStream.bufferedReader().use { reader ->
                    reader.readLines().any { it.trim() == "0" }
                }
            }
        } catch (_: Exception) {
            false
        } finally {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }

    private fun runRoot(paths: Paths, action: String, mode: String, timeoutMs: Long, profile: String = "none"): Result {
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        val command = listOf("/system/bin/sh", paths.script, action, paths.state, paths.abi, paths.appProcess, mode, profile)
            .joinToString(" ", transform = ::quote)
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val output = StringBuilder()
        fun drain(stream: java.io.InputStream) = Thread {
            runCatching {
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> synchronized(output) {
                        output.appendLine(line)
                        if (output.length > 1024 * 1024) output.delete(0, output.length - 1024 * 1024)
                    } }
                }
            }
        }
        val outThread = drain(process.inputStream)
        val errorThread = drain(process.errorStream)
        outThread.start(); errorThread.start()
        val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            outThread.join(1_000); errorThread.join(1_000)
            return Result(124, synchronized(output) { output.appendLine("timeout").toString() })
        }
        outThread.join(1_000); errorThread.join(1_000)
        return Result(process.exitValue(), synchronized(output) { output.toString() })
    }
}
