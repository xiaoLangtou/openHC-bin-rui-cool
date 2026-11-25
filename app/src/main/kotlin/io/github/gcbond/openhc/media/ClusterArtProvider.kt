package io.github.gcbond.openhc.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * 给车机 EAS MediaCenter（系统进程）跨进程读取专辑封面的 ContentProvider。
 * Android 7+ 跨进程传递 `file://` 可能触发 FileUriExposure 或权限拦截，统一写入 `cacheDir/eas/cover.png` 并以 `content://` 暴露。
 */
class ClusterArtProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val f = fileFor(uri.lastPathSegment) ?: return null
        if (!f.exists()) return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols, 1)
        val row = Array<Any?>(cols.size) { i ->
            when (cols[i]) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }
        cursor.addRow(row)
        return cursor
    }

    override fun getType(uri: Uri): String = "image/png"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fileFor(uri.lastPathSegment)
        if (f == null || !f.exists()) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun fileFor(name: String?): File? {
        val ctx = context ?: return null
        val dir = File(ctx.cacheDir, DIR_NAME)
        return if (name == "icon") File(dir, "icon.png") else File(dir, "cover.png")
    }

    companion object {
        private const val DIR_NAME = "eas"

        fun authority(context: Context): String = "${context.packageName}.eas"

        fun saveCover(context: Context, bitmap: Bitmap): Uri? = try {
            val dir = File(context.cacheDir, DIR_NAME)
            if (!dir.exists() && !dir.mkdirs()) {
                null
            } else {
                val file = File(dir, "cover.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 92, out)
                }
                Uri.parse("content://${authority(context)}/cover?v=${System.currentTimeMillis()}")
            }
        } catch (_: Throwable) {
            null
        }
    }
}
