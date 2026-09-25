import ajax from './axios'

/** 服务端 WebApi 统一信封 (本地声明, 避免与 ./api 形成循环依赖) */
type ApiEnvelope<T> = { isSuccess: boolean; errorMsg: string; data: T }

const TOKEN_KEY = 'legado_auth_token'

/**
 * 内置账号鉴权状态。
 *
 * - 服务端未配置访问密码时 `/auth/me` 返回 authEnabled=false, 前端视为已通过, 全程无感
 * - 服务端启用密码后, 未认证会回登录页; token 存 localStorage (服务端 token 仅内存,
 *   重启后失效 → 下一次请求 401 → 自动清 token 并回登录页)
 */
export const authToken = ref<string>(
  typeof localStorage !== 'undefined' ? localStorage.getItem(TOKEN_KEY) || '' : '',
)
export const authEnabled = ref(false)
export const authUser = ref('')
export const authOk = ref(true)

export const getToken = () => authToken.value

export const setToken = (token: string) => {
  authToken.value = token || ''
  if (typeof localStorage === 'undefined') return
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

export const clearToken = () => setToken('')

/** 有 token 时带 Bearer (WebApi.authorized 接受 Bearer/Basic/?token= 三种) */
export const authHeaders = (): Record<string, string> =>
  authToken.value ? { Authorization: `Bearer ${authToken.value}` } : {}

/** 读取 /auth/me: 未启用鉴权视为通过; 启用且未认证则清 token 并返回 false */
export const fetchAuthMe = async (): Promise<boolean> => {
  try {
    const { data } = await ajax.get<ApiEnvelope<string>>('auth/me')
    if (!data?.isSuccess) return true
    const info = JSON.parse(data.data || '{}') as {
      authEnabled?: boolean
      authenticated?: boolean
      user?: string
    }
    authEnabled.value = info.authEnabled === true
    authUser.value = info.user || ''
    const ok = info.authEnabled !== true || info.authenticated === true
    authOk.value = ok
    if (!ok) clearToken()
    return ok
  } catch {
    // 后端不可达时不要卡死在登录页, 连接状态提示由响应拦截器负责
    return true
  }
}

let ensured: Promise<boolean> | null = null

/** 应用启动/导航时校验一次, 结果复用 (登录/登出后 resetAuthCheck) */
export const ensureAuth = () => (ensured ??= fetchAuthMe())

export const resetAuthCheck = () => {
  ensured = null
}

export const login = async (user: string, password: string) => {
  const { data } = await ajax.post<ApiEnvelope<string>>('auth/login', { user, password })
  if (!data?.isSuccess) throw new Error(data?.errorMsg || '登录失败')
  setToken(String(data.data || ''))
  resetAuthCheck()
  await fetchAuthMe()
  return true
}

export const logout = async () => {
  try {
    await ajax.post<ApiEnvelope<string>>('auth/logout', {})
  } catch {
    // 忽略: 本地清 token 即可
  }
  clearToken()
  authUser.value = ''
  resetAuthCheck()
}

export const changePassword = async (oldPassword: string, newPassword: string) => {
  const { data } = await ajax.post<ApiEnvelope<string>>('auth/password', {
    oldPassword,
    newPassword,
  })
  if (!data?.isSuccess) throw new Error(data?.errorMsg || '修改密码失败')
  return true
}
