package io.legado.app.api.controller

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookType
import io.legado.app.help.book.BookExportProviders
import io.legado.app.utils.systemCurrentTimeMillis
import io.legado.app.utils.toInputStream
import io.legado.app.utils.yearMonthDayFromMillis
import io.legado.app.web.api.WebApiResponse

/**
 * OPDS 1.2 目录 (Atom XML) 输出。
 *
 * # 为什么要有
 * 「可通过其他前端接入」这句话落到标准协议上就是 OPDS —— KOReader / 静读天下 / 各类
 * 阅读器都原生支持 OPDS 目录 + HTTP Basic 认证, 不需要为每个客户端写适配。
 * 参考项目 hectorqin/reader 同样提供 OPDS, 这里对齐这一层。
 *
 * # 路由
 * - `GET /opds`           导航目录 (navigation)
 * - `GET /opds/bookshelf` 书架取书目录 (acquisition), 每本书给 epub/txt 两个取书链接
 * - `GET /opds/download?url=<bookUrl>&format=epub|txt|cbz` 取书 (导出后流式下发)
 *
 * # 鉴权
 * 三条路径都登记在 [io.legado.app.web.api.WebApi] 的 apiPaths 里, 由鉴权闸门统一拦截;
 * OPDS 客户端用 HTTP Basic, 未授权时收到的 401 + `WWW-Authenticate` 正是它们弹凭据框的依据。
 *
 * # 代价说明
 * 远程书源的「在线书」导出需要逐章抓取, 首次取书较慢; 本地书 (上传/文件导入) 走同一路径。
 * 导出结果不落盘 (临时目录用完即删), 每次取书都重新导出。
 */
object OpdsController {

    private const val NAV_TYPE = "application/atom+xml;profile=opds-catalog;kind=navigation"
    private const val ACQ_TYPE = "application/atom+xml;profile=opds-catalog;kind=acquisition"

    /** 导航目录。 */
    suspend fun root(): WebApiResponse = stream(navigationFeed(), NAV_TYPE)

    /** 书架取书目录。 */
    suspend fun bookshelf(): WebApiResponse = stream(acquisitionFeed(), ACQ_TYPE)

    /** 取书: 导出后流式下发。 */
    suspend fun download(parameters: Map<String, List<String>>): WebApiResponse {
        val bookUrl = parameters["url"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: return error(400, "缺少 url 参数")
        val format = parameters["format"]?.firstOrNull()?.lowercase()?.takeIf { it.isNotBlank() } ?: "epub"
        val book = AppDbProviders.get().bookDao.getBook(bookUrl)
            ?: return error(404, "书籍不存在")
        val provider = BookExportProviders.getOrNull()
            ?: return error(501, "当前平台未注册书籍导出能力")
        val bytes = provider.exportBookBytes(book, format)
            ?: return error(500, "导出失败: $format")
        return WebApiResponse.Stream(
            inputStream = bytes.toInputStream(),
            contentType = mimeOf(format),
            contentLength = bytes.size.toLong(),
            statusCode = 200,
            statusMessage = "OK",
            headers = mapOf(
                "Content-Disposition" to "attachment; filename=\"${fileName(book, format)}\"",
                "Cache-Control" to "no-store",
            ),
        )
    }

    // ---------------- feed 生成 ----------------

    private suspend fun shelfBooks(): List<Book> =
        AppDbProviders.get().bookDao.all().filterNot { (it.type and BookType.notShelf) > 0 }

    private suspend fun navigationFeed(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<feed xmlns=\"http://www.w3.org/2005/Atom\">\n")
        append("  <title>Legado 书架</title>\n")
        append("  <id>urn:legado:opds:root</id>\n")
        append("  <updated>${nowIso()}</updated>\n")
        append("  <link rel=\"self\" href=\"/opds\" type=\"$NAV_TYPE\"/>\n")
        append("  <link rel=\"start\" href=\"/opds\" type=\"$NAV_TYPE\"/>\n")
        append("  <entry>\n")
        append("    <title>全部书籍</title>\n")
        append("    <id>urn:legado:opds:bookshelf</id>\n")
        append("    <updated>${nowIso()}</updated>\n")
        append("    <content type=\"text\">书架上的全部书籍</content>\n")
        append("    <link rel=\"subsection\" href=\"/opds/bookshelf\" type=\"$ACQ_TYPE\"/>\n")
        append("  </entry>\n")
        append("</feed>\n")
    }

    private suspend fun acquisitionFeed(): String {
        val books = shelfBooks()
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<feed xmlns=\"http://www.w3.org/2005/Atom\">\n")
            append("  <title>Legado 书架 (${books.size})</title>\n")
            append("  <id>urn:legado:opds:bookshelf</id>\n")
            append("  <updated>${nowIso()}</updated>\n")
            append("  <link rel=\"self\" href=\"/opds/bookshelf\" type=\"$ACQ_TYPE\"/>\n")
            append("  <link rel=\"start\" href=\"/opds\" type=\"$NAV_TYPE\"/>\n")
            books.forEach { book ->
                val u = enc(book.bookUrl)
                append("  <entry>\n")
                append("    <title>${xml(book.name)}</title>\n")
                append("    <author><name>${xml(book.author)}</name></author>\n")
                append("    <id>${xml(book.bookUrl)}</id>\n")
                append("    <updated>${nowIso()}</updated>\n")
                append("    <content type=\"text\">${xml(book.intro)}</content>\n")
                append("    <link rel=\"http://opds-spec.org/acquisition\" href=\"/opds/download?url=$u&amp;format=epub\" type=\"application/epub+zip\"/>\n")
                append("    <link rel=\"http://opds-spec.org/acquisition\" href=\"/opds/download?url=$u&amp;format=txt\" type=\"text/plain\"/>\n")
                append("  </entry>\n")
            }
            append("</feed>\n")
        }
    }

    // ---------------- 小工具 ----------------

    private fun stream(text: String, contentType: String): WebApiResponse {
        val bytes = text.encodeToByteArray()
        return WebApiResponse.Stream(
            inputStream = bytes.toInputStream(),
            contentType = contentType,
            contentLength = bytes.size.toLong(),
            statusCode = 200,
            statusMessage = "OK",
            headers = mapOf("Cache-Control" to "no-store"),
        )
    }

    private fun error(code: Int, msg: String): WebApiResponse {
        val bytes = (
            "{\"isSuccess\":false,\"errorMsg\":\"" + msg.replace("\"", "'") + "\",\"data\":null}"
            ).encodeToByteArray()
        return WebApiResponse.Stream(
            inputStream = bytes.toInputStream(),
            contentType = "application/json; charset=utf-8",
            contentLength = bytes.size.toLong(),
            statusCode = code,
            statusMessage = "Error",
        )
    }

    private fun mimeOf(format: String): String = when (format) {
        "epub" -> "application/epub+zip"
        "cbz" -> "application/vnd.comicbook+zip"
        else -> "text/plain; charset=utf-8"
    }

    private fun fileName(book: Book, format: String): String {
        val ext = when (format) {
            "epub" -> "epub"
            "cbz" -> "cbz"
            else -> "txt"
        }
        val base = (book.name + "_" + book.author).replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim()
        return (base.take(80).ifBlank { "book" }) + "." + ext
    }

    private fun xml(raw: String?): String = (raw ?: "")
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** 极简 percent-encoding (commonMain 无 java.net.URLEncoder)。 */
    private fun enc(raw: String): String {
        val hex = "0123456789ABCDEF"
        return buildString {
            for (b in raw.encodeToByteArray()) {
                val i = b.toInt() and 0xFF
                val c = i.toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') {
                    append(c)
                } else {
                    append('%').append(hex[i shr 4]).append(hex[i and 0xF])
                }
            }
        }
    }

    /** Atom 的 `<updated>` 需 RFC3339; 这里取当日零点 (OPDS 客户端基本不据此做增量)。 */
    private fun nowIso(): String {
        val (y, m, d) = yearMonthDayFromMillis(systemCurrentTimeMillis())
        return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}T00:00:00Z"
    }
}
