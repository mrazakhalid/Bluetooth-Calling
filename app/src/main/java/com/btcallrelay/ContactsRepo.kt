package com.btcallrelay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

data class Person(val id: Long, val name: String, val starred: Boolean, val photo: String?,
                  val numbers: List<String> = emptyList())

/** Contacts on THIS phone (the Samsung), with duplicates merged by name. */
object ContactsRepo {
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val photos = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun allowed(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun load(ctx: Context, done: (List<Person>) -> Unit) {
        if (!allowed(ctx)) return done(emptyList())
        val app = ctx.applicationContext
        io.execute {
            val byName = LinkedHashMap<String, Person>()
            runCatching {
                app.contentResolver.query(ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                        ContactsContract.Contacts.STARRED, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI),
                    "${ContactsContract.Contacts.HAS_PHONE_NUMBER}=1", null,
                    "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC")?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1)?.trim().orEmpty()
                        if (name.isEmpty()) continue
                        val p = Person(c.getLong(0), name, c.getInt(2) == 1, c.getString(3))
                        val key = name.lowercase()
                        val old = byName[key]
                        // Keep the best copy of a duplicate: starred first, then one with a photo.
                        if (old == null || (p.starred && !old.starred) || (old.photo == null && p.photo != null && p.starred == old.starred))
                            byName[key] = if (old != null && old.starred) p.copy(starred = true) else p
                    }
                }
            }
            // All numbers in one pass, so the keypad can search names (T9) and numbers instantly.
            val nums = HashMap<String, LinkedHashMap<String, String>>()
            runCatching {
                app.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Phone.NUMBER),
                    null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(0)?.trim()?.lowercase() ?: continue
                        val n = c.getString(1)?.trim().orEmpty()
                        if (n.isNotEmpty()) nums.getOrPut(name) { LinkedHashMap() }.putIfAbsent(threadKey(n), n)
                    }
                }
            }
            val list = byName.map { (key, p) -> p.copy(numbers = nums[key]?.values?.toList().orEmpty()) }
            main.post { done(list) }
        }
    }

    /** Phone numbers of a contact (and of its same-name duplicates), deduplicated. */
    fun numbers(ctx: Context, person: Person): List<String> {
        val out = LinkedHashMap<String, String>()
        runCatching {
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY}=?", arrayOf(person.name), null)?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0)?.trim().orEmpty()
                    if (n.isNotEmpty()) out.putIfAbsent(threadKey(n), n)
                }
            }
        }
        return out.values.toList()
    }

    fun loadPhoto(view: ImageView, uri: String?, onLoaded: (Boolean) -> Unit) {
        view.tag = uri
        if (uri == null) return onLoaded(false)
        photos.get(uri)?.let { view.setImageBitmap(it); return onLoaded(true) }
        view.setImageDrawable(null)
        onLoaded(false)
        val app = view.context.applicationContext
        io.execute {
            val bmp = runCatching {
                app.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return@execute
            photos.put(uri, bmp)
            main.post { if (view.tag == uri) { view.setImageBitmap(bmp); onLoaded(true) } }
        }
    }
}
