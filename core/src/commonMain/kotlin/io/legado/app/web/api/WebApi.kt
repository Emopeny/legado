package io.legado.app.web.api

import io.legado.app.api.ReturnData
import io.legado.app.api.controller.BackupController
import io.legado.app.api.controller.BookController
import io.legado.app.api.controller.BookSourceController
import io.legado.app.api.controller.ReplaceRuleController
import io.legado.app.utils.toInputStream
import io.legado.app.web.auth.WebAuthProviders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 平台无关的 web API 路由层。
 *
 * 把原 HttpServer.serve 的 method/path when 分派上移至此 (11 GET + 11 POST 逐字等价)，
 * nanohttpd/Ktor/ContentProvider 各壳只负责「原生请求 -> [WebApiRequest] -> handle ->
 * [WebApiResponse] -> 原生响应」的薄适配。路径/参数名/错误分支零改动。
 */
object WebApi {

    suspend fun handle(request: WebApiRequest): WebApiResponse {
        // 鉴权闸门: 只拦已知 API 路由; 静态资源 (登录界面本体 / 帮助页 / favicon) 一律放行,
        // 否则前端连登录页都加载不出来。未注册 provider 或未配置密码时 authorized() 恒真 (旧行为)。
        if (request.path in apiPaths && !authorized(request)) {
            return unauthorized()
        }
        if (request.path == "/mediaStream") {
            return mediaStreamGone(request.method)
        }
        if (request.method == "GET" && request.path == "/getBackupZip") {
            return BackupController.getBackupZip()
        }

        val returnData: ReturnData? = when (request.method) {
            "POST" -> handlePost(request)
            "GET" -> handleGet(request)
            else -> null
        }

        if (returnData == null) {
            // 非 API 路由 -> 平台静态资源 (尾斜杠补 index.html)
            var path = request.path
            if (path.endsWith("/")) path += "index.html"
            return WebApiResponse.StaticAsset(path)
        }

        val data = returnData.data
        return if (data is ByteArray) {
            WebApiResponse.Bytes(data, "image/png", returnData)
        } else {
            WebApiResponse.Json(returnData)
        }
    }

    private suspend fun handlePost(request: WebApiRequest): ReturnData? {
        val postData = request.postData
        return when (request.path) {
            "/auth/login" -> authLogin(postData)
            "/auth/logout" -> authLogout(request)
            "/auth/password" -> authChangePassword(request, postData)
            "/saveBookSource" -> BookSourceController.saveSource(postData)
            "/saveBookSources" -> BookSourceController.saveSources(postData)
            "/importBookSourcesFromUrl" -> BookSourceController.importFromUrl(postData)
            "/deleteBookSources" -> BookSourceController.deleteSources(postData)
            "/saveBook" -> BookController.saveBook(postData)
            "/deleteBook" -> BookController.deleteBook(postData)
            "/saveBookProgress" -> BookController.saveBookProgress(postData)
            "/addLocalBook" -> BookController.addLocalBook(request.query, request.files)
            "/restoreBackup" -> BackupController.restoreBackup(request.files)
            "/saveReadConfig" -> BookController.saveWebReadConfig(postData)
            "/saveReplaceRule" -> ReplaceRuleController.saveRule(postData)
            "/deleteReplaceRule" -> ReplaceRuleController.delete(postData)
            "/testReplaceRule" -> ReplaceRuleController.testRule(postData)
            else -> null
        }
    }

    private suspend fun handleGet(request: WebApiRequest): ReturnData? {
        val parameters = request.query
        return when (request.path) {
            "/auth/me" -> authMe(request)
            "/getBookSource" -> BookSourceController.getSource(parameters)
            "/getBookSources" -> BookSourceController.sources()
            "/getBookSourcesPart" -> BookSourceController.sourcesPart()
            "/getExploreKinds" -> BookSourceController.exploreKinds(parameters)
            "/getExploreBooks" -> BookSourceController.exploreBooks(parameters)
            "/getBookshelf" -> BookController.getBooks(parameters)
            "/getGroups" -> BookController.groups()
            "/getChapterList" -> BookController.getChapterList(parameters)
            "/refreshToc" -> BookController.refreshToc(parameters)
            "/getBookContent" -> BookController.getBookContent(parameters)
            "/cover" -> BookController.getCover(parameters)
            "/image" -> BookController.getImg(parameters)
            "/getReadConfig" -> BookController.getWebReadConfig()
            "/getReplaceRules" -> ReplaceRuleController.allRules()
            else -> null
        }
    }

    /**
     * 媒体代理已永久关闭。此分支不读取查询参数/来源/Range，也不接触任何 HTTP 客户端。
     */
    private fun mediaStreamGone(method: String): WebApiResponse {
        val body = if (method.equals("HEAD", ignoreCase = true)) {
            ByteArray(0)
        } else {
            "Media stream proxy is gone; use the media URL directly.".encodeToByteArray()
        }
        return WebApiResponse.Stream(
            inputStream = body.toInputStream(),
            contentType = "text/plain; charset=utf-8",
            contentLength = body.size.toLong(),
            statusCode = 410,
            statusMessage = "Gone",
            headers = mapOf(
                "Cache-Control" to "no-store",
                "X-Content-Type-Options" to "nosniff",
            ),
            returnData = ReturnData().apply {
                setErrorMsg("Media stream proxy is gone")
            },
        )
    }

    // ---------------- 内置账号与权限 ----------------

    /**
     * 已知 API 路由集合。只有这些路径过鉴权闸门, 其余一律当静态资源放行
     * (登录界面本身就是前端资源; 拦了就没法登录)。
     */
    private val apiPaths = setOf(
        "/mediaStream", "/getBackupZip",
        "/auth/logout", "/auth/password",
        "/saveBookSource", "/saveBookSources", "/deleteBookSources",
        "/saveBook", "/deleteBook", "/saveBookProgress", "/addLocalBook",
        "/restoreBackup", "/saveReadConfig",
        "/saveReplaceRule", "/deleteReplaceRule", "/testReplaceRule",
        "/importBookSourcesFromUrl",
        "/getBookSource", "/getBookSources", "/getBookSourcesPart",
        "/getExploreKinds", "/getExploreBooks",
        "/getBookshelf", "/getGroups", "/getChapterList", "/refreshToc",
        "/getBookContent", "/cover", "/image",
        "/getReadConfig", "/getReplaceRules",
    )

    /** 是否通过鉴权。未注册 provider 或未配置密码时恒真 (向后兼容)。 */
    private fun authorized(request: WebApiRequest): Boolean {
        val auth = WebAuthProviders.getOrNull() ?: return true
        if (!auth.isEnabled()) return true
        if (auth.verifyAuthorization(request.headers["authorization"])) return true
        // WebSocket 握手无法自定义请求头, 允许 ?token= 兜底
        return auth.verifyToken(request.query["token"]?.firstOrNull())
    }

    /**
     * 401 + `WWW-Authenticate`。必须返回真 401 而不是 200+isSuccess=false:
     * 标准客户端 (如各类 OPDS 阅读器) 只有收到 401 才会弹凭据输入框。
     */
    private fun unauthorized(): WebApiResponse {
        val body = ReturnData().setErrorMsg("未登录或凭据无效").toJsonString().encodeToByteArray()
        return WebApiResponse.Stream(
            inputStream = body.toInputStream(),
            contentType = "application/json; charset=utf-8",
            contentLength = body.size.toLong(),
            statusCode = 401,
            statusMessage = "Unauthorized",
            headers = mapOf("WWW-Authenticate" to "Basic realm=\"Legado\""),
            returnData = null,
        )
    }


    private fun tokenOf(request: WebApiRequest): String? =
        request.headers["authorization"]
            ?.trim()
            ?.takeIf { it.startsWith("bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?.trim()

    /** `POST /auth/login` body: `{"user":"legado","password":"..."}` → data 为 token 字符串。 */
    private fun authLogin(postData: String?): ReturnData {
        val auth = WebAuthProviders.getOrNull()
            ?: return ReturnData().setErrorMsg("当前平台未启用内置账号")
        if (!auth.isEnabled()) return ReturnData().setErrorMsg("未配置访问密码")
        val obj = runCatching { Json.parseToJsonElement(postData ?: "").jsonObject }.getOrNull()
            ?: return ReturnData().setErrorMsg("请求体格式错误")
        val user = obj["user"]?.jsonPrimitive?.content ?: auth.userName()
        val password = obj["password"]?.jsonPrimitive?.content
            ?: return ReturnData().setErrorMsg("密码不能为空")
        val token = auth.login(user, password)
            ?: return ReturnData().setErrorMsg("用户名或密码错误")
        return ReturnData().setData(token)
    }

    private fun authLogout(request: WebApiRequest): ReturnData {
        WebAuthProviders.getOrNull()?.logout(tokenOf(request))
        return ReturnData().setData("已退出")
    }

    /** data 为 JSON 字符串: `{"authEnabled":..,"authenticated":..,"user":".."}` */
    private fun authMe(request: WebApiRequest): ReturnData {
        val auth = WebAuthProviders.getOrNull()
        val enabled = auth?.isEnabled() == true
        return ReturnData().setData(
            buildJsonObject {
                put("authEnabled", enabled)
                put("authenticated", !enabled || authorized(request))
                put("user", auth?.userName() ?: "")
            }.toString()
        )
    }

    /** `POST /auth/password` body: `{"oldPassword":"..","newPassword":".."}` */
    private fun authChangePassword(request: WebApiRequest, postData: String?): ReturnData {
        val auth = WebAuthProviders.getOrNull()
            ?: return ReturnData().setErrorMsg("当前平台未启用内置账号")
        if (!authorized(request)) return ReturnData().setErrorMsg("未登录或凭据无效")
        val obj = runCatching { Json.parseToJsonElement(postData ?: "").jsonObject }.getOrNull()
            ?: return ReturnData().setErrorMsg("请求体格式错误")
        val old = obj["oldPassword"]?.jsonPrimitive?.content.orEmpty()
        val new = obj["newPassword"]?.jsonPrimitive?.content
            ?: return ReturnData().setErrorMsg("新密码不能为空")
        val err = auth.changePassword(old, new)
        return if (err == null) ReturnData().setData("已更新") else ReturnData().setErrorMsg(err)
    }
}