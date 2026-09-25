<template>
  <div :class="{ 'rule-page': true, night: isNight, day: !isNight }">
    <header class="page-topbar">
      <button class="topbar-btn back-btn" type="button" aria-label="返回" @click="goBack">
        <svg viewBox="0 0 24 24" fill="currentColor">
          <path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z" />
        </svg>
      </button>
      <span class="topbar-title">替换净化</span>
      <button class="topbar-btn add-btn" type="button" aria-label="新建" @click="openEditor()">
        <svg viewBox="0 0 24 24" fill="currentColor">
          <path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" />
        </svg>
      </button>
    </header>

    <div class="rule-scroll">
      <!-- 测试区 -->
      <div class="card">
        <div class="card-title">测试替换</div>
        <textarea
          v-model="testText"
          class="rule-textarea"
          rows="4"
          placeholder="粘贴一段正文，选择下方规则后点「测试」查看效果"
        ></textarea>
        <div class="row-actions">
          <button class="rule-btn" type="button" :disabled="busy || !testRule" @click="runTest">
            测试所选规则
          </button>
        </div>
        <div v-if="testResult !== null" class="test-result">
          <div class="test-result-label">结果</div>
          <pre class="test-result-body">{{ testResult }}</pre>
        </div>
      </div>

      <!-- 规则列表 -->
      <div class="card">
        <div class="card-title">
          规则列表 ({{ rules.length }})
          <span v-if="loading" class="loading-hint">加载中…</span>
        </div>

        <div v-if="!loading && rules.length === 0" class="empty-hint">
          暂无替换规则，点右上角「+」新建
        </div>

        <div
          v-for="rule in rules"
          :key="rule.id"
          class="rule-item"
          :class="{ selected: testRule?.id === rule.id, disabled: rule.isEnabled === false }"
          @click="selectRule(rule)"
        >
          <div class="rule-main">
            <div class="rule-head">
              <span class="rule-name">{{ rule.name || '(未命名)' }}</span>
              <span v-if="rule.group" class="rule-group">{{ rule.group }}</span>
              <span v-if="rule.isRegex === false" class="rule-tag">纯文本</span>
              <span v-else class="rule-tag regex">正则</span>
              <span v-if="rule.scopeContent !== false" class="rule-tag">正文</span>
              <span v-if="rule.scopeTitle" class="rule-tag">标题</span>
              <span v-if="rule.isEnabled === false" class="rule-tag off">已禁用</span>
            </div>
            <div class="rule-pattern">
              <code>{{ rule.pattern }}</code>
              <span class="arrow">→</span>
              <code>{{ rule.replacement }}</code>
            </div>
          </div>
          <div class="rule-ops">
            <button class="mini-btn" type="button" @click.stop="toggleEnabled(rule)">
              {{ rule.isEnabled === false ? '启用' : '禁用' }}
            </button>
            <button class="mini-btn" type="button" @click.stop="openEditor(rule)">编辑</button>
            <button class="mini-btn danger" type="button" @click.stop="removeRule(rule)">删除</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 编辑弹窗 -->
    <div v-if="editorOpen" class="modal-mask" @click="editorOpen = false">
      <div class="modal" @click.stop>
        <div class="modal-header">
          <span>{{ editing.id ? '编辑规则' : '新建规则' }}</span>
          <button class="modal-close" type="button" @click="editorOpen = false">✕</button>
        </div>
        <div class="modal-body">
          <label class="field">
            <span>名称</span>
            <input v-model="editing.name" class="rule-input" type="text" placeholder="规则名称" />
          </label>
          <label class="field">
            <span>分组</span>
            <input v-model="editing.group" class="rule-input" type="text" placeholder="可选" />
          </label>
          <label class="field">
            <span>查找内容</span>
            <textarea
              v-model="editing.pattern"
              class="rule-textarea"
              rows="3"
              placeholder="正则表达式或纯文本"
            ></textarea>
          </label>
          <label class="field">
            <span>替换为</span>
            <textarea
              v-model="editing.replacement"
              class="rule-textarea"
              rows="2"
              placeholder="留空表示删除匹配内容"
            ></textarea>
          </label>
          <div class="field switches">
            <label class="check"><input v-model="editing.isRegex" type="checkbox" /> 正则</label>
            <label class="check"><input v-model="editing.scopeContent" type="checkbox" /> 作用于正文</label>
            <label class="check"><input v-model="editing.scopeTitle" type="checkbox" /> 作用于标题</label>
            <label class="check"><input v-model="editing.isEnabled" type="checkbox" /> 启用</label>
          </div>
        </div>
        <div class="modal-footer">
          <button class="rule-btn" type="button" @click="editorOpen = false">取消</button>
          <button class="rule-btn primary" type="button" :disabled="busy" @click="saveEditor">
            保存
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'ReplaceRuleManage' })

import { useBookStore } from '@/store'
import API from '@api'
import type { ReplaceRuleItem } from '@api'
import { toast, msgbox } from '@/utils/toast'

const router = useRouter()
const store = useBookStore()
const isNight = computed(() => store.isNight)

const rules = ref<ReplaceRuleItem[]>([])
const loading = ref(false)
const busy = ref(false)
const testText = ref('')
const testRule = ref<ReplaceRuleItem | null>(null)
const testResult = ref<string | null>(null)
const editorOpen = ref(false)

const emptyRule = (): ReplaceRuleItem => ({
  id: 0,
  name: '',
  group: '',
  pattern: '',
  replacement: '',
  scope: '',
  scopeTitle: false,
  scopeContent: true,
  excludeScope: '',
  isEnabled: true,
  isRegex: true,
  timeoutMillisecond: 3000,
  order: -2147483648,
})

const editing = ref<ReplaceRuleItem>(emptyRule())

const goBack = () => {
  if (window.history.length > 1) router.back()
  else router.push('/my')
}

const loadRules = async () => {
  loading.value = true
  try {
    rules.value = await API.getReplaceRules()
  } catch (e) {
    toast.error((e as Error)?.message || '加载替换规则失败')
  } finally {
    loading.value = false
  }
}

const selectRule = (rule: ReplaceRuleItem) => {
  testRule.value = testRule.value?.id === rule.id ? null : rule
  testResult.value = null
}

const runTest = async () => {
  if (!testRule.value) return
  busy.value = true
  try {
    testResult.value = await API.testReplaceRule(testRule.value, testText.value)
  } catch (e) {
    toast.error((e as Error)?.message || '测试失败')
  } finally {
    busy.value = false
  }
}

const openEditor = (rule?: ReplaceRuleItem) => {
  editing.value = rule ? { ...rule } : emptyRule()
  editorOpen.value = true
}

const saveEditor = async () => {
  if (!editing.value.pattern) {
    toast.warning('查找内容不能为空')
    return
  }
  busy.value = true
  try {
    await API.saveReplaceRule({ ...editing.value })
    editorOpen.value = false
    toast.success('已保存')
    await loadRules()
  } catch (e) {
    toast.error((e as Error)?.message || '保存失败')
  } finally {
    busy.value = false
  }
}

const toggleEnabled = async (rule: ReplaceRuleItem) => {
  busy.value = true
  try {
    await API.saveReplaceRule({ ...rule, isEnabled: rule.isEnabled === false })
    await loadRules()
  } catch (e) {
    toast.error((e as Error)?.message || '操作失败')
  } finally {
    busy.value = false
  }
}

const removeRule = async (rule: ReplaceRuleItem) => {
  try {
    await msgbox.confirm(`确定删除规则「${rule.name || '(未命名)'}」？`, '删除替换规则')
  } catch {
    return
  }
  busy.value = true
  try {
    await API.deleteReplaceRule(rule)
    if (testRule.value?.id === rule.id) testRule.value = null
    toast.success('已删除')
    await loadRules()
  } catch (e) {
    toast.error((e as Error)?.message || '删除失败')
  } finally {
    busy.value = false
  }
}

onMounted(loadRules)
</script>

<style lang="scss" scoped>
.rule-page {
  height: 100vh;
  width: 100vw;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background-color: #f7f7f7;
  color: var(--web-text, #333);

  .page-topbar {
    flex: none;
    height: 52px;
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 0 12px;
    background: #fff;
    border-bottom: 1px solid rgba(128, 128, 128, 0.15);

    .topbar-title {
      flex: 1;
      font-size: 17px;
      font-weight: 600;
    }

    .topbar-btn {
      width: 34px;
      height: 34px;
      display: flex;
      align-items: center;
      justify-content: center;
      border: none;
      background: transparent;
      color: #666;
      cursor: pointer;
      padding: 0;

      svg {
        width: 22px;
        height: 22px;
      }

      &.add-btn {
        color: var(--web-primary, #1e80ff);
      }
    }
  }

  .rule-scroll {
    flex: 1;
    overflow-y: auto;
    padding: 12px 14px 40px;
  }

  .card {
    margin-bottom: 14px;
    border-radius: 8px;
    background: #fff;
    padding: 12px 14px;

    .card-title {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 14px;
      font-weight: 600;
      color: #888;
      margin-bottom: 8px;

      .loading-hint {
        font-size: 12px;
        color: #aaa;
        font-weight: 400;
      }
    }
  }

  .rule-input,
  .rule-textarea {
    width: 100%;
    box-sizing: border-box;
    border: 1px solid var(--web-border, #dcdfe6);
    border-radius: 6px;
    padding: 7px 10px;
    font-size: 13px;
    color: var(--web-text, #333);
    background: #fff;
    outline: none;
    font-family: inherit;

    &:focus {
      border-color: var(--web-primary, #1e80ff);
    }
  }

  .rule-textarea {
    resize: vertical;
    line-height: 1.45;
  }

  .row-actions {
    display: flex;
    gap: 10px;
    margin-top: 8px;
  }

  .rule-btn {
    flex: 1;
    border: 1px solid var(--web-border, #dcdfe6);
    border-radius: 6px;
    background: #fff;
    padding: 8px 0;
    font-size: 13px;
    color: var(--web-text, #333);
    cursor: pointer;

    &.primary {
      background: var(--web-primary, #1e80ff);
      border-color: var(--web-primary, #1e80ff);
      color: #fff;
    }

    &:disabled {
      opacity: 0.6;
      cursor: not-allowed;
    }
  }

  .test-result {
    margin-top: 10px;
    border-top: 1px dashed #e5e7eb;
    padding-top: 8px;

    .test-result-label {
      font-size: 12px;
      color: #999;
      margin-bottom: 4px;
    }

    .test-result-body {
      margin: 0;
      max-height: 200px;
      overflow: auto;
      font-size: 12px;
      line-height: 1.5;
      white-space: pre-wrap;
      word-break: break-all;
      background: #f8fafc;
      border-radius: 6px;
      padding: 8px 10px;
    }
  }

  .empty-hint {
    font-size: 13px;
    color: #999;
    padding: 12px 0;
    text-align: center;
  }

  .rule-item {
    display: flex;
    align-items: flex-start;
    gap: 10px;
    padding: 10px 8px;
    border-bottom: 1px solid #f5f5f5;
    border-radius: 6px;
    cursor: pointer;

    &:last-child {
      border-bottom: none;
    }

    &.selected {
      background: rgba(30, 128, 255, 0.07);
    }

    &.disabled {
      opacity: 0.55;
    }

    .rule-main {
      flex: 1;
      min-width: 0;

      .rule-head {
        display: flex;
        align-items: center;
        flex-wrap: wrap;
        gap: 6px;
        font-size: 13px;

        .rule-name {
          font-weight: 600;
        }

        .rule-group {
          font-size: 11px;
          background: #f1f5f9;
          color: #64748b;
          padding: 1px 6px;
          border-radius: 3px;
        }

        .rule-tag {
          font-size: 11px;
          background: #eef2ff;
          color: #4f46e5;
          padding: 1px 6px;
          border-radius: 3px;

          &.regex {
            background: #ecfdf5;
            color: #059669;
          }

          &.off {
            background: #fef2f2;
            color: #dc2626;
          }
        }
      }

      .rule-pattern {
        display: flex;
        align-items: center;
        gap: 6px;
        margin-top: 4px;
        font-size: 12px;
        color: #666;
        overflow: hidden;

        code {
          background: #f6f8fa;
          padding: 1px 5px;
          border-radius: 3px;
          max-width: 45%;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }

        .arrow {
          color: #bbb;
        }
      }
    }

    .rule-ops {
      display: flex;
      flex-direction: column;
      gap: 4px;
      flex-shrink: 0;

      .mini-btn {
        border: 1px solid var(--web-border, #dcdfe6);
        background: #fff;
        border-radius: 4px;
        font-size: 11px;
        padding: 2px 8px;
        cursor: pointer;
        color: #555;
        white-space: nowrap;

        &.danger {
          color: #dc2626;
          border-color: #fecaca;
        }
      }
    }
  }

  .modal-mask {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.45);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 1000;
    padding: 16px;

    .modal {
      width: 100%;
      max-width: 460px;
      max-height: 86vh;
      background: #fff;
      border-radius: 12px;
      display: flex;
      flex-direction: column;
      overflow: hidden;
      box-shadow: 0 10px 25px rgba(0, 0, 0, 0.15);

      .modal-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 12px 16px;
        border-bottom: 1px solid #f0f0f0;
        font-size: 15px;
        font-weight: 600;

        .modal-close {
          border: none;
          background: transparent;
          font-size: 16px;
          color: #94a3b8;
          cursor: pointer;
        }
      }

      .modal-body {
        flex: 1;
        overflow-y: auto;
        padding: 12px 16px;

        .field {
          display: flex;
          flex-direction: column;
          gap: 5px;
          margin-bottom: 12px;

          > span {
            font-size: 12px;
            color: #888;
          }

          &.switches {
            flex-direction: row;
            flex-wrap: wrap;
            gap: 12px;
          }

          .check {
            display: inline-flex;
            align-items: center;
            gap: 4px;
            font-size: 13px;
            color: #444;
          }
        }
      }

      .modal-footer {
        display: flex;
        gap: 10px;
        padding: 12px 16px;
        border-top: 1px solid #f0f0f0;
      }
    }
  }
}

.night {
  background-color: #161819;
  color: #aeaeae;

  .page-topbar {
    background: #242526;
    border-bottom-color: rgba(255, 255, 255, 0.08);

    .topbar-btn {
      color: #aaa;
    }
  }

  .card {
    background: #242526;

    .card-title {
      color: #999;
    }
  }

  .rule-input,
  .rule-textarea {
    background: #18191a;
    border-color: #3a3b3d;
    color: #eee;
  }

  .test-result .test-result-body {
    background: #18191a;
    color: #ddd;
  }

  .rule-btn {
    background: #2b2c2e;
    border-color: #3a3b3d;
    color: #ccc;

    &.primary {
      background: var(--web-primary, #1e80ff);
      border-color: var(--web-primary, #1e80ff);
      color: #fff;
    }
  }

  .rule-item {
    border-bottom-color: #333;

    &.selected {
      background: rgba(96, 165, 250, 0.12);
    }

    .rule-main {
      .rule-head .rule-group {
        background: #333;
        color: #aaa;
      }

      .rule-pattern code {
        background: #18191a;
        color: #bbb;
      }
    }

    .rule-ops .mini-btn {
      background: #2b2c2e;
      border-color: #3a3b3d;
      color: #ccc;

      &.danger {
        color: #f87171;
        border-color: #5b2b2b;
      }
    }
  }

  .modal-mask .modal {
    background: #242526;
    color: #ddd;

    .modal-header {
      border-bottom-color: #333;
    }

    .modal-body .field > span {
      color: #999;
    }

    .modal-body .field .check {
      color: #ccc;
    }

    .modal-footer {
      border-top-color: #333;
    }
  }
}
</style>
