package com.tiger.usbmanager.auth

import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.StringReader
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

/** The framework JSON reader preserves token types and integer literals without bundling a parser. */
object SchemePackage {
    const val MAX_BYTES = 64L * 1024 * 1024
    private val identifier = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val safePath = Regex("[A-Za-z0-9._/-]{1,240}")
    private val digest = Regex("[0-9a-f]{64}")
    private val supportedAbis = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64", "riscv64", "any")

    fun parseManifest(json: String): SchemeManifest {
        schemeRequire(json.toByteArray(Charsets.UTF_8).size <= 64 * 1024, SchemeError.MANIFEST_TOO_LARGE)
        val strings = mutableMapOf<String, String>()
        val integers = mutableMapOf<String, String>()
        var rawAbis: List<String>? = null
        var rawFiles: Map<String, String>? = null
        try {
            JsonReader(StringReader(json)).use { reader ->
                schemeRequire(reader.peek() == JsonToken.BEGIN_OBJECT, SchemeError.INVALID_MANIFEST)
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    when (key) {
                        "id", "name", "version", "author", "description", "entry", "storageId" -> {
                            schemeRequire(reader.peek() == JsonToken.STRING, SchemeError.INVALID_FIELD, key)
                            strings[key] = reader.nextString()
                        }
                        "formatVersion", "minSdk" -> {
                            schemeRequire(reader.peek() == JsonToken.NUMBER, SchemeError.INVALID_FIELD, key)
                            integers[key] = reader.nextString()
                        }
                        "abis" -> {
                            schemeRequire(reader.peek() == JsonToken.BEGIN_ARRAY, SchemeError.INVALID_ABI_LIST)
                            val values = mutableListOf<String>()
                            reader.beginArray()
                            while (reader.hasNext()) {
                                schemeRequire(reader.peek() == JsonToken.STRING, SchemeError.INVALID_ABI)
                                values.add(reader.nextString())
                            }
                            reader.endArray()
                            rawAbis = values
                        }
                        "files" -> {
                            schemeRequire(reader.peek() == JsonToken.BEGIN_OBJECT, SchemeError.INVALID_HASH)
                            val values = mutableMapOf<String, String>()
                            reader.beginObject()
                            while (reader.hasNext()) {
                                val path = reader.nextName()
                                schemeRequire(reader.peek() == JsonToken.STRING, SchemeError.INVALID_HASH)
                                values[path] = reader.nextString()
                            }
                            reader.endObject()
                            rawFiles = values
                        }
                        else -> reader.skipValue() // Companion information and author-defined metadata.
                    }
                }
                reader.endObject()
                schemeRequire(reader.peek() == JsonToken.END_DOCUMENT, SchemeError.INVALID_MANIFEST)
            }
        } catch (_: IOException) {
            throw SchemeException(SchemeError.INVALID_MANIFEST)
        } catch (_: IllegalStateException) {
            throw SchemeException(SchemeError.INVALID_MANIFEST)
        }
        fun string(key: String, max: Int = 128): String {
            val value = strings[key] ?: throw SchemeException(SchemeError.INVALID_FIELD, key)
            return value.also {
                schemeRequire(it.isNotBlank() && it.length <= max && it.none(Char::isISOControl), SchemeError.INVALID_FIELD, key)
            }
        }
        fun integer(key: String): Int {
            val value = integers[key] ?: throw SchemeException(SchemeError.INVALID_FIELD, key)
            schemeRequire(value.matches(Regex("[0-9]+")), SchemeError.INVALID_FIELD, key)
            return value.toIntOrNull() ?: throw SchemeException(SchemeError.INVALID_FIELD, key)
        }
        schemeRequire(integer("formatVersion") == 1, SchemeError.UNSUPPORTED_FORMAT)
        val id = string("id", 64).also { schemeRequire(identifier.matches(it), SchemeError.INVALID_ID) }
        val storageId = if (strings.containsKey("storageId")) string("storageId", 64) else id
        schemeRequire(identifier.matches(storageId), SchemeError.INVALID_STORAGE_ID)
        schemeRequire(string("entry") == "entry.sh", SchemeError.INVALID_ENTRY)
        val minSdk = integer("minSdk").also { schemeRequire(it in 26..100, SchemeError.INVALID_MIN_SDK) }
        val abis = rawAbis ?: throw SchemeException(SchemeError.INVALID_ABI_LIST)
        abis.forEach { abi -> schemeRequire(abi in supportedAbis, SchemeError.UNSUPPORTED_ABI, abi) }
        schemeRequire(abis.isNotEmpty() && abis.distinct().size == abis.size && ("any" !in abis || abis.size == 1), SchemeError.INVALID_ABI_LIST)
        val files = rawFiles ?: throw SchemeException(SchemeError.INVALID_HASH)
        files.forEach { (path, hash) ->
            validatePath(path)
            schemeRequire(path != "manifest.json" && digest.matches(hash), SchemeError.INVALID_HASH)
        }
        schemeRequire(files.size in 1..127 && "entry.sh" in files, SchemeError.MISSING_ENTRY)
        return SchemeManifest(id, string("name"), string("version", 64), string("author"),
            string("description", 2000), minSdk, abis, storageId, files)
    }

    fun validatePath(path: String) {
        schemeRequire(safePath.matches(path) && !path.startsWith('/') && path.split('/').all {
            it.isNotEmpty() && it != "." && it != ".."
        }, SchemeError.UNSAFE_PATH, path)
    }

    /** The caller provides an empty staging directory; it is removed if validation fails. */
    fun extract(input: InputStream, directory: File): SchemeManifest {
        schemeRequire(directory.isDirectory && directory.list()?.isEmpty() == true, SchemeError.INVALID_STAGING)
        try {
            val names = mutableSetOf<String>()
            val hashes = mutableMapOf<String, String>()
            var total = 0L
            var count = 0
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    schemeRequire(++count <= 256, SchemeError.TOO_MANY_ENTRIES)
                    val path = entry.name.removeSuffix("/")
                    validatePath(path)
                    schemeRequire(names.add(path), SchemeError.DUPLICATE_PATH, path)
                    val file = File(directory, path)
                    schemeRequire(file.canonicalPath.startsWith(directory.canonicalPath + File.separator), SchemeError.UNSAFE_PATH, path)
                    if (entry.isDirectory) {
                        schemeRequire(file.mkdirs() || file.isDirectory, SchemeError.CREATE_DIRECTORY)
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
                                schemeRequire(size <= 32L * 1024 * 1024 && total <= MAX_BYTES && (path != "manifest.json" || size <= 64 * 1024), SchemeError.PACKAGE_TOO_LARGE)
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
            schemeRequire(hashes.keys == manifest.files.keys + "manifest.json", SchemeError.FILE_LIST_MISMATCH)
            schemeRequire(manifest.files.all { (path, hash) -> hashes[path] == hash }, SchemeError.CHECKSUM_MISMATCH)
            schemeRequire(File(directory, "entry.sh").length() > 0, SchemeError.EMPTY_ENTRY)
            return manifest
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }
}
