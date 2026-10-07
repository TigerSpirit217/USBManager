package com.tiger.usbmanager.auth

import com.google.gson.JsonParser
import com.google.gson.JsonParseException
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
        schemeRequire(json.toByteArray(Charsets.UTF_8).size <= 64 * 1024, SchemeError.MANIFEST_TOO_LARGE)
        val element = try {
            JsonParser.parseString(json)
        } catch (_: JsonParseException) {
            throw SchemeException(SchemeError.INVALID_MANIFEST)
        }
        schemeRequire(element.isJsonObject, SchemeError.INVALID_MANIFEST)
        val root = element.asJsonObject
        fun string(key: String, max: Int = 128): String {
            val value = root.get(key)
            schemeRequire(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString, SchemeError.INVALID_FIELD, key)
            return value!!.asString.also {
                schemeRequire(it.isNotBlank() && it.length <= max && it.none(Char::isISOControl), SchemeError.INVALID_FIELD, key)
            }
        }
        fun integer(key: String): Int {
            val value = root.get(key)
            schemeRequire(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.toString().matches(Regex("[0-9]+")), SchemeError.INVALID_FIELD, key)
            return value!!.asString.toIntOrNull() ?: throw SchemeException(SchemeError.INVALID_FIELD, key)
        }
        schemeRequire(integer("formatVersion") == 1, SchemeError.UNSUPPORTED_FORMAT)
        val id = string("id", 64).also { schemeRequire(identifier.matches(it), SchemeError.INVALID_ID) }
        val storageId = if (root.has("storageId")) string("storageId", 64) else id
        schemeRequire(identifier.matches(storageId), SchemeError.INVALID_STORAGE_ID)
        schemeRequire(string("entry") == "entry.sh", SchemeError.INVALID_ENTRY)
        val minSdk = integer("minSdk").also { schemeRequire(it in 26..100, SchemeError.INVALID_MIN_SDK) }
        schemeRequire(root.get("abis")?.isJsonArray == true, SchemeError.INVALID_ABI_LIST)
        val abis = root.getAsJsonArray("abis").map {
            schemeRequire(it.isJsonPrimitive && it.asJsonPrimitive.isString, SchemeError.INVALID_ABI)
            it.asString.also { abi -> schemeRequire(abi in supportedAbis, SchemeError.UNSUPPORTED_ABI, abi) }
        }
        schemeRequire(abis.isNotEmpty() && abis.distinct().size == abis.size && ("any" !in abis || abis.size == 1), SchemeError.INVALID_ABI_LIST)
        schemeRequire(root.get("files")?.isJsonObject == true, SchemeError.INVALID_HASH)
        val files = root.getAsJsonObject("files").entrySet().associate { (path, hash) ->
            validatePath(path)
            schemeRequire(path != "manifest.json" && hash.isJsonPrimitive && hash.asJsonPrimitive.isString && digest.matches(hash.asString), SchemeError.INVALID_HASH)
            path to hash.asString
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
