<template>
  <div class="login-wrapper">
    <div class="login-box">
      <div class="login-title">Legado Web</div>
      <div class="login-desc">该服务已启用访问密码，请输入账号密码</div>

      <div class="login-field">
        <label>账号</label>
        <input
          v-model="user"
          class="login-input"
          type="text"
          autocomplete="username"
          placeholder="账号"
          @keyup.enter="submit"
        />
      </div>

      <div class="login-field">
        <label>密码</label>
        <input
          v-model="password"
          class="login-input"
          type="password"
          autocomplete="current-password"
          placeholder="密码"
          @keyup.enter="submit"
        />
      </div>

      <button class="login-btn" type="button" :disabled="busy" @click="submit">
        {{ busy ? '登录中…' : '登录' }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'Login' })

import { login } from '@/api/auth'
import { toast } from '@/utils/toast'

const router = useRouter()
const route = useRoute()
const user = ref('legado')
const password = ref('')
const busy = ref(false)

const submit = async () => {
  if (busy.value) return
  if (!password.value) {
    toast.warning('请输入密码')
    return
  }
  busy.value = true
  try {
    await login(user.value.trim() || 'legado', password.value)
    toast.success('登录成功')
    const redirect = (route.query.redirect as string) || '/shelf'
    await router.replace(redirect)
  } catch (e) {
    toast.error((e as Error)?.message || '登录失败')
  } finally {
    busy.value = false
  }
}
</script>

<style lang="scss" scoped>
.login-wrapper {
  min-height: 100%;
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #f7f7f7;
  padding: 24px;

  .login-box {
    width: 100%;
    max-width: 340px;
    background: #fff;
    border-radius: 10px;
    padding: 26px 22px 22px;
    box-shadow: 0 4px 18px rgba(0, 0, 0, 0.08);

    .login-title {
      font-size: 20px;
      font-weight: 600;
      color: var(--web-text, #333);
      text-align: center;
    }

    .login-desc {
      font-size: 12px;
      color: #999;
      text-align: center;
      margin: 6px 0 18px;
    }

    .login-field {
      display: flex;
      flex-direction: column;
      gap: 6px;
      margin-bottom: 14px;

      label {
        font-size: 13px;
        color: #888;
      }

      .login-input {
        height: 38px;
        border: 1px solid var(--web-border, #dcdfe6);
        border-radius: 6px;
        padding: 0 10px;
        font-size: 14px;
        color: var(--web-text, #333);
        background: #fff;
        outline: none;

        &:focus {
          border-color: var(--web-primary, #1e80ff);
        }
      }
    }

    .login-btn {
      width: 100%;
      height: 40px;
      margin-top: 6px;
      border: none;
      border-radius: 6px;
      background: var(--web-primary, #1e80ff);
      color: #fff;
      font-size: 15px;
      cursor: pointer;

      &:disabled {
        opacity: 0.6;
        cursor: not-allowed;
      }
    }
  }
}
</style>
