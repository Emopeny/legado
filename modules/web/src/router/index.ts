import { createWebHashHistory, createRouter } from 'vue-router'
import { bookRoutes } from './bookRouter'
import { sourceRoutes } from './sourceRouter'
import { ensureAuth } from '@/api/auth'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    // 直接进主界面书架 (用户裁决: 不要 Welcome 落地页)
    { path: '/', redirect: '/shelf' },
    // 服务端启用访问密码时, 未认证导航一律回登录页
    { path: '/login', name: 'login', component: () => import('../views/Login.vue') },
    // 替换净化规则管理 (Web 端入口)
    { path: '/replace-rules', name: 'replace-rules', component: () => import('../views/ReplaceRuleManage.vue') },
    ...bookRoutes,
    ...sourceRoutes,
  ].flat(),
})

const titleMap: Record<string, string> = {
  shelf: '书架',
  explore: '发现',
  my: '我的',
  search: '搜索',
  'source-manage': '书源管理',
  'book-home': '书源编辑',
  'explore-show': '发现',
  'book-info': '书籍详情',
  chapter: '阅读',
  login: '登录',
  'replace-rules': '替换净化',
}

router.afterEach(to => {
  const t = titleMap[(to.name as string) || '']
  if (t) document.title = t
})

/**
 * 鉴权守卫: 服务端未启用密码时 ensureAuth 恒真 (零影响); 启用后未认证回登录页,
 * 并把原目标路径带在 redirect 上, 登录成功后跳回。
 */
router.beforeEach(async to => {
  if (to.path === '/login') return true
  const ok = await ensureAuth()
  if (!ok) return { path: '/login', query: { redirect: to.fullPath } }
  return true
})

export default router
