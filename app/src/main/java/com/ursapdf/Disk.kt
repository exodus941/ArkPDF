package com.ursapdf

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

/**
 * The file behind an address, for a PDF another app opened with read access only. Renaming or
 * deleting it then needs All files access (Android 11+) or storage permission (older).
 */
object Disk {
    fun allowed(c: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** The file on disk, else null: from a file address, a raw document id, `_data`, or name and size. */
    fun fileOf(c: Context, uri: String): File? {
        val u = Uri.parse(uri)
        if (u.scheme == "file") return u.path?.let(::File)?.takeIf { it.isFile }
        try {
            if (DocumentsContract.isDocumentUri(c, u)) {
                val id = DocumentsContract.getDocumentId(u)
                if (id.startsWith("raw:")) File(id.substring(4)).takeIf { it.isFile }?.let { return it }
                // External storage: "primary:Download/x.pdf".
                if (id.startsWith("primary:")) File(Environment.getExternalStorageDirectory(), id.substring(8)).takeIf { it.isFile }?.let { return it }
            }
        } catch (_: Exception) {}
        try {
            c.contentResolver.query(u, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) File(it.getString(0)).takeIf { f -> f.isFile }?.let { f -> return f }
            }
        } catch (_: Exception) {}
        // Last: the one file on shared storage with this name and size.
        var name: String? = null; var size = -1L
        try {
            c.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) { name = it.getString(0); if (!it.isNull(1)) size = it.getLong(1) }
            }
        } catch (_: Exception) {}
        if (name == null || size < 0) return null
        try {
            c.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.MediaColumns.DATA),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?", arrayOf(name, size.toString()), null)?.use {
                val hits = ArrayList<File>()
                while (it.moveToNext()) File(it.getString(0)).takeIf { f -> f.isFile }?.let(hits::add)
                if (hits.size == 1) return hits[0]
            }
        } catch (_: Exception) {}
        return null
    }

    /** Renames [f] in its folder; the new file, or null if the name is taken or the move fails. */
    fun rename(c: Context, f: File, name: String): File? {
        val to = File(f.parentFile, name)
        if (to.exists() || !f.renameTo(to)) return null
        MediaScannerConnection.scanFile(c, arrayOf(f.path, to.path), null, null)
        return to
    }

    fun delete(c: Context, f: File): Boolean {
        val ok = f.delete()
        if (ok) MediaScannerConnection.scanFile(c, arrayOf(f.path), null, null)
        return ok
    }
}
