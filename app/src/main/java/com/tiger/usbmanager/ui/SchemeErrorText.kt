package com.tiger.usbmanager.ui

import android.content.Context
import android.util.Log
import com.tiger.usbmanager.R
import com.tiger.usbmanager.auth.SchemeError
import com.tiger.usbmanager.auth.SchemeException
import java.io.IOException

/** Raw exceptions and scheme output are diagnostic data, not translated UI messages. */
internal fun Context.schemeErrorText(error: Throwable?): String {
    Log.w("USBManager", "Scheme operation failed", error)
    val reason = if (error is SchemeException) {
        val resource = when (error.code) {
            SchemeError.INVALID_MANIFEST -> R.string.scheme_error_invalid_manifest
            SchemeError.MANIFEST_TOO_LARGE -> R.string.scheme_error_manifest_too_large
            SchemeError.INVALID_FIELD -> R.string.scheme_error_invalid_field
            SchemeError.UNSUPPORTED_FORMAT -> R.string.scheme_error_unsupported_format
            SchemeError.INVALID_ID -> R.string.scheme_error_invalid_id
            SchemeError.INVALID_STORAGE_ID -> R.string.scheme_error_invalid_storage_id
            SchemeError.INVALID_ENTRY -> R.string.scheme_error_invalid_entry
            SchemeError.INVALID_MIN_SDK -> R.string.scheme_error_invalid_min_sdk
            SchemeError.INVALID_ABI -> R.string.scheme_error_invalid_abi
            SchemeError.UNSUPPORTED_ABI -> R.string.scheme_error_unsupported_abi
            SchemeError.INVALID_ABI_LIST -> R.string.scheme_error_invalid_abi_list
            SchemeError.INVALID_HASH -> R.string.scheme_error_invalid_hash
            SchemeError.MISSING_ENTRY -> R.string.scheme_error_missing_entry
            SchemeError.UNSAFE_PATH -> R.string.scheme_error_unsafe_path
            SchemeError.INVALID_STAGING -> R.string.scheme_error_invalid_staging
            SchemeError.TOO_MANY_ENTRIES -> R.string.scheme_error_too_many_entries
            SchemeError.DUPLICATE_PATH -> R.string.scheme_error_duplicate_path
            SchemeError.CREATE_DIRECTORY -> R.string.scheme_error_create_directory
            SchemeError.PACKAGE_TOO_LARGE -> R.string.scheme_error_package_too_large
            SchemeError.FILE_LIST_MISMATCH -> R.string.scheme_error_file_list_mismatch
            SchemeError.CHECKSUM_MISMATCH -> R.string.scheme_error_checksum_mismatch
            SchemeError.EMPTY_ENTRY -> R.string.scheme_error_empty_entry
            SchemeError.MIN_SDK_REQUIRED -> R.string.scheme_error_min_sdk_required
            SchemeError.UNSUPPORTED_DEVICE -> R.string.scheme_error_unsupported_device
            SchemeError.INSTALL_FAILED -> R.string.scheme_error_install_failed
            SchemeError.SAVE_FAILED -> R.string.scheme_error_save_failed
            SchemeError.OPEN_FAILED -> R.string.scheme_error_open_failed
            SchemeError.SCHEME_REQUIRED -> R.string.auth_scheme_required
            SchemeError.RUNTIME_REQUIRED -> R.string.scheme_error_runtime_required
            SchemeError.RESTORE_IMPORT_FAILED -> R.string.scheme_error_restore_import_failed
            SchemeError.RESTORE_REMOVE_FAILED -> R.string.scheme_error_restore_remove_failed
        }
        getString(resource, *error.arguments)
    } else {
        getString(if (error is IOException || error is SecurityException)
            R.string.scheme_error_io else R.string.scheme_error_unknown)
    }
    return getString(R.string.auth_scheme_error, reason)
}
