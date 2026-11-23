package com.example.util

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.util.Properties

data class SambaServerConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 445,
    val share: String = "",
    val domain: String = "",
    val username: String = "",
    val password: String = ""
)

data class SambaEntry(
    val name: String,
    val url: String,
    val isDirectory: Boolean,
    val size: Long,

    val lastModified: Long
)

object SambaClient {

    fun buildContext(server: SambaServerConfig): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.encryptionEnabled", "false")
            setProperty("jcifs.smb.client.connTimeout", "8000")
            setProperty("jcifs.smb.client.responseTimeout", "12000")
            setProperty("jcifs.smb.client.soTimeout", "15000")
            setProperty("jcifs.smb.client.useBatching", "false")
            setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
            setProperty("jcifs.smb.client.ignoreUnprocessedResponse", "true")
        }
        val base = BaseContext(PropertyConfiguration(props))
        return if (server.username.isBlank() && server.password.isBlank()) {
            base.withAnonymousCredentials()
        } else {
            base.withCredentials(NtlmPasswordAuthenticator(server.domain, server.username, server.password))
        }
    }

    fun serverRootUrl(server: SambaServerConfig): String {
        val cleanHost = server.host.trim().trimEnd('/')
        val cleanShare = server.share.trim().trim('/')
        return if (cleanShare.isNotBlank()) {
            "smb://$cleanHost:${server.port}/$cleanShare/"
        } else {
            "smb://$cleanHost:${server.port}/"
        }
    }

    fun childUrl(context: CIFSContext, parentUrl: String, name: String): String {
        val formattedParent = if (parentUrl.endsWith("/")) parentUrl else "$parentUrl/"
        val cleanName = name.trim('/')
        return "$formattedParent$cleanName"
    }

    fun listEntries(context: CIFSContext, url: String): List<SambaEntry> {
        val formattedUrl = if (url.endsWith("/") || url.contains("?")) url else "$url/"
        val root = SmbFile(formattedUrl, context)
        if (!root.exists()) return emptyList()
        
        val files = try {
            root.listFiles() ?: emptyArray()
        } catch (e: Exception) {
            throw e
        }

        return files.mapNotNull { f ->
            runCatching {
                val isDir = f.isDirectory
                val rawName = f.name.trimEnd('/')
                SambaEntry(
                    name = if (rawName.isBlank()) f.name else rawName,
                    url = f.locator.canonicalURL,
                    isDirectory = isDir,
                    size = if (isDir) 0L else runCatching { f.length() }.getOrDefault(0L),
                    lastModified = runCatching { f.lastModified }.getOrDefault(0L)
                )
            }.getOrNull()
        }.sortedWith(compareByDescending<SambaEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun listShares(context: CIFSContext, server: SambaServerConfig): List<SambaEntry> =
        listEntries(context, serverRootUrl(server))

    fun mkdir(context: CIFSContext, parentUrl: String, name: String) {
        val dirUrl = childUrl(context, parentUrl, name)
        val dir = SmbFile(if (dirUrl.endsWith("/")) dirUrl else "$dirUrl/", context)
        dir.mkdirs()
    }

    fun rename(context: CIFSContext, oldUrl: String, newUrl: String) {
        val from = SmbFile(oldUrl, context)
        val to = SmbFile(newUrl, context)
        from.renameTo(to)
    }

    fun delete(context: CIFSContext, url: String) {
        val file = SmbFile(url, context)
        if (file.isDirectory) {
            val children = runCatching { file.listFiles() }.getOrNull() ?: emptyArray()
            children.forEach { child ->
                delete(context, child.locator.canonicalURL)
            }
        }
        file.delete()
    }

    fun inputStream(context: CIFSContext, url: String): java.io.InputStream =
        SmbFile(url, context).inputStream

    fun outputStream(context: CIFSContext, url: String): java.io.OutputStream =
        SmbFile(url, context).outputStream

    fun isDirectory(context: CIFSContext, url: String): Boolean =
        runCatching { SmbFile(url, context).isDirectory }.getOrDefault(false)

    fun exists(context: CIFSContext, url: String): Boolean =
        runCatching { SmbFile(url, context).exists() }.getOrDefault(false)

    fun testConnection(context: CIFSContext, server: SambaServerConfig): String? {
        return try {
            val shares = listShares(context, server)
            if (shares.isEmpty() && server.share.isBlank()) {
                "Connected, but no public shares listed. Try entering a specific Share Name."
            } else null
        } catch (e: Exception) {
            e.message ?: "Connection failed"
        }
    }
}
