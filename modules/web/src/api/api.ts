/** https://github.com/gedoor/legado/tree/master/app/src/main/java/io/legado/app/api */
/** https://github.com/gedoor/legado/tree/master/app/src/main/java/io/legado/app/web */

import type { webReadConfig } from '@/web'
import ajax from './axios'
import { authHeaders } from './auth'
import type { BaseBook, Book, BookChapter, BookGroup, BookProgress, SeachBook } from '@/book'
import type { RawSource, Source } from '@/source'

export type LeagdoApiResponse<T> = {
  isSuccess: boolean
  errorMsg: string
  data: T
}

export let legado_http_entry_point = ''
export let legado_webSocket_entry_point = ''

let wsOnError: typeof WebSocket.prototype.onerror = () => {}
let wsOnMessage: typeof WebSocket.prototype.onmessage = () => {}
export const setWebsocketOnMessage = (callback: typeof wsOnMessage) =>
  (wsOnMessage = callback)
export const setWebsocketOnError = (callback: typeof wsOnError) => {
  wsOnError = callback
}

export const setApiEntryPoint = (
  http_entry_point: string,
  webSocket_entry_point: string,
) => {
  legado_http_entry_point = new URL(http_entry_point).toString()
  legado_webSocket_entry_point = new URL(webSocket_entry_point).toString()
  ajax.defaults.baseURL = legado_http_entry_point
}

// 书架API
const getReadConfig = async (http_url = legado_http_entry_point) => {
  const { data } = await ajax.get<LeagdoApiResponse<string>>('getReadConfig', {
    baseURL: http_url.toString(),
  })
  if (data.isSuccess) {
    try {
      return JSON.parse(data.data) as webReadConfig
    } catch {}
  }
}
const saveReadConfig = (config: webReadConfig) =>
  ajax.post<LeagdoApiResponse<unknown>>('saveReadConfig', config)

const saveBookProgress = (bookProgress: BookProgress) =>
  ajax.post<LeagdoApiResponse<unknown>>('saveBookProgress', bookProgress)

const saveBookProgressWithBeacon = (bookProgress: BookProgress) => {
  if (!bookProgress) return
  navigator.sendBeacon(
    new URL('saveBookProgress', legado_http_entry_point),
    JSON.stringify(bookProgress),
  )
}

const getGroups = () => ajax.get<LeagdoApiResponse<BookGroup[]>>('getGroups')

const getBookShelf = (groupId?: number | string) => {
  const url = groupId !== undefined ? `getBookshelf?groupId=${groupId}` : 'getBookshelf'
  return ajax.get<LeagdoApiResponse<Book[]>>(url)
}

export type CatalogBookMetadata = Pick<BaseBook, 'bookUrl' | 'name' | 'author'> & {
  origin?: string
  originName?: string
  tocUrl?: string
  type?: number
  coverUrl?: string
  intro?: string
  kind?: string
  wordCount?: string
  variable?: string
}

const catalogQuery = (book: CatalogBookMetadata) => {
  const params = new URLSearchParams({
    url: book.bookUrl,
    name: book.name,
    author: book.author,
  })
  const optional: Array<[string, unknown]> = [
    ['origin', book.origin],
    ['originName', book.originName],
    ['tocUrl', book.tocUrl],
    ['type', book.type],
    ['coverUrl', book.coverUrl],
    ['intro', book.intro],
    ['kind', book.kind],
    ['wordCount', book.wordCount],
    ['variable', book.variable],
  ]
  for (const [key, value] of optional) {
    if (value !== undefined && value !== null && value !== '') {
      params.set(key, String(value))
    }
  }
  return params.toString()
}

const getChapterList = (book: CatalogBookMetadata) =>
  ajax.get<LeagdoApiResponse<BookChapter[]>>('getChapterList?' + catalogQuery(book))

const refreshToc = (book: CatalogBookMetadata) =>
  ajax.get<LeagdoApiResponse<BookChapter[]>>('refreshToc?' + catalogQuery(book))

const getBookContent = (
  bookUrl: string,
  origin: string | undefined,
  chapterIndex: number,
  refresh = false,
) => {
  const params = new URLSearchParams({ url: bookUrl, index: String(chapterIndex) })
  if (origin) params.set('origin', origin)
  if (refresh) params.set('refresh', 'true')
  return ajax.get<LeagdoApiResponse<string>>('getBookContent?' + params.toString())
}

const search = (
  searchKey: string,
  onReceive: (data: SeachBook[]) => void,
  onFinish: () => void,
  scope?: string,
) => {
  const socket = new WebSocket(
    new URL('searchBook', legado_webSocket_entry_point),
  )
  let finished = false
  const finishOnce = () => {
    if (finished) return
    finished = true
    onFinish()
  }
  socket.onerror = wsOnError

  socket.onopen = () => {
    if (finished || socket.readyState !== WebSocket.OPEN) return
    const payload: { key: string; scope: string } = {
      key: searchKey,
      scope: scope && scope.trim() ? scope.trim() : 'all',
    }
    socket.send(JSON.stringify(payload))
  }
  socket.onmessage = event => {
    if (finished || event.currentTarget !== socket) return
    try {
      onReceive(JSON.parse(event.data))
      wsOnMessage?.call(socket, event)
    } catch {
      finishOnce()
    }
  }

  socket.onclose = event => {
    if (event.currentTarget === socket) finishOnce()
  }

  return socket
}

export interface WebExploreKind {
  title: string
  type?: string
  url?: string
  style?: {
    cols?: number
    rows?: number
    layout_flexBasisPercent?: number
  }
}

const saveBook = (book: BaseBook) => ajax.post<LeagdoApiResponse<unknown>>('saveBook', book)
const deleteBook = (book: BaseBook) => ajax.post<LeagdoApiResponse<unknown>>('deleteBook', book)

export interface WebBookSourcePart {
  bookSourceUrl: string
  bookSourceName: string
  bookSourceGroup?: string
  customOrder: number
  enabled: boolean
  enabledExplore: boolean
  hasLoginUrl: boolean
  lastUpdateTime: number
  respondTime: number
  weight: number
  hasExploreUrl: boolean
}

const getSources = () => ajax.get<LeagdoApiResponse<(RawSource | Source)[]>>('getBookSources')
const getBookSourcesPart = () =>
  ajax.get<LeagdoApiResponse<WebBookSourcePart[]>>('getBookSourcesPart')
const getSource = (url: string) =>
  ajax.get<LeagdoApiResponse<Source>>('getBookSource?url=' + encodeURIComponent(url))

/** 下载全端标准备份 zip (BackupShared 管线); 失败抛 Error(errorMsg) */
const getBackupZip = async (): Promise<Blob> => {
  const base = legado_http_entry_point || location.origin
  const resp = await fetch(new URL('getBackupZip', base).toString(), {
    headers: authHeaders(),
  })
  if ((resp.headers.get('content-type') || '').includes('application/json')) {
    const env = (await resp.json()) as LeagdoApiResponse<unknown>
    throw new Error(env.errorMsg || '备份失败')
  }
  return await resp.blob()
}

/** 上传备份 zip 恢复 (multipart, 全端标准格式) */
const restoreBackup = async (file: File): Promise<LeagdoApiResponse<unknown>> => {
  const base = legado_http_entry_point || location.origin
  const fd = new FormData()
  fd.append('fileData', file, file.name)
  const resp = await fetch(new URL('restoreBackup', base).toString(), {
    method: 'POST',
    body: fd,
    headers: authHeaders(),
  })
  if (!resp.ok) {
    let msg = `HTTP ${resp.status}: ${resp.statusText || '请求失败'}`
    try {
      const errJson = await resp.json()
      if (errJson?.errorMsg) msg = errJson.errorMsg
    } catch {
      // ignore
    }
    throw new Error(msg)
  }
  return (await resp.json()) as LeagdoApiResponse<unknown>
}

/** 上传本地书 (multipart, 支持 txt/epub/zip/cbz) */
const addLocalBook = async (file: File): Promise<LeagdoApiResponse<unknown>> => {
  const base = legado_http_entry_point || location.origin
  const fd = new FormData()
  fd.append('fileData', file, file.name)
  const resp = await fetch(
    new URL(`addLocalBook?fileName=${encodeURIComponent(file.name)}`, base).toString(),
    {
      method: 'POST',
      body: fd,
      headers: authHeaders(),
    },
  )
  if (!resp.ok) {
    let msg = `HTTP ${resp.status}: ${resp.statusText || '上传失败'}`
    try {
      const errJson = await resp.json()
      if (errJson?.errorMsg) msg = errJson.errorMsg
    } catch {
      // ignore
    }
    throw new Error(msg)
  }
  return (await resp.json()) as LeagdoApiResponse<unknown>
}

const saveSource = (data: Source | RawSource) => ajax.post<LeagdoApiResponse<unknown>>('saveBookSource', data)

const saveSources = (data: (Source | RawSource)[]) => ajax.post<LeagdoApiResponse<unknown>>('saveBookSources', data)

const deleteSource = (data: (Source | RawSource)[]) => ajax.post<LeagdoApiResponse<unknown>>('deleteBookSources', data)

// 服务端抓取书源链接 (浏览器直连会被 CORS / 混合内容拦, 故由后端 fetch)
const importBookSourcesFromUrl = (url: string) =>
  ajax.post<LeagdoApiResponse<(RawSource | Source)[]>>('importBookSourcesFromUrl', { url })

const debug = (
  sourceUrl: string,
  searchKey: string,
  onReceive: (data: string) => void,
  onFinish: () => void,
) => {
  const url = new URL(
    'bookSourceDebug',
    legado_webSocket_entry_point,
  )

  const socket = new WebSocket(url)
  socket.onerror = wsOnError
  socket.onopen = () => {
    socket.send(JSON.stringify({ tag: sourceUrl, key: searchKey }))
  }
  socket.onmessage = event => {
    onReceive(event.data)
    wsOnMessage?.call(socket, event)
  }

  socket.onclose = () => {
    onFinish()
  }
}

const toAbsoluteUrl = (path: string): string => {
  if (!path) return ''
  if (/^https?:\/\//i.test(path) || path.startsWith('data:') || path.startsWith('blob:')) {
    return path
  }
  const base = legado_http_entry_point || (typeof location !== 'undefined' ? location.origin : '')
  if (!base) return path
  const relative = path.startsWith('/') ? path.slice(1) : path
  return new URL(relative, base).toString()
}

const getProxyCoverUrl = (coverUrl: string) => {
  if (coverUrl.startsWith(legado_http_entry_point)) return coverUrl
  return new URL(
    'cover?path=' + encodeURIComponent(coverUrl),
    legado_http_entry_point,
  ).toString()
}

const getProxyImageUrl = (
  bookUrl: string,
  src: string,
  _width?: number | `${number}`,
) => {
  if (legado_http_entry_point && src.startsWith(legado_http_entry_point)) return src
  if (src.startsWith('/image?') || src.startsWith('image?')) {
    return toAbsoluteUrl(src)
  }
  return new URL(
    'image?path=' +
      encodeURIComponent(src) +
      '&url=' +
      encodeURIComponent(bookUrl),
    legado_http_entry_point,
  ).toString()
}

/**
 * 清洗媒体地址并仅返回浏览器可直接加载的 URL。
 * `origin` 为兼容旧调用签名保留；媒体代理已永久关闭。
 */
export const getMediaStreamUrl = (url: string, _origin?: string): string => {
  if (!url) return ''
  let clean = url.trim()
  const commaIdx = clean.indexOf(',{')
  if (commaIdx > 0) {
    clean = clean.substring(0, commaIdx).trim()
  }
  return /^(https?:\/\/|data:|blob:)/i.test(clean) ? clean : ''
}

// ---- 替换净化规则 ----

export interface ReplaceRuleItem {
  id?: number
  name: string
  group?: string | null
  pattern: string
  replacement: string
  scope?: string | null
  scopeTitle?: boolean
  scopeContent?: boolean
  excludeScope?: string | null
  isEnabled?: boolean
  isRegex?: boolean
  timeoutMillisecond?: number
  order?: number
}

/** 全部替换净化规则 (服务端 data 为 JSON 字符串) */
const getReplaceRules = async (): Promise<ReplaceRuleItem[]> => {
  const { data } = await ajax.get<LeagdoApiResponse<string>>('getReplaceRules')
  if (!data.isSuccess) throw new Error(data.errorMsg || '获取替换规则失败')
  try {
    return JSON.parse(data.data || '[]') as ReplaceRuleItem[]
  } catch {
    return []
  }
}

/** 新增/更新一条替换规则 (按 id 覆盖, 新规则 order 传 Int.MIN_VALUE 由服务端排序) */
const saveReplaceRule = async (rule: ReplaceRuleItem): Promise<void> => {
  const { data } = await ajax.post<LeagdoApiResponse<unknown>>('saveReplaceRule', rule)
  if (!data.isSuccess) throw new Error(data.errorMsg || '保存替换规则失败')
}

/** 删除一条替换规则 (按 id) */
const deleteReplaceRule = async (rule: ReplaceRuleItem): Promise<void> => {
  const { data } = await ajax.post<LeagdoApiResponse<unknown>>('deleteReplaceRule', rule)
  if (!data.isSuccess) throw new Error(data.errorMsg || '删除替换规则失败')
}

/** 用给定规则试跑一段文本, 返回替换后的内容 */
const testReplaceRule = async (rule: ReplaceRuleItem, text: string): Promise<string> => {
  const { data } = await ajax.post<LeagdoApiResponse<string>>('testReplaceRule', { rule, text })
  if (!data.isSuccess) throw new Error(data.errorMsg || '测试替换规则失败')
  return data.data
}

// ---- WebDAV 云端备份 / 恢复 ----

export interface WebDavConfig {
  url: string
  account: string
  dir: string
  deviceName: string
  hasPassword: boolean
  isOk: boolean
}

/** data 为 JSON 字符串的信封解包 (服务端 WebDAV 接口统一返回 JSON 文本) */
const parseJsonEnvelope = <T>(data: LeagdoApiResponse<string>, fallbackMsg: string): T => {
  if (!data.isSuccess) throw new Error(data.errorMsg || fallbackMsg)
  try {
    return JSON.parse(data.data || '{}') as T
  } catch {
    throw new Error(fallbackMsg)
  }
}

/** 读取 WebDAV 配置 (密码不回传, 只给 hasPassword) */
const getWebDavConfig = async (): Promise<WebDavConfig> => {
  const { data } = await ajax.get<LeagdoApiResponse<string>>('webDavConfig')
  return parseJsonEnvelope<WebDavConfig>(data, '获取 WebDAV 配置失败')
}

/** 保存并测试 WebDAV 配置 (password 留空 = 保留原密码; clearPassword=true 清空) */
const saveWebDavConfig = async (cfg: {
  url?: string
  account?: string
  password?: string
  dir?: string
  deviceName?: string
  clearPassword?: boolean
}): Promise<WebDavConfig> => {
  const { data } = await ajax.post<LeagdoApiResponse<string>>('webDavConfig', cfg)
  return parseJsonEnvelope<WebDavConfig>(data, '保存 WebDAV 配置失败')
}

/** 列出云端 backup* 备份文件名 (按名称倒序) */
const listWebDavBackups = async (): Promise<string[]> => {
  const { data } = await ajax.get<LeagdoApiResponse<string>>('webDavBackups')
  return parseJsonEnvelope<string[]>(data, '获取云端备份列表失败')
}

/** 生成备份并上传 WebDAV; force=true 时覆盖云端同名备份 */
const webDavBackup = async (force = false): Promise<string> => {
  const { data } = await ajax.post<LeagdoApiResponse<string>>('webDavBackup', { force })
  return parseJsonEnvelope<{ fileName: string }>(data, '上传备份失败').fileName
}

/** 从云端指定备份恢复 */
const webDavRestore = async (name: string): Promise<void> => {
  const { data } = await ajax.post<LeagdoApiResponse<boolean>>('webDavRestore', { name })
  if (!data.isSuccess) throw new Error(data.errorMsg || '恢复失败')
}

const getExploreKinds = (url: string) =>
  ajax.get<LeagdoApiResponse<WebExploreKind[]>>(`getExploreKinds?url=${encodeURIComponent(url)}`)

const getExploreBooks = (url: string, exploreUrl: string, page: number = 1) =>
  ajax.get<LeagdoApiResponse<SeachBook[]>>(
    `getExploreBooks?url=${encodeURIComponent(url)}&exploreUrl=${encodeURIComponent(exploreUrl)}&page=${page}`,
  )

export default {
  get legado_http_entry_point() {
    return legado_http_entry_point
  },
  getReadConfig,
  saveReadConfig,
  saveBookProgress,
  saveBookProgressWithBeacon,
  getGroups,
  getBookShelf,
  getChapterList,
  refreshToc,
  getBookContent,
  search,
  saveBook,
  deleteBook,

  getSources,
  getBookSourcesPart,
  getSource,
  getExploreKinds,
  getExploreBooks,
  saveSources,
  importBookSourcesFromUrl,
  getBackupZip,
  restoreBackup,
  addLocalBook,
  getWebDavConfig,
  saveWebDavConfig,
  listWebDavBackups,
  webDavBackup,
  webDavRestore,
  getReplaceRules,
  saveReplaceRule,
  deleteReplaceRule,
  testReplaceRule,
  saveSource,
  deleteSource,
  debug,

  toAbsoluteUrl,
  getProxyCoverUrl,
  getProxyImageUrl,
  getMediaStreamUrl,
}

export {
  toAbsoluteUrl,
  getSources,
  getBookSourcesPart,
  refreshToc,
  getSource,
  getExploreKinds,
  getExploreBooks,
  importBookSourcesFromUrl,
  getProxyCoverUrl,
  getProxyImageUrl,
  getWebDavConfig,
  saveWebDavConfig,
  listWebDavBackups,
  webDavBackup,
  webDavRestore,
  getReplaceRules,
  saveReplaceRule,
  deleteReplaceRule,
  testReplaceRule,
}
