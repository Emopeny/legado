<template>
  <div :class="{ 'my-wrapper': true, night: isNight, day: !isNight }">
    <header class="page-topbar">
      <span class="topbar-title">我的</span>
    </header>

    <div class="my-scroll">
      <!-- 外观设置 -->
      <div class="card">
        <div class="card-title">外观设置</div>
        <div class="card-row link-row" @click="toggleTheme">
          <div class="row-content">
            <span class="row-label">深色模式</span>
            <span class="row-desc">{{ isNight ? '已开启（深色主题）' : '已关闭（浅色主题）' }}</span>
          </div>
          <div class="switch-pill" :class="{ active: isNight }">
            <div class="switch-knob"></div>
          </div>
        </div>
      </div>

      <!-- 书库与书源管理 -->
      <div class="card">
        <div class="card-title">书库与书源</div>
        
        <!-- 上传本地书 -->
        <div class="card-row link-row" @click="uploadInput?.click()">
          <span class="row-label">上传本地书籍</span>
          <span class="row-arrow">›</span>
          <input
            ref="uploadInput"
            type="file"
            accept=".txt,.epub,.zip,.cbz"
            hidden
            @change="onUploadBookFile"
          />
        </div>

        <!-- 书源管理 -->
        <div class="card-row link-row" @click="router.push('/sources')">
          <span class="row-label">书源管理</span>
          <span class="row-arrow">›</span>
        </div>

        <!-- 替换净化 -->
        <div class="card-row link-row" @click="router.push('/replace-rules')">
          <div class="row-content">
            <span class="row-label">替换净化</span>
            <span class="row-desc">正则替换正文/标题中的广告与杂项</span>
          </div>
          <span class="row-arrow">›</span>
        </div>
      </div>

      <!-- 备份与恢复 -->
      <div class="card">
        <div class="card-title">备份与恢复</div>
        <div class="backup-actions">
          <button
            class="backup-btn primary"
            type="button"
            :disabled="busy"
            @click="exportBackup"
          >
            导出备份
          </button>
          <button class="backup-btn" type="button" :disabled="busy" @click="importInput?.click()">
            导入恢复
          </button>
          <input
            ref="importInput"
            type="file"
            accept="application/zip,.zip"
            hidden
            @change="onImportFile"
          />
        </div>
      </div>

      <!-- 访问密码 -->
      <div class="card">
        <div class="card-title">访问密码</div>
        <div class="card-row">
          <div class="row-content">
            <span class="row-label">{{ authEnabledRef ? '已启用访问密码' : '未启用访问密码' }}</span>
            <span class="row-desc">{{
              authEnabledRef
                ? '服务端 API 需要登录后才能访问；修改后 KOReader 插件需用新密码登录'
                : '设置后服务端 API 与 WebDAV 接口都需要登录（KOReader 插件请先确认能登录）'
            }}</span>
          </div>
        </div>
        <div class="webdav-form">
          <input
            v-if="authEnabledRef"
            v-model="pwdForm.oldPassword"
            class="webdav-input"
            type="password"
            autocomplete="current-password"
            placeholder="当前密码"
          />
          <input
            v-model="pwdForm.newPassword"
            class="webdav-input"
            type="password"
            autocomplete="new-password"
            placeholder="新密码（至少 4 位）"
          />
          <input
            v-model="pwdForm.confirmPassword"
            class="webdav-input"
            type="password"
            autocomplete="new-password"
            placeholder="确认新密码"
          />
          <button class="backup-btn primary" type="button" :disabled="busy" @click="savePassword">
            {{ authEnabledRef ? '修改访问密码' : '设置访问密码' }}
          </button>
        </div>
      </div>

      <!-- WebDAV 云端备份 -->
      <div class="card">
        <div class="card-title">WebDAV 云端备份</div>
        <div class="card-row link-row" @click="toggleWebDavForm">
          <div class="row-content">
            <span class="row-label">WebDAV 配置</span>
            <span class="row-desc">{{ webDavSummary }}</span>
          </div>
          <span class="row-arrow">{{ showWebDavForm ? '\u25be' : '\u203a' }}</span>
        </div>
        <div v-if="showWebDavForm" class="webdav-form">
          <input
            v-model="webDavForm.url"
            class="webdav-input"
            type="text"
            placeholder="WebDAV 地址，如 https://dav.jianguoyun.com/dav/"
          />
          <input v-model="webDavForm.account" class="webdav-input" type="text" placeholder="账号" />
          <input
            v-model="webDavForm.password"
            class="webdav-input"
            type="password"
            :placeholder="webDav.hasPassword ? '密码（留空则不修改）' : '密码'"
          />
          <input
            v-model="webDavForm.dir"
            class="webdav-input"
            type="text"
            placeholder="子目录（默认 legado）"
          />
          <input
            v-model="webDavForm.deviceName"
            class="webdav-input"
            type="text"
            placeholder="设备名（可选，用于区分备份文件）"
          />
          <button class="backup-btn primary" type="button" :disabled="busy" @click="saveWebDav">
            保存并测试连接
          </button>
        </div>
        <div class="backup-actions">
          <button
            class="backup-btn primary"
            type="button"
            :disabled="busy || !webDav.isOk"
            @click="doWebDavBackup"
          >
            上传备份
          </button>
          <button
            class="backup-btn"
            type="button"
            :disabled="busy || !webDav.isOk"
            @click="openRestorePicker"
          >
            从云端恢复
          </button>
        </div>
        <div v-if="showRestorePicker" class="webdav-restore">
          <div v-if="backupNames.length === 0" class="webdav-empty">云端暂无备份</div>
          <template v-else>
            <select v-model="selectedBackup" class="webdav-input">
              <option v-for="n in backupNames" :key="n" :value="n">{{ n }}</option>
            </select>
            <button
              class="backup-btn primary"
              type="button"
              :disabled="busy || !selectedBackup"
              @click="doWebDavRestore"
            >
              恢复所选备份
            </button>
          </template>
        </div>
      </div>

      <!-- 更多与帮助 -->
      <div class="card">
        <div class="card-title">更多</div>
        <a class="card-row link-row" href="./help/index.html" target="_blank">
          <span class="row-label">使用帮助</span>
          <span class="row-arrow">›</span>
        </a>
        <div v-if="authEnabledRef" class="card-row link-row" @click="doLogout">
          <span class="row-label">退出登录</span>
          <span class="row-arrow">›</span>
        </div>
        <div class="card-row">
          <div class="row-content">
            <span class="row-label">Legado Web</span>
            <span class="row-desc">轻量化跨平台开源阅读器</span>
          </div>
          <span class="row-value">v3.0</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'MyPage' })

import '@/assets/webui.css'
import { useBookStore } from '@/store'
import API from '@api'
import { authEnabled as authEnabledRef, changePassword, fetchAuthMe, logout } from '@/api/auth'
import { toast, msgbox } from '@/utils/toast'

const router = useRouter()
const store = useBookStore()
const isNight = computed(() => store.isNight)

const toggleTheme = () => {
  const nextTheme = store.isNight ? 0 : 6
  store.config.theme = nextTheme
  API.saveReadConfig(store.config)
}

const busy = ref(false)
const isUploadingBook = ref(false)
const importInput = ref<HTMLInputElement>()
const uploadInput = ref<HTMLInputElement>()
// 上传本地书籍
const onUploadBookFile = async (evt: Event) => {
  const input = evt.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return

  busy.value = true
  isUploadingBook.value = true
  const toastId = 'upload-book'
  toast.info(`正在上传并导入《${file.name}》...`)

  try {
    const resp = await API.addLocalBook(file)
    if (resp.isSuccess) {
      toast.success(`《${file.name}》导入成功，已加入书架！`)
      store.clearShelfCache()
      await store.loadBookShelf()
    } else {
      toast.error(resp.errorMsg || '上传失败')
    }
  } catch (e) {
    console.error('addLocalBook error:', e)
    toast.error((e as Error)?.message || '上传导入发生异常')
  } finally {
    busy.value = false
    isUploadingBook.value = false
  }
}

const exportBackup = async () => {
  busy.value = true
  try {
    const blob = await API.getBackupZip()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `legado-backup-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '')}.zip`
    a.click()
    URL.revokeObjectURL(url)
    toast.success('备份已导出 (标准 zip 格式)')
  } catch (e) {
    toast.error((e as Error)?.message || '备份导出失败')
    throw e
  } finally {
    busy.value = false
  }
}

const onImportFile = async (evt: Event) => {
  const input = evt.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  busy.value = true
  try {
    const resp = await API.restoreBackup(file)
    if (resp.isSuccess) {
      toast.success('恢复完成，数据已从备份包写回')
      store.clearShelfCache()
      store.loadGroups()
      store.loadBookShelf()
    } else {
      toast.error(resp.errorMsg || '恢复失败')
    }
  } catch (e) {
    console.error('restoreBackup error:', e)
    toast.error((e as Error)?.message || '恢复失败')
  } finally {
    busy.value = false
  }
}

// ---- WebDAV 云端备份 ----
const webDav = reactive({
  url: '',
  account: '',
  dir: '',
  deviceName: '',
  hasPassword: false,
  isOk: false,
})
const webDavForm = reactive({
  url: '',
  account: '',
  password: '',
  dir: '',
  deviceName: '',
})
const showWebDavForm = ref(false)
const showRestorePicker = ref(false)
const backupNames = ref<string[]>([])
const selectedBackup = ref('')

const webDavSummary = computed(() => {
  if (!webDav.url && !webDav.account) return '未配置，点击展开填写'
  if (!webDav.isOk) return `${webDav.account || '未填账号'} · 未连接`
  return `${webDav.account} · ${webDav.url}`
})

const applyWebDav = (cfg: {
  url: string
  account: string
  dir: string
  deviceName: string
  hasPassword: boolean
  isOk: boolean
}) => {
  webDav.url = cfg.url || ''
  webDav.account = cfg.account || ''
  webDav.dir = cfg.dir || ''
  webDav.deviceName = cfg.deviceName || ''
  webDav.hasPassword = cfg.hasPassword === true
  webDav.isOk = cfg.isOk === true
  webDavForm.url = webDav.url
  webDavForm.account = webDav.account
  webDavForm.dir = webDav.dir
  webDavForm.deviceName = webDav.deviceName
  webDavForm.password = ''
}

const loadWebDav = async () => {
  try {
    applyWebDav(await API.getWebDavConfig())
  } catch (e) {
    console.error('getWebDavConfig error:', e)
  }
}

const toggleWebDavForm = async () => {
  showWebDavForm.value = !showWebDavForm.value
  if (showWebDavForm.value) await loadWebDav()
}

const saveWebDav = async () => {
  busy.value = true
  try {
    const cfg = await API.saveWebDavConfig({ ...webDavForm })
    applyWebDav(cfg)
    toast.success(cfg.isOk ? '配置已保存，WebDAV 连接正常' : '配置已保存，但连接未通过')
  } catch (e) {
    toast.error((e as Error)?.message || '保存 WebDAV 配置失败')
  } finally {
    busy.value = false
  }
}

const doWebDavBackup = async () => {
  busy.value = true
  try {
    const fileName = await API.webDavBackup(false)
    toast.success(`已上传云端备份 ${fileName}`)
  } catch (e) {
    const msg = (e as Error)?.message || '上传备份失败'
    if (msg.includes('已存在同名备份')) {
      try {
        await msgbox.confirm(msg, '覆盖云端备份')
        const fileName = await API.webDavBackup(true)
        toast.success(`已覆盖上传 ${fileName}`)
      } catch {
        // 用户取消
      }
      return
    }
    toast.error(msg)
  } finally {
    busy.value = false
  }
}

const openRestorePicker = async () => {
  busy.value = true
  try {
    backupNames.value = await API.listWebDavBackups()
    selectedBackup.value = backupNames.value[0] || ''
    showRestorePicker.value = true
  } catch (e) {
    toast.error((e as Error)?.message || '获取云端备份列表失败')
  } finally {
    busy.value = false
  }
}

const doWebDavRestore = async () => {
  if (!selectedBackup.value) return
  busy.value = true
  try {
    await API.webDavRestore(selectedBackup.value)
    toast.success(`已从 ${selectedBackup.value} 恢复，数据已写回`)
    store.clearShelfCache()
    store.loadGroups()
    store.loadBookShelf()
    showRestorePicker.value = false
  } catch (e) {
    toast.error((e as Error)?.message || '恢复失败')
  } finally {
    busy.value = false
  }
}

const doLogout = async () => {
  await logout()
  router.replace('/login')
}

// ---- 访问密码 ----
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const savePassword = async () => {
  if (pwdForm.newPassword.length < 4) {
    toast.warning('新密码至少 4 位')
    return
  }
  if (pwdForm.newPassword !== pwdForm.confirmPassword) {
    toast.warning('两次输入的新密码不一致')
    return
  }
  busy.value = true
  try {
    await changePassword(pwdForm.oldPassword, pwdForm.newPassword)
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdForm.confirmPassword = ''
    await fetchAuthMe()
    toast.success('访问密码已更新')
  } catch (e) {
    toast.error((e as Error)?.message || '设置访问密码失败')
  } finally {
    busy.value = false
  }
}

onMounted(loadWebDav)
</script>

<style lang="scss" scoped>
.my-wrapper {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: column;
  background-color: #f7f7f7;

  .page-topbar {
    flex: none;
    height: 52px;
    display: flex;
    align-items: center;
    padding: 0 16px;

    .topbar-title {
      font-size: 20px;
      font-weight: 600;
      color: var(--web-text);
    }
  }

  .my-scroll {
    flex: 1;
    overflow-y: auto;
    padding: 4px 16px calc(72px + env(safe-area-inset-bottom));
  }

  .card {
    margin-bottom: 14px;
    border-radius: 8px;
    background: #fff;
    padding: 12px 14px;

    .card-title {
      font-size: 14px;
      font-weight: 600;
      color: #888;
      margin-bottom: 8px;
    }

    .card-row {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 10px 4px;
      font-size: 14px;
      text-decoration: none;
      border-bottom: 1px solid #f5f5f5;

      &:last-child {
        border-bottom: none;
      }

      .row-content {
        flex: 1;
        min-width: 0;
        display: flex;
        flex-direction: column;

        .row-label {
          font-weight: 500;
          color: var(--web-text, #333);
        }

        .row-desc {
          font-size: 12px;
          color: #999;
          margin-top: 2px;
        }
      }

      .row-value {
        color: #888;
        font-size: 12px;
      }

      .row-arrow {
        font-size: 18px;
        color: #bbb;
      }

      .switch-pill {
        width: 44px;
        height: 24px;
        border-radius: 12px;
        background: #e2e8f0;
        position: relative;
        cursor: pointer;
        transition: background-color 0.2s;
        flex-shrink: 0;

        .switch-knob {
          position: absolute;
          top: 2px;
          left: 2px;
          width: 20px;
          height: 20px;
          border-radius: 50%;
          background: #fff;
          box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2);
          transition: transform 0.2s;
        }

        &.active {
          background: var(--web-primary, #1e80ff);

          .switch-knob {
            transform: translateX(20px);
          }
        }
      }

      &.link-row {
        cursor: pointer;

        &:hover {
          background: rgba(0, 0, 0, 0.02);
          border-radius: 6px;
        }
      }
    }

    .webdav-form,
    .webdav-restore {
      display: flex;
      flex-direction: column;
      gap: 8px;
      padding: 6px 0 4px;
    }

    .webdav-input {
      width: 100%;
      height: 34px;
      border: 1px solid var(--web-border, #dcdfe6);
      border-radius: 6px;
      padding: 0 10px;
      font-size: 13px;
      color: var(--web-text, #333);
      background: #fff;
      outline: none;
      box-sizing: border-box;

      &:focus {
        border-color: var(--web-primary, #1e80ff);
      }
    }

    .webdav-empty {
      font-size: 13px;
      color: #999;
      padding: 4px 0;
    }

    .backup-actions {
      display: flex;
      gap: 10px;
      padding: 6px 0 4px;

      .backup-btn {
        flex: 1;
        border: 1px solid var(--web-border);
        border-radius: 6px;
        background: #fff;
        padding: 8px 0;
        font-size: 14px;
        color: var(--web-text);
        cursor: pointer;

        &.primary {
          background: var(--web-primary);
          border-color: var(--web-primary);
          color: #fff;
        }

        &:disabled {
          opacity: 0.6;
          cursor: not-allowed;
        }
      }
    }
  }
}

.night {
  background-color: #161819;

  .page-topbar {
    .topbar-title {
      color: #aeaeae;
    }
  }

  .card {
    background: #454545;

    .card-row {
      border-bottom-color: #3e3e3e;

      .row-content .row-label {
        color: #ddd;
      }

      .switch-pill {
        background: #333;

        &.active {
          background: var(--web-primary, #1e80ff);
        }
      }

      &.link-row:hover {
        background: rgba(255, 255, 255, 0.04);
      }
    }
  }

  .card .card-row .row-value {
    color: #aeaeae;
  }

  .card .webdav-input {
    background: #555;
    border-color: #666;
    color: #ccc;
  }

  .card .webdav-empty {
    color: #999;
  }

  .card .backup-actions .backup-btn {
    background: #555;
    border-color: #666;
    color: #ccc;

    &.primary {
      background: var(--web-primary);
      border-color: var(--web-primary);
      color: #fff;
    }
  }
}
</style>
