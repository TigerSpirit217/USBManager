package com.tiger.usbmanager.bridge

import android.content.Context
import android.util.Log
import com.tiger.usbmanager.policy.UsbMode
import java.io.File

/** Writes one manual USB choice for the KernelSU service to consume. */
object UsbConfigSender {
    private const val TAG = "USBManager"

    fun apply(context: Context, mode: UsbMode, adb: Boolean, token: Int) {
        write(
            context,
            listOf("apply", token.toString(), mode.wireValue, if (adb) "1" else "0")
                .joinToString("|"),
        )
    }

    fun close(context: Context, token: Int) {
        write(context, "close|$token")
    }

    private fun write(context: Context, payload: String) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val target = File(dir, "command")
            val temp = File(dir, "command.tmp")
            temp.writeText(payload, Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                target.writeText(payload, Charsets.UTF_8)
                temp.delete()
            }
            Log.i(TAG, "[KSU-UI] response written type=${payload.substringBefore('|')}")
        }.onFailure { Log.e(TAG, "[KSU-UI] failed to write response", it) }
    }
}
