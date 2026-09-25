package io.legado.app.api.controller

import io.legado.app.api.ReturnData
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.storage.BackupShared
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/**
 * WebDAV 配置 / 备份 / 恢复 Web 接口。
 *
 * 复用全端统一管线, 不另造结构:
 * - 配置读写走 [PreferenceProviders] 的 `webDav*` 键 (与 App/桌面设置界面同一份)
 * - 备份走 [BackupShared.backupLocked] (与 App 手动备份/自动备份同一 zip 格式与命名)
 * - 列表/下载/恢复走 [AppWebDavShared] (getBackupNames / restoreWebDav)
 *
 * 冲突处理: 云端已有同名 (backup{日期}[-设备名].zip) 备份时不覆盖, 需调用方带
 * `{"force":true}` 才重传 —— 与 [BackupShared.autoBack] 的「当日已有则跳过」同源。
 */
object WebDavController {

    /** 当前配置 (密码不回传, 只给 hasPassword 布尔)。 */
    fun getConfig(): ReturnData {
        val appConfig = AppConfigProviders.get()
        val dir = PreferenceProviders.get().getString(PreferKey.webDavDir, "legado")
        return ReturnData().setData(
            buildJsonObject {
                put("url", appConfig.webDavUrl)
                put("account", appConfig.webDavAccount)
                put("dir", dir)
                put("deviceName", appConfig.webDavDeviceName)
                put("hasPassword", appConfig.webDavPassword.isNotEmpty())
                put("isOk", AppWebDavShared.isOk)
            }.toString()
        )
    }

    /**
     * 保存配置并立即校验连通性。
     *
     * 语义:
     * - `password` 缺省或空串 = 保留原密码 (前端不必回填明文)
     * - `clearPassword:true` = 显式清空密码
     * - 校验失败时 [AppWebDavShared.upConfig] 内部已清空错误密码
     */
    suspend fun saveConfig(postData: String?): ReturnData {
        val obj = parseObject(postData) ?: return ReturnData().setErrorMsg("请求体格式错误")
        val prefs = PreferenceProviders.get()
        obj["url"]?.jsonPrimitive?.contentOrNull()?.let { prefs.putString(PreferKey.webDavUrl, it.trim()) }
        obj["account"]?.jsonPrimitive?.contentOrNull()?.let {
            prefs.putString(PreferKey.webDavAccount, it.trim())
        }
        obj["dir"]?.jsonPrimitive?.contentOrNull()?.let { prefs.putString(PreferKey.webDavDir, it.trim()) }
        obj["deviceName"]?.jsonPrimitive?.contentOrNull()?.let {
            prefs.putString(PreferKey.webDavDeviceName, it.trim())
        }
        val clearPassword = obj["clearPassword"]?.jsonPrimitive?.booleanOrNull == true
        val password = obj["password"]?.jsonPrimitive?.contentOrNull()
        if (clearPassword) {
            prefs.putString(PreferKey.webDavPassword, "")
        } else if (!password.isNullOrEmpty()) {
            prefs.putString(PreferKey.webDavPassword, password)
        }
        return verify()
    }

    /** 校验当前配置是否可用 (upConfig 会建好 root/bookProgress/books/background 四个目录)。 */
    suspend fun verify(): ReturnData = runWebDav {
        if (AppWebDavShared.isOk) {
            ReturnData().setData(configStatusJson())
        } else {
            ReturnData().setErrorMsg("未配置 WebDAV 账号或密码")
        }
    }

    /** 列出云端 backup* 备份文件名 (按名称倒序)。 */
    suspend fun listBackups(): ReturnData = runWebDav {
        val names = AppWebDavShared.getBackupNames()
        ReturnData().setData(
            buildJsonArray { names.forEach { add(JsonPrimitive(it)) } }.toString()
        )
    }

    /**
     * 生成备份并上传 WebDAV (body 可选 `{"force":true}` 覆盖同名备份)。
     *
     * 文件名由 [BackupShared.nowZipFileName] 决定, 与上传时内部生成的一致。
     */
    suspend fun backup(postData: String?): ReturnData = runWebDav {
        val force = parseObject(postData)?.get("force")?.jsonPrimitive?.booleanOrNull == true
        val fileName = BackupShared.nowZipFileName()
        if (!force && AppWebDavShared.hasBackUp(fileName)) {
            return@runWebDav ReturnData().setErrorMsg(
                "云端已存在同名备份 $fileName, 如需覆盖请确认后重试"
            )
        }
        BackupShared.backupLocked(uploadToWebDav = true)
        ReturnData().setData(buildJsonObject { put("fileName", fileName) }.toString())
    }

    /** 从云端指定备份恢复 (body `{"name":"backup2026-09-25.zip"}`)。 */
    suspend fun restore(postData: String?): ReturnData {
        val obj = parseObject(postData) ?: return ReturnData().setErrorMsg("请求体格式错误")
        val name = obj["name"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        if (name.isEmpty()) return ReturnData().setErrorMsg("备份文件名不能为空")
        // 防目录穿越: 只允许根目录下形如 backup*.zip 的普通文件名
        if (name.contains('/') || name.contains('\\') || name.contains("..") ||
            !name.startsWith("backup") || !name.endsWith(".zip")
        ) {
            return ReturnData().setErrorMsg("备份文件名不合法")
        }
        return runWebDav {
            AppWebDavShared.restoreWebDav(name)
            ReturnData().setData(true)
        }
    }

    /** 统一: 先 upConfig 校验配置, 再执行 action; 异常转 errorMsg, 取消异常原样抛。 */
    private suspend inline fun runWebDav(crossinline action: () -> ReturnData): ReturnData {
        return try {
            AppWebDavShared.upConfig()
            if (!AppWebDavShared.isOk) {
                ReturnData().setErrorMsg("未配置 WebDAV 账号或密码")
            } else {
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReturnData().setErrorMsg(e.message ?: "WebDAV 操作失败")
        }
    }

    private fun configStatusJson(): String {
        val appConfig = AppConfigProviders.get()
        return buildJsonObject {
            put("url", appConfig.webDavUrl)
            put("account", appConfig.webDavAccount)
            put("dir", PreferenceProviders.get().getString(PreferKey.webDavDir, "legado"))
            put("deviceName", appConfig.webDavDeviceName)
            put("hasPassword", appConfig.webDavPassword.isNotEmpty())
            put("isOk", AppWebDavShared.isOk)
        }.toString()
    }

    private fun parseObject(postData: String?) =
        runCatching { Json.parseToJsonElement(postData ?: "").jsonObject }.getOrNull()
}
