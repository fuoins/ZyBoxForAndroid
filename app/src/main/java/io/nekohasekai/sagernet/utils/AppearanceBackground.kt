package io.nekohasekai.sagernet.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import io.nekohasekai.sagernet.SagerNet
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Liquid Glass · 自定义背景图。
 *
 * 日间与夜间各存一份，互不影响：切到深色主题就换另一张。
 * 图片统一压成 JPEG 放在私有目录里，避免把几 MB 的原图长期留在内存/磁盘。
 */
object AppearanceBackground {

    /** 长边上限。再大对屏幕也没有意义，只会拖慢解码。 */
    private const val MAX_EDGE = 2560

    private const val QUALITY = 92

    fun file(night: Boolean): File = File(
        SagerNet.application.filesDir,
        if (night) "appearance_bg_night.img" else "appearance_bg_day.img"
    )

    fun isSet(night: Boolean): Boolean = file(night).let { it.isFile && it.length() > 0L }

    fun clear(night: Boolean) {
        try {
            file(night).delete()
        } catch (_: Throwable) {
        }
    }

    fun clearAll() {
        clear(false)
        clear(true)
    }

    /** 把相册里的图读出来、缩到合理尺寸、写进私有目录。 */
    fun save(context: Context, uri: Uri, night: Boolean): Boolean {
        var source: Bitmap? = null
        var output: Bitmap? = null
        return try {
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return false
            source = decoded
            val edge = max(decoded.width, decoded.height)
            val ratio = if (edge > MAX_EDGE) MAX_EDGE.toFloat() / edge else 1f
            val result = if (ratio < 1f) {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * ratio).toInt().coerceAtLeast(1),
                    (decoded.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                decoded
            }
            output = result
            val target = file(night)
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { out ->
                result.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            }
            true
        } catch (_: Throwable) {
            false
        } finally {
            val produced = output
            val original = source
            if (produced != null && produced !== original) produced.recycle()
            original?.recycle()
        }
    }

    /** 按屏幕尺寸采样解码，避免整张原图进内存。 */
    fun decode(file: File, reqWidth: Int, reqHeight: Int): Bitmap? = try {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqWidth &&
            bounds.outHeight / (sample * 2) >= reqHeight
        ) {
            sample *= 2
        }
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    } catch (_: Throwable) {
        null
    }
}
