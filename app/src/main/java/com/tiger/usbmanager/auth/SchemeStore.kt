package com.tiger.usbmanager.auth

import android.content.Context
import android.os.Build
import java.io.File
import java.io.InputStream
import java.util.UUID

object SchemeStore {
    data class Candidate(val directory: File, val manifest: SchemeManifest)
    data class Installed(val directory: File, val manifest: SchemeManifest, val revision: String)
    private val revisionPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    private fun root(context: Context) = File(context.filesDir, "recognition-schemes").apply { mkdirs() }

    /** Called once during process startup, before any import worker or dialog exists. */
    fun cleanupStaging(context: Context) {
        root(context).listFiles()?.filter {
            it.isDirectory && it.name.startsWith("staging-") && revisionPattern.matches(it.name.removePrefix("staging-"))
        }?.forEach { it.deleteRecursively() }
    }

    fun prepare(context: Context, input: InputStream): Candidate {
        val directory = File(root(context), "staging-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val manifest = SchemePackage.extract(input, directory)
            require(Build.VERSION.SDK_INT >= manifest.minSdk) { "Scheme requires API ${manifest.minSdk}" }
            require(selectAbi(manifest) != null) { "Scheme does not support this device's architecture or Android version" }
            return Candidate(directory, manifest)
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun current(context: Context): Installed? {
        val revision = RecognitionSettings.schemeRevision(context)
        if (!revisionPattern.matches(revision)) return null
        val directory = File(root(context), revision)
        return runCatching {
            val file = File(directory, "manifest.json")
            require(file.length() in 1..65536)
            val manifest = SchemePackage.parseManifest(file.readText(Charsets.UTF_8))
            require(File(directory, "entry.sh").isFile)
            Installed(directory, manifest, revision)
        }.getOrNull()
    }

    fun activate(context: Context, candidate: Candidate) {
        val packageRoot = root(context)
        require(candidate.directory.parentFile?.canonicalFile == packageRoot.canonicalFile && candidate.directory.name.startsWith("staging-"))
        val old = current(context)
        val revision = UUID.randomUUID().toString()
        val directory = File(packageRoot, revision)
        check(candidate.directory.renameTo(directory)) { "Cannot install scheme" }
        if (!RecognitionSettings.selectScheme(context, revision, candidate.manifest.id)) {
            directory.deleteRecursively()
            error("Cannot save scheme settings")
        }
        old?.directory?.deleteRecursively()
    }

    fun remove(context: Context) {
        val current = current(context)
        check(RecognitionSettings.selectScheme(context, "", "")) { "Cannot save scheme settings" }
        current?.directory?.deleteRecursively()
    }

    fun selectAbi(manifest: SchemeManifest): String? {
        if ("any" in manifest.abis) return "any"
        return Build.SUPPORTED_ABIS.firstOrNull {
            it in manifest.abis && (it != "riscv64" || Build.VERSION.SDK_INT >= 37)
        }
    }
}
