package com.tiger.usbmanager.auth

import com.google.gson.JsonParser
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class SchemeManifest(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val minSdk: Int,
    val abis: List<String>,
    val storageId: String,
    val files: Map<String, String>,
)

/** Package parsing is deliberately independent of Android, so malformed imports can be tested. */
object SchemePackage {
    const val MAX_BYTES = 64L * 1024 * 1024
    private val identifier = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val safePath = Regex("[A-Za-z0-9._/-]{1,240}")
    private val digest = Regex("[0-9a-f]{64}")
    private val supportedAbis = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64", "riscv64", "any")

    fun parseManifest(json: String): SchemeManifest {
        require(json.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "Manifest is too large" }
        val root = JsonParser.parseString(json).asJsonObject
        fun string(key: String, max: Int = 128): String {
            val value = root.get(key)
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Invalid $key" }
            return value.asString.also { require(it.isNotBlank() && it.length <= max && it.none(Char::isISOControl)) { "Invalid $key" } }
        }
        fun integer(key: String): Int {
            val value = root.get(key)
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.toString().matches(Regex("[0-9]+"))) { "Invalid $key" }
            return requireNotNull(value.asString.toIntOrNull()) { "Invalid $key" }
        }
        require(integer("formatVersion") == 1) { "Unsupported scheme format version" }
        val id = string("id", 64).also { require(identifier.matches(it)) { "Invalid scheme ID" } }
        val storageId = if (root.has("storageId")) string("storageId", 64) else id
        require(identifier.matches(storageId)) { "Invalid storage ID" }
        require(string("entry") == "entry.sh") { "The entry point must be entry.sh" }
        val minSdk = integer("minSdk").also { require(it in 26..100) { "Invalid minimum SDK" } }
        val abis = root.getAsJsonArray("abis").map {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Invalid ABI" }
            it.asString.also { abi -> require(abi in supportedAbis) { "Unsupported ABI: $abi" } }
        }
        require(abis.isNotEmpty() && abis.distinct().size == abis.size && ("any" !in abis || abis.size == 1)) { "Invalid ABI list" }
        val files = root.getAsJsonObject("files").entrySet().associate { (path, hash) ->
            validatePath(path)
            require(path != "manifest.json" && hash.isJsonPrimitive && hash.asJsonPrimitive.isString && digest.matches(hash.asString)) { "Invalid file hash" }
            path to hash.asString
        }
        require(files.size in 1..127 && "entry.sh" in files) { "Missing entry.sh" }
        return SchemeManifest(id, string("name"), string("version", 64), string("author"),
            string("description", 2000), minSdk, abis, storageId, files)
    }

    fun validatePath(path: String) {
        require(safePath.matches(path) && !path.startsWith('/') && path.split('/').all {
            it.isNotEmpty() && it != "." && it != ".."
        }) { "Unsafe package path: $path" }
    }

    /** The caller provides an empty staging directory; it is removed if validation fails. */
    fun extract(input: InputStream, directory: File): SchemeManifest {
        require(directory.isDirectory && directory.list()?.isEmpty() == true) { "Staging directory is not empty" }
        try {
            val names = mutableSetOf<String>()
            val hashes = mutableMapOf<String, String>()
            var total = 0L
            var count = 0
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= 256) { "Too many ZIP entries" }
                    val path = entry.name.removeSuffix("/")
                    validatePath(path)
                    require(names.add(path)) { "Duplicate ZIP path: $path" }
                    val file = File(directory, path)
                    require(file.canonicalPath.startsWith(directory.canonicalPath + File.separator)) { "Unsafe ZIP path" }
                    if (entry.isDirectory) {
                        require(file.mkdirs() || file.isDirectory) { "Cannot create directory" }
                    } else {
                        file.parentFile!!.mkdirs()
                        val sha = MessageDigest.getInstance("SHA-256")
                        var size = 0L
                        file.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                size += read; total += read
                                require(size <= 32L * 1024 * 1024 && total <= MAX_BYTES && (path != "manifest.json" || size <= 64 * 1024)) { "Package is too large" }
                                sha.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                            }
                        }
                        hashes[path] = sha.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    }
                    zip.closeEntry()
                }
            }
            val manifest = parseManifest(File(directory, "manifest.json").readText(Charsets.UTF_8))
            require(hashes.keys == manifest.files.keys + "manifest.json") { "File list does not match manifest" }
            require(manifest.files.all { (path, hash) -> hashes[path] == hash }) { "Package checksum mismatch" }
            require(File(directory, "entry.sh").length() > 0) { "Empty entry point" }
            return manifest
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }
}
