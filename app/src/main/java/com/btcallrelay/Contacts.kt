package com.btcallrelay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import java.util.concurrent.ConcurrentHashMap

/** Looks up contact names on this phone (only if READ_CONTACTS was allowed). */
object Contacts {
    private val cache = ConcurrentHashMap<String, String>()
    private const val NONE = "\u0000"

    fun name(ctx: Context, number: String): String? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val hit = cache[number]
        if (hit != null) return hit.takeIf { it != NONE }
        val found = runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        cache[number] = found ?: NONE
        return found
    }
}
