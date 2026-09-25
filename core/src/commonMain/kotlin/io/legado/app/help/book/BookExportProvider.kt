package io.legado.app.help.book

import io.legado.app.data.entities.Book
import kotlin.concurrent.Volatile

/**
 * 书籍导出 provider (commonMain 接口, 平台注入实现)。
 *
 * # 为什么需要它
 * OPDS 取书要把「远程书源的在线书」导出成 EPUB/TXT 文件下发。导出主流程
 * (`ExportBookShared` / `ExportBookEpubShared`) 依赖 EPUB 模板资源与文件写出,
 * 无法下沉 commonMain, 故用 provider 注入 (与 OkHttpClientProviders 同模式)。
 *
 * 未注册实现时, OPDS 的取书链接会明确报错, 不影响其他功能。
 */
interface BookExportProvider {

    /**
     * 导出单本书并返回文件字节。
     *
     * @param format `"epub"` / `"txt"` / `"cbz"` (其余按 txt 处理)
     * @return 文件字节; 失败返回 null (调用方据此向 OPDS 客户端报错)
     */
    suspend fun exportBookBytes(book: Book, format: String): ByteArray?
}

object BookExportProviders {

    @Volatile
    private var impl: BookExportProvider? = null

    fun register(provider: BookExportProvider) {
        impl = provider
    }

    fun getOrNull(): BookExportProvider? = impl
}
