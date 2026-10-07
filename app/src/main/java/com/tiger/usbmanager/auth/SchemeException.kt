package com.tiger.usbmanager.auth

/** Stable error codes keep package validation independent of Android and UI languages. */
enum class SchemeError {
    INVALID_MANIFEST, MANIFEST_TOO_LARGE, INVALID_FIELD, UNSUPPORTED_FORMAT,
    INVALID_ID, INVALID_STORAGE_ID, INVALID_ENTRY, INVALID_MIN_SDK,
    INVALID_ABI, UNSUPPORTED_ABI, INVALID_ABI_LIST, INVALID_HASH, MISSING_ENTRY,
    UNSAFE_PATH, INVALID_STAGING, TOO_MANY_ENTRIES, DUPLICATE_PATH,
    CREATE_DIRECTORY, PACKAGE_TOO_LARGE, FILE_LIST_MISMATCH, CHECKSUM_MISMATCH,
    EMPTY_ENTRY, MIN_SDK_REQUIRED, UNSUPPORTED_DEVICE, INSTALL_FAILED,
    SAVE_FAILED, OPEN_FAILED, SCHEME_REQUIRED, RUNTIME_REQUIRED,
    RESTORE_IMPORT_FAILED, RESTORE_REMOVE_FAILED,
}

class SchemeException(val code: SchemeError, vararg val arguments: Any) :
    IllegalArgumentException(code.name)

internal fun schemeRequire(condition: Boolean, code: SchemeError, vararg arguments: Any) {
    if (!condition) throw SchemeException(code, *arguments)
}
