package io.legado.desktop.help.book

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookExportProvider
import io.legado.app.help.book.BookExportProviders
import java.io.File

/**
 * 桌面 / 无头端 [BookExportProvider]。
 *
 * 复用 [DesktopBookExport] 的既有导出主流程 (txt/epub/cbz), 落到临时目录后读回字节并清理 ——
 * OPDS 是流式下发, 不需要在磁盘留文件, 也不污染数据目录。
 */
private object DesktopBookExportProvider : BookExportProvider {

    override suspend fun exportBookBytes(book: Book, format: String): ByteArray? {
        val root = File(System.getProperty("java.io.tmpdir"), "legado-opds-export")
        val dir = File(root, System.nanoTime().toString())
        return try {
            if (!dir.mkdirs()) return null
            when (format.lowercase()) {
                "epub" -> DesktopBookExport.exportEpub(dir.absolutePath, listOf(book))
                "cbz" -> DesktopBookExport.exportCbz(dir.absolutePath, listOf(book))
                else -> DesktopBookExport.exportTxt(dir.absolutePath, listOf(book))
            }
            dir.listFiles()
                ?.firstOrNull { it.isFile && it.length() > 0 }
                ?.readBytes()
        } catch (e: Exception) {
            AppLog.put("OPDS 导出失败(${format}): ${e.message}", e)
            null
        } finally {
            runCatching { dir.deleteRecursively() }
        }
    }
}

/** 桌面/无头 main 入口早期注册一次。 */
fun registerDesktopBookExportProvider() {
    BookExportProviders.register(DesktopBookExportProvider)
}
