package io.legado.app.web.auth

import kotlin.concurrent.Volatile

/**
 * Web 访问鉴权 provider (commonMain 接口, 平台注入实现)。
 *
 * # 为什么要有这一层
 * fork 的 `:headless` 端此前**没有任何鉴权** —— 未带凭据也能读写书架/书源/备份,
 * 公网暴露只能靠 nginx basic auth 在外面兜一层, 而 nginx 之外的入口
 * (局域网直连 1132、OPDS/第三方前端) 全是敞的。这里把账号体系做进服务端本身:
 * - 给 web 前端一个登录态 (`Bearer <token>`);
 * - 同时接受 `Basic base64(user:password)` —— 浏览器对已认证 origin 会自动携带,
 *   OPDS 客户端 (KOReader 等) 也是原生支持, 不需要额外握手协议。
 *
 * # 平台注入
 * 桌面/无头端实现见 `io.legado.desktop.help.config.registerDesktopWebAuthProvider`
 * (java.util.prefs 持久化 + SHA-256 加盐摘要 + 内存 token 表)。
 * 未注册实现时 [WebAuthProviders.getOrNull] 返回 null, 鉴权整体关闭 (保持旧行为)。
 */
interface WebAuthProvider {

    /** 是否已配置访问密码; 未配置时鉴权关闭。 */
    fun isEnabled(): Boolean

    /** 当前用户名 (默认 "legado")。 */
    fun userName(): String

    /**
     * 校验 `Authorization` 头。接受两种形式:
     * - `Bearer <token>`  —— web 前端登录后携带
     * - `Basic base64(user:password)` —— OPDS / KOReader 等标准客户端
     */
    fun verifyAuthorization(authorization: String?): Boolean

    /** 校验查询参数里的 token (WebSocket 握手无法自定义请求头)。 */
    fun verifyToken(token: String?): Boolean

    /**
     * 直接校验用户名/口令 (无副作用, 不签发 token)。
     * 给「只能带查询参数」的客户端用 —— 例如 KOReader 的 legado.koplugin 类型1 客户端,
     * 它的鉴权中间件只能改 QUERY_STRING, 无法设置请求头。
     */
    fun verifyPassword(user: String, password: String): Boolean

    /** 登录; 成功返回 token, 失败返回 null。 */
    fun login(user: String, password: String): String?

    /** 注销 (幂等)。 */
    fun logout(token: String?)

    /** 改密码; 成功返回 null, 失败返回错误信息。 */
    fun changePassword(oldPassword: String, newPassword: String): String?
}

object WebAuthProviders {

    @Volatile
    private var impl: WebAuthProvider? = null

    fun register(provider: WebAuthProvider) {
        impl = provider
    }

    /** 未注册实现时返回 null (鉴权关闭)。 */
    fun getOrNull(): WebAuthProvider? = impl
}
