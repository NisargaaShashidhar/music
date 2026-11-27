package com.localbeats.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class Song(val id: Long, val title: String, val artist: String, val uri: Uri, val duration: Long)

data class Row(val title: String, val sub: String, val song: Song?, val playlist: String?)

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("localbeats", Context.MODE_PRIVATE)

    fun playlists(): LinkedHashMap<String, MutableList<Long>> {
        val m = LinkedHashMap<String, MutableList<Long>>()
        try {
            val o = JSONObject(sp.getString("playlists", "{}") ?: "{}")
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val a = o.getJSONArray(k)
                val l = ArrayList<Long>()
                for (i in 0 until a.length()) l.add(a.getLong(i))
                m[k] = l
            }
        } catch (e: Exception) {
        }
        return m
    }

    fun savePlaylists(m: Map<String, List<Long>>) {
        val o = JSONObject()
        for ((k, v) in m) {
            val a = JSONArray()
            for (id in v) a.put(id)
            o.put(k, a)
        }
        sp.edit().putString("playlists", o.toString()).apply()
    }
}

object Img {
    /** Decode an image from the gallery, fix rotation, shrink it, and save it as JPEG. */
    fun save(ctx: Context, src: Uri, dest: File, max: Int): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > max * 2 || bounds.outHeight / sample > max * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bm = ctx.contentResolver.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return false

            val orient = try {
                ctx.contentResolver.openInputStream(src)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                } ?: 1
            } catch (e: Exception) {
                1
            }
            val deg = when (orient) {
                6 -> 90f
                3 -> 180f
                8 -> 270f
                else -> 0f
            }
            val scale = max.toFloat() / maxOf(bm.width, bm.height)
            val m = Matrix()
            if (scale < 1f) m.postScale(scale, scale)
            if (deg != 0f) m.postRotate(deg)
            if (!m.isIdentity) bm = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)

            dest.parentFile?.mkdirs()
            FileOutputStream(dest).use { bm.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            true
        } catch (e: Exception) {
            false
        }
    }
}
