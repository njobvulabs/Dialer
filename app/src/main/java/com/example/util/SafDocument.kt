package com.example.util

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

/**
 * Minimal SAF (Storage Access Framework) wrapper built directly on
 * android.provider.DocumentsContract.
 *
 * Replaces androidx.documentfile:documentfile, which is NOT available in this
 * offline Gradle build (only the module descriptor is cached, no artifacts), so
 * no new dependency is added.
 */
class SafDocument internal constructor(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    documentId: String,
) {
    var documentId: String = documentId
        private set


    /** Named document Uri under [treeUri] that keeps tree-grant access. */
    val uri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    private var mName: String? = null
    private var mMime: String? = null
    private var mSize: Long? = null
    private var mLastModified: Long? = null
    private var loaded = false

    private fun overwrite(name: String?, mime: String?, size: Long?, modified: Long?) {
        mName = name
        mMime = mime
        mSize = size
        mLastModified = modified
        loaded = true
    }

    fun refresh() {
        loaded = false
    }

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    overwrite(
                        c.stringOrNull(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                        c.stringOrNull(DocumentsContract.Document.COLUMN_MIME_TYPE),
                        c.longOrNull(DocumentsContract.Document.COLUMN_SIZE),
                        c.longOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                    )
                }
            }
        }
    }

    val name: String? get() { load(); return mName }
    val mimeType: String? get() { load(); return mMime }
    val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    val length: Long get() { load(); return mSize ?: 0L }
    val lastModified: Long get() { load(); return mLastModified ?: 0L }
    val exists: Boolean get() { load(); return !mName.isNullOrEmpty() }

    fun listChildren(): List<SafDocument> {
        if (!isDirectory) return emptyList()
        val out = mutableListOf<SafDocument>()
        runCatching {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            resolver.query(childrenUri, null, null, null, null)?.use { c ->
                val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                if (idCol >= 0) {
                    while (c.moveToNext()) {
                        val id = c.getString(idCol) ?: continue
                        val child = SafDocument(resolver, treeUri, id)
                        child.overwrite(
                            c.stringOrNull(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                            c.stringOrNull(DocumentsContract.Document.COLUMN_MIME_TYPE),
                            c.longOrNull(DocumentsContract.Document.COLUMN_SIZE),
                            c.longOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                        )
                        out.add(child)
                    }
                }
            }
        }
        return out
    }

    fun findChild(name: String): SafDocument? =
        listChildren().firstOrNull { it.name == name }

    fun findChildDir(name: String): SafDocument? =
        findChild(name)?.takeIf { it.isDirectory }

    fun findChildFile(name: String): SafDocument? =
        findChild(name)?.takeIf { !it.isDirectory }

    fun createDirectory(displayName: String): SafDocument? =
        create(displayName, DocumentsContract.Document.MIME_TYPE_DIR)

    fun createFile(mime: String, displayName: String): SafDocument? =
        create(displayName, mime.ifEmpty { "application/octet-stream" })

    private fun create(displayName: String, mime: String): SafDocument? {
        return try {
            val created = DocumentsContract.createDocument(resolver, uri, mime, displayName)
                ?: return null
            SafDocument(resolver, treeUri, DocumentsContract.getDocumentId(created))
        } catch (_: Exception) {
            null
        }
    }

    fun delete(): Boolean {
        return try {
            DocumentsContract.deleteDocument(resolver, uri)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun renameTo(newName: String): Boolean {
        return try {
            val updated = DocumentsContract.renameDocument(resolver, uri, newName)
            if (updated != null) {
                documentId = DocumentsContract.getDocumentId(updated)
                refresh()
                true
            } else {
                // Some providers rename in place and return null.
                refresh()
                name == newName
            }
        } catch (_: Exception) {
            false
        }
    }

    fun openInputStream(): java.io.InputStream? =
        runCatching { resolver.openInputStream(uri) }.getOrNull()

    fun openOutputStream(mode: String = "w"): java.io.OutputStream? =
        runCatching { resolver.openOutputStream(uri, mode) }.getOrNull()

    fun readBytes(): ByteArray {
        val ins = openInputStream() ?: return ByteArray(0)
        return try {
            ins.use { it.readBytes() }
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    companion object {
        fun fromTreeUri(resolver: ContentResolver, treeUri: Uri): SafDocument? = runCatching {
            val id = DocumentsContract.getTreeDocumentId(treeUri)
            SafDocument(resolver, treeUri, id)
        }.getOrNull()

        fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.stringOrNull(OpenableColumns.DISPLAY_NAME) else null
            }
        }.getOrNull()
    }
}

private fun Cursor.stringOrNull(column: String): String? {
    val idx = getColumnIndex(column)
    return if (idx >= 0 && !isNull(idx)) getString(idx) else null
}

private fun Cursor.longOrNull(column: String): Long? {
    val idx = getColumnIndex(column)
    if (idx < 0 || isNull(idx)) return null
    return try {
        getLong(idx)
    } catch (_: Exception) {
        null
    }
}

/**
 * Persists a SAF tree grant in SharedPreferences so the server root survives
 * app restarts. The URI is made persistable via takePersistableUriPermission
 * (read + write) so it stays usable without re-picking it.
 */
object SafPrefs {
    private const val KEY = "tree_uri"

    fun save(context: Context, prefsName: String, uri: Uri?) {
        val sp = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        if (uri == null) {
            sp.edit().remove(KEY).apply()
            return
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        sp.edit().putString(KEY, uri.toString()).apply()
    }

    fun load(context: Context, prefsName: String): Uri? {
        val s = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return null
        return runCatching { Uri.parse(s) }.getOrNull()
    }
}