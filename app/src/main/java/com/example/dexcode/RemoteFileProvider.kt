package com.example.dexcode

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import com.example.dexcode.ssh.SshSessionManager
import com.jcraft.jsch.ChannelSftp
import java.io.FileNotFoundException
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.UUID

/** Presents an exact, read-only remote file as a seekable descriptor. */
class RemoteFileProvider : ContentProvider() {
    private data class Share(
        val session: WeakReference<SshSessionManager>,
        val path: String,
        val name: String,
        val mime: String,
        val size: Long,
    )

    override fun onCreate() = true

    private fun find(uri: Uri): Share? {
        if (uri.pathSegments.size != 1) return null
        return synchronized(shares) { shares[uri.lastPathSegment] }
            ?.takeIf { it.session.get()?.connected == true }
    }

    override fun getType(uri: Uri): String? = find(uri)?.mime

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
        val columns = projection?.map { it }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            find(uri)?.let { share ->
                addRow(columns.map {
                    when (it) {
                        OpenableColumns.DISPLAY_NAME -> share.name
                        OpenableColumns.SIZE -> share.size
                        else -> null
                    }
                }.toTypedArray())
            }
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val share = find(uri) ?: throw FileNotFoundException("Remote file is closed")
        val thread = HandlerThread("DeXCode remote file").apply { start() }
        val callback = object : ProxyFileDescriptorCallback() {
            private var channel: ChannelSftp? = null
            private var stream: InputStream? = null
            private var position = -1L

            override fun onGetSize() = share.size

            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                if (offset >= share.size || size == 0) return 0
                try {
                    if (stream == null || position != offset) {
                        closeStream()
                        val session = checkNotNull(share.session.get())
                        val source = session.openSftp().also { channel = it }
                        val attributes = source.lstat(share.path)
                        check(attributes.isReg && attributes.size == share.size)
                        stream = source.get(share.path, null, offset)
                        position = offset
                    }
                    val requested = minOf(size.toLong(), share.size - offset, data.size.toLong()).toInt()
                    var read = 0
                    val input = checkNotNull(stream)
                    while (read < requested) {
                        val count = input.read(data, read, requested - read)
                        if (count <= 0) break
                        read += count
                    }
                    position += read
                    return read
                } catch (error: Exception) {
                    closeStream()
                    throw ErrnoException("SFTP read", OsConstants.EIO, error)
                }
            }

            private fun closeStream() {
                channel?.disconnect()
                runCatching { stream?.close() }
                channel = null
                stream = null
            }

            override fun onRelease() {
                closeStream()
                thread.quitSafely()
            }
        }
        try {
            val storage = checkNotNull(context).getSystemService(StorageManager::class.java)
            return checkNotNull(storage).openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY, callback, Handler(thread.looper),
            )
        } catch (error: Exception) {
            thread.quitSafely()
            throw FileNotFoundException("Could not open remote file").apply { initCause(error) }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        private val shares = LinkedHashMap<String, Share>()

        internal fun share(context: Context, manager: SshSessionManager, path: String,
                           name: String, mime: String, size: Long): Uri {
            val token = UUID.randomUUID().toString()
            synchronized(shares) {
                shares.entries.removeAll { it.value.session.get()?.connected != true }
                if (shares.size >= 128) shares.remove(shares.keys.first())
                shares[token] = Share(WeakReference(manager), path, name, mime, size)
            }
            return Uri.Builder().scheme("content").authority(context.packageName + ".remote-files")
                .appendPath(token).build()
        }
    }
}
