package io.legado.desktop.help.config

import io.legado.app.help.config.PreferenceProviders
import io.legado.app.web.auth.WebAuthProvider
import io.legado.app.web.auth.WebAuthProviders
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 桌面 / 无头端 [WebAuthProvider] 实现。
 *
 * - 凭据落 [PreferenceProviders] (java.util.prefs, 与 webPort / WebDAV 同源),
 *   只存 `salt$sha256hex(salt + password)`, **不存明文**;
 * - token 只放内存 (进程重启即全部失效, 与旧 warpdotsys/reader 的会话语义一致);
 * - 口令比较走常量时间实现, 避免按字节短路泄露前缀。
 *
 * 注册时机: 需 [PreferenceProviders] 已就绪 (即 registerDesktopConfig 之后)。
 */
private const val KEY_WEB_USER = "webAuthUser"
private const val KEY_WEB_PASSWORD = "webAuthPassword"

/** token 有效期: 30 天。 */
private const val TOKEN_TTL_MS = 30L * 24 * 60 * 60 * 1000

private object DesktopWebAuthProvider : WebAuthProvider {

    private val prefs get() = PreferenceProviders.get()
    private val tokens = ConcurrentHashMap<String, Long>()
    private val random = SecureRandom()

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun randomHex(bytes: Int): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        if (x.size != y.size) return false
        var diff = 0
        for (i in x.indices) diff = diff or (x[i].toInt() xor y[i].toInt())
        return diff == 0
    }

    /** 存的是 `salt$hash`; null 表示未设置密码。 */
    private fun storedPassword(): String? =
        prefs.getString(KEY_WEB_PASSWORD, "").takeIf { it.isNotEmpty() }

    private fun matches(password: String): Boolean {
        val stored = storedPassword() ?: return false
        val idx = stored.indexOf('$')
        if (idx <= 0) return false
        val salt = stored.substring(0, idx)
        val expected = stored.substring(idx + 1)
        return constantTimeEquals(expected, sha256Hex(salt + password))
    }

    override fun isEnabled(): Boolean = storedPassword() != null

    override fun userName(): String =
        prefs.getString(KEY_WEB_USER, "legado").takeIf { it.isNotBlank() } ?: "legado"

    override fun verifyAuthorization(authorization: String?): Boolean {
        if (!isEnabled()) return true
        val header = authorization?.trim().orEmpty()
        if (header.isEmpty()) return false
        val space = header.indexOf(' ')
        if (space <= 0) return false
        val scheme = header.substring(0, space).lowercase()
        val value = header.substring(space + 1).trim()
        return when (scheme) {
            "bearer" -> verifyToken(value)
            "basic" -> {
                val decoded = runCatching {
                    String(Base64.getDecoder().decode(value), Charsets.UTF_8)
                }.getOrNull() ?: return false
                val colon = decoded.indexOf(':')
                if (colon < 0) return false
                val user = decoded.substring(0, colon)
                val password = decoded.substring(colon + 1)
                constantTimeEquals(user, userName()) && matches(password)
            }
            else -> false
        }
    }

    override fun verifyToken(token: String?): Boolean {
        if (!isEnabled()) return true
        val t = token?.trim().orEmpty()
        if (t.isEmpty()) return false
        val expiry = tokens[t] ?: return false
        if (expiry < System.currentTimeMillis()) {
            tokens.remove(t)
            return false
        }
        return true
    }

    override fun login(user: String, password: String): String? {
        if (!isEnabled()) return null
        if (!constantTimeEquals(user, userName()) || !matches(password)) return null
        val token = randomHex(24)
        tokens[token] = System.currentTimeMillis() + TOKEN_TTL_MS
        return token
    }

    override fun logout(token: String?) {
        token?.trim()?.takeIf { it.isNotEmpty() }?.let { tokens.remove(it) }
    }

    override fun changePassword(oldPassword: String, newPassword: String): String? {
        if (newPassword.length < 4) return "新密码至少 4 位"
        if (isEnabled() && !matches(oldPassword)) return "原密码不正确"
        val salt = randomHex(8)
        prefs.putString(KEY_WEB_PASSWORD, "$salt$" + sha256Hex(salt + newPassword))
        // 改密后旧会话全部失效
        tokens.clear()
        return null
    }
}

/** 桌面/无头 main 入口早期注册一次 (需 PreferenceProviders 已就绪)。 */
fun registerDesktopWebAuthProvider() {
    WebAuthProviders.register(DesktopWebAuthProvider)
}
