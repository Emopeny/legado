package io.legado.app.api.controller


import io.legado.app.api.ReturnData
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 书源 CRUD Web 接口 (shared commonMain 下沉版)。
 *
 * 原 app 端实现仅依赖 DAO + SourceHelp + GSON 扩展, 这些均已下沉 commonMain:
 * - `appDb.bookSourceDao` → `AppDbProviders.get().bookSourceDao` (与 SourceHelp 同模式)
 * - [SourceHelp] 已整体下沉 commonMain
 * - [GSON]/[fromJsonObject]/[fromJsonArray] 已下沉 commonMain (kotlinx-serialization 兼容层)
 *
 * 行为与原 app 端逐字等价, 仅多一层 provider 间接。消费方 import 不变。
 */
object BookSourceController {

    suspend fun sources(): ReturnData {
        val bookSources = AppDbProviders.get().bookSourceDao.all()
        val returnData = ReturnData()
        return if (bookSources.isEmpty()) {
            returnData.setErrorMsg("设备源列表为空")
        } else returnData.setData(bookSources)
    }

    suspend fun sourcesPart(): ReturnData {
        val bookSources = AppDbProviders.get().bookSourceDao.allPart()
        val returnData = ReturnData()
        return if (bookSources.isEmpty()) {
            returnData.setErrorMsg("设备源列表为空")
        } else returnData.setData(bookSources)
    }

    suspend fun saveSource(postData: String?): ReturnData {
        val returnData = ReturnData()
        postData ?: return returnData.setErrorMsg("数据不能为空")
        val bookSource = GSON.fromJsonObject<BookSource>(postData).getOrNull()
        if (bookSource != null) {
            if (bookSource.bookSourceName.isEmpty() || bookSource.bookSourceUrl.isEmpty()) {
                returnData.setErrorMsg("源名称和URL不能为空")
            } else {
                AppDbProviders.get().bookSourceDao.insert(bookSource)
                // WebApi 改源后失效封面链路源缓存
                SourceHelp.evict(bookSource.bookSourceUrl)
                returnData.setData("")
            }
        } else {
            returnData.setErrorMsg("转换源失败")
        }
        return returnData
    }

    suspend fun saveSources(postData: String?): ReturnData {
        postData ?: return ReturnData().setErrorMsg("数据为空")
        val okSources = arrayListOf<BookSource>()
        val bookSources = GSON.fromJsonArray<BookSource>(postData).getOrNull()
        if (bookSources.isNullOrEmpty()) {
            return ReturnData().setErrorMsg("转换源失败")
        }
        bookSources.forEach { bookSource ->
            if (bookSource.bookSourceName.isNotBlank()
                && bookSource.bookSourceUrl.isNotBlank()
            ) {
                AppDbProviders.get().bookSourceDao.insert(bookSource)
                SourceHelp.evict(bookSource.bookSourceUrl)
                okSources.add(bookSource)
            }
        }
        return ReturnData().setData(okSources)
    }

    /**
     * 服务端拉取书源链接后直接入库。
     *
     * 为什么必须放在服务端: 前端 web 端原来的「网络链接导入」是浏览器 `fetch(url)` 直连,
     * 第三方站点不给 CORS 头、或链接是 http (https 页面下的混合内容) 时, 浏览器直接抛
     * "Failed to fetch", 且这类拦截在页面侧无法绕过。旧 warpdotsys/reader 与
     * lukelzlz/legado-server 的 `importBookSourcesFromUrl` 都是服务端抓, 行为对齐。
     *
     * 请求体: `{"url":"https://..."}`; 返回与 [saveSources] 一致 (已入库的源列表)。
     */
    suspend fun importFromUrl(postData: String?): ReturnData {
        postData ?: return ReturnData().setErrorMsg("数据为空")
        val sourceUrl = runCatching {
            Json.parseToJsonElement(postData).jsonObject["url"]?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return ReturnData().setErrorMsg("参数url不能为空，请指定书源链接")
        val text = try {
            val response = OkHttpClientProviders.get().okHttpClient.newCallResponse {
                url(sourceUrl)
                get()
            }
            if (!response.isSuccessful) {
                return ReturnData().setErrorMsg("拉取失败 HTTP ${response.code}")
            }
            response.body.string()
        } catch (e: Exception) {
            return ReturnData().setErrorMsg("拉取失败: ${e.message}")
        }
        return saveSources(text)
    }

    suspend fun getSource(parameters: Map<String, List<String>>): ReturnData {
        val url = parameters["url"]?.firstOrNull()
        val returnData = ReturnData()
        if (url.isNullOrEmpty()) {
            return returnData.setErrorMsg("参数url不能为空，请指定源地址")
        }
        val bookSource = AppDbProviders.get().bookSourceDao.getBookSource(url)
            ?: return returnData.setErrorMsg("未找到源，请检查书源地址")
        return returnData.setData(bookSource)
    }

    suspend fun deleteSources(postData: String?): ReturnData {
        kotlin.runCatching {
            GSON.fromJsonArray<BookSource>(postData).getOrThrow().let {
                SourceHelp.deleteBookSources(it)
            }
        }.onFailure {
            return ReturnData().setErrorMsg(it.message ?: "数据格式错误")
        }
        return ReturnData().setData("已执行"/*okSources*/)
    }

    suspend fun exploreKinds(parameters: Map<String, List<String>>): ReturnData {
        val url = parameters["url"]?.firstOrNull()
        val returnData = ReturnData()
        if (url.isNullOrEmpty()) {
            return returnData.setErrorMsg("参数url不能为空，请指定源地址")
        }
        val bookSource = AppDbProviders.get().bookSourceDao.getBookSource(url)
            ?: return returnData.setErrorMsg("未找到源，请检查书源地址")
        return kotlin.runCatching {
            val kinds = bookSource.exploreKinds()
            returnData.setData(kinds)
        }.getOrElse {
            returnData.setErrorMsg("解析分类失败: ${it.message}")
        }
    }

    suspend fun exploreBooks(parameters: Map<String, List<String>>): ReturnData {
        val url = parameters["url"]?.firstOrNull()
        val exploreUrl = parameters["exploreUrl"]?.firstOrNull()
        val page = parameters["page"]?.firstOrNull()?.toIntOrNull() ?: 1
        val returnData = ReturnData()
        if (url.isNullOrEmpty() || exploreUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("参数url和exploreUrl不能为空")
        }
        val bookSource = AppDbProviders.get().bookSourceDao.getBookSource(url)
            ?: return returnData.setErrorMsg("未找到源，请检查书源地址")
        return kotlin.runCatching {
            val pageResult = WebBook.getBookListAwait(
                bookSource = bookSource,
                key = exploreUrl,
                page = page,
                isSearch = false
            )
            returnData.setData(pageResult.books)
        }.getOrElse {
            returnData.setErrorMsg("加载发现书籍失败: ${it.message}")
        }
    }
}
