package com.tvvoice.assistant.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Site icons for the sources pill, from Google's favicon service (it answers for any domain, and from networks
 * where the sites themselves may be slow). Each comes back as a disc ready to draw, kept for the app's life.
 */
object Favicons {
    private const val SERVICE = "https://www.google.com/s2/favicons?sz=64&domain="
    private const val SIZE = 64
    private val cache = LruCache<String, Bitmap>(48)
    /** Sites with no icon (or no answer): not asked again for a while, a slow one cost seconds every time. */
    private val misses = LruCache<String, Long>(64)
    private const val MISS_MS = 30 * 60_000L

    /** The icon of [site] ("rbc.ru") on a white disc ringed in [ring], or null when there is none. */
    suspend fun load(site: String, ring: Int): Bitmap? {
        cache.get(site)?.let { return it }
        misses.get(site)?.let { if (System.currentTimeMillis() - it < MISS_MS) return null }
        val icon = withContext(Dispatchers.IO) { fetch(site) }
        if (icon == null) {
            misses.put(site, System.currentTimeMillis())
            return null
        }
        return disc(icon, ring).also { cache.put(site, it) }
    }

    private fun fetch(site: String): Bitmap? = try {
        val c = URL(SERVICE + URLEncoder.encode(site, "UTF-8")).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 4_000
            c.readTimeout = 4_000
            if (c.responseCode == 200) BitmapFactory.decodeStream(c.inputStream) else null
        } finally {
            c.disconnect()
        }
    } catch (e: IOException) {
        null
    }

    /** White under the icon, so dark and transparent icons both read on the dark pill; the ring parts overlaps. */
    private fun disc(icon: Bitmap, ring: Int): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val half = SIZE / 2f
        paint.color = ring
        canvas.drawCircle(half, half, half, paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(half, half, half - 4, paint)
        val inset = SIZE * 0.2f
        canvas.drawBitmap(icon, Rect(0, 0, icon.width, icon.height), RectF(inset, inset, SIZE - inset, SIZE - inset), paint)
        return out
    }
}
