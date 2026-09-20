<template>
  <div class="page accounts">
    <div class="page-head">
      <div class="page-title">
        <i class="fas fa-address-card"></i>{{ t('account.managerTitle') }}
        <span class="count-chip">{{ rows.length }}</span>
      </div>
      <div class="toolbar">
        <button class="btn icon-btn" :class="{ primary: mode === 'add' }" :disabled="!!mode" @click="onAdd">
          <i class="fas fa-plus"></i>{{ t('account.add') }}
        </button>
        <button class="btn icon-btn" :disabled="!!mode || sel < 0" @click="onEdit">
          <i class="fas fa-pen"></i>{{ t('account.edit') }}
        </button>
        <button class="btn icon-btn" :disabled="!!mode || sel < 0" @click="onDelete">
          <i class="fas fa-trash-alt"></i>{{ t('account.delete') }}
        </button>
        <span class="sep"></span>
        <button class="btn icon-btn" :disabled="!!mode || sel <= 0" @click="onUp" :title="t('account.up')">
          <i class="fas fa-arrow-up"></i>
        </button>
        <button class="btn icon-btn" :disabled="!!mode || sel < 0 || sel >= rows.length - 1" @click="onDown" :title="t('account.down')">
          <i class="fas fa-arrow-down"></i>
        </button>
        <span class="sep"></span>
        <button class="btn icon-btn" :disabled="!!mode" @click="onImport" :title="t('account.import')">
          <i class="fas fa-file-import"></i>
        </button>
        <button class="btn icon-btn" :disabled="!!mode" @click="onExport" :title="t('account.export')">
          <i class="fas fa-file-export"></i>
        </button>
      </div>
    </div>

    <!-- 账号表格 -->
    <div class="card table-card">
      <div class="table-box">
        <table class="grid">
          <thead>
          <tr>
            <th style="width:44px">{{ t('account.col.id') }}</th>
            <th style="width:150px">{{ t('account.col.nickname') }}</th>
            <th style="width:170px">{{ t('account.col.user') }}</th>
            <th style="width:100px">{{ t('account.col.pass') }}</th>
            <th>{{ t('account.col.remark') }}</th>
          </tr>
          </thead>
          <tbody>
          <tr v-if="!rows.length">
            <td colspan="5" class="empty">{{ t('account.empty') }}</td>
          </tr>
          <tr v-for="(a, i) in rows" :key="i"
              :class="{ selected: sel === i, editing: mode && mode.index === i }"
              @click="sel = i">
            <td>{{ i + 1 }}</td>
            <td :title="a.name">
              {{ a.name }}
              <span v-if="i === state.currentIndex" class="using-chip">{{ t('home.status.connected') }}</span>
            </td>
            <td :title="a.username">{{ a.username }}</td>
            <td :class="{ 'pw-empty': !a.hasPassword }">{{ a.hasPassword ? t('account.masked') : t('account.pwUnset') }}</td>
            <td :title="a.remark">{{ a.remark }}</td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 行内编辑表单（替代旧弹窗） -->
    <div v-if="mode" class="card form-card">
      <div class="form-title">
        <i :class="mode.kind === 'add' ? 'fas fa-plus-circle' : 'fas fa-edit'"></i>
        {{ mode.kind === 'add' ? t('account.formAdd') : t('account.formEdit') }}
      </div>
      <div class="form-grid">
        <label class="field-label">{{ t('account.nickname') }}</label>
        <input v-model="form.name" type="text" @keyup.enter="submitForm"/>
        <label class="field-label">{{ t('account.user') }}</label>
        <input v-model="form.username" type="text" @keyup.enter="submitForm"/>
        <label class="field-label">{{ t('account.pass') }}</label>
        <input v-model="form.password" type="password" :placeholder="pwPlaceholder" @keyup.enter="submitForm"/>
        <template v-if="canClearPw">
          <span></span>
          <label class="clear-pw">
            <input type="checkbox" v-model="clearPassword"/>
            <span>{{ t('account.clearPassword') }}</span>
          </label>
        </template>
        <label class="field-label">{{ t('account.remark') }}</label>
        <input v-model="form.remark" type="text" @keyup.enter="submitForm"/>
      </div>
      <div class="hint">{{ t('account.nameHint') }}</div>
      <div class="form-actions">
        <button class="btn" @click="cancelForm">{{ t('account.cancel') }}</button>
        <button class="btn btn-primary" @click="submitForm">
          <i class="fas fa-check"></i>{{ t('account.save') }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { state, showToast } from '../store'
import { api } from '../bridge'
import { t } from '../i18n'
import { confirmDialog, pickDialog } from '../dialog'

const rows = ref([])
const sel = ref(0)
// mode: null | { kind: 'add' } | { kind: 'edit', index }
const mode = ref(null)
const form = ref({ name: '', username: '', password: '', remark: '' })
// 编辑表单附加状态：是否显式清除已保存密码；打开表单时的原始账号名
const clearPassword = ref(false)
const editFromUsername = ref('')

// 打开时以「含密码」视图为准：编辑后密码字段随行保留，保存时整体回传。
watch(
  () => state.accounts,
  (list) => {
    rows.value = (list || []).map((a) => ({ ...a }))
    if (sel.value >= rows.value.length) sel.value = rows.value.length - 1
    if (sel.value < 0 && rows.value.length) sel.value = 0
  },
  { immediate: true }
)

const pwPlaceholder = computed(() => {
  const cur = mode.value && mode.value.kind === 'edit' ? rows.value[mode.value.index] : null
  return cur && cur.hasPassword ? t('account.pwKeepHint') : ''
})

// 仅编辑已有密码的账号时展示"清除已保存密码"
const canClearPw = computed(() => {
  if (!mode.value || mode.value.kind !== 'edit') return false
  const cur = rows.value[mode.value.index]
  return !!cur && !!cur.hasPassword
})

async function persist() {
  await api.SaveAccounts(rows.value.map((r) => ({
    name: r.name || '',
    username: r.username || '',
    password: r.password || '',
    remark: r.remark || '',
    hasPassword: !!r.hasPassword
  })))
  // 回传后清掉明文副本，避免常驻内存
  rows.value.forEach((r) => {
    r.password = ''
  })
}

// ------------------------------------------------------------ 行内表单 ----

function onAdd() {
  mode.value = { kind: 'add' }
  clearPassword.value = false
  editFromUsername.value = ''
  form.value = { name: '', username: '', password: '', remark: '' }
}

function onEdit() {
  if (sel.value < 0) {
    showToast(t('account.managerTitle'), t('account.selectFirst'), 'error')
    return
  }
  const cur = rows.value[sel.value]
  mode.value = { kind: 'edit', index: sel.value }
  clearPassword.value = false
  editFromUsername.value = (cur.username || '').trim()
  form.value = {
    name: cur.name || '',
    username: cur.username || '',
    password: '',
    remark: cur.remark || ''
  }
}

function cancelForm() {
  mode.value = null
}

async function submitForm() {
  const name = (form.value.name || '').trim() || (form.value.username || '').trim() || '未设置'
  if (mode.value.kind === 'add') {
    rows.value.push({
      name,
      username: (form.value.username || '').trim(),
      password: form.value.password || '',
      remark: (form.value.remark || '').trim(),
      hasPassword: !!form.value.password
    })
    sel.value = rows.value.length - 1
  } else {
    const cur = rows.value[mode.value.index]
    const newUsername = (form.value.username || '').trim()
    // 账号名被修改且没有重新输入密码时，后端无法按账号名匹配到旧密码，
    // 沿用会失败：提前告知用户，避免 silently 丢密。
    if (newUsername !== editFromUsername.value && !form.value.password &&
        cur.hasPassword && !clearPassword.value) {
      const ok = await confirmDialog(t('account.userChangedTitle'), t('account.userChangedMsg'))
      if (!ok) return
    }
    cur.name = name
    cur.username = newUsername
    cur.remark = (form.value.remark || '').trim()
    if (form.value.password) {
      cur.password = form.value.password
      cur.hasPassword = true
    } else if (clearPassword.value) {
      cur.password = ''
      cur.hasPassword = false
    }
    // 留空且未勾选清除：hasPassword 保持为 true，后端按账号名继承旧密码
  }
  mode.value = null
  await persist()
}

// ------------------------------------------------------------ 列表操作 ----

async function onDelete() {
  if (sel.value < 0) {
    showToast(t('account.managerTitle'), t('account.selectFirst'), 'error')
    return
  }
  if (sel.value === state.currentIndex) {
    showToast(t('account.managerTitle'), t('account.inUse'), 'error')
    return
  }
  if (rows.value.length <= 1) {
    showToast(t('account.managerTitle'), t('account.keepOne'), 'error')
    return
  }
  const ok = await confirmDialog(t('common.confirm'), t('account.confirmDelete'))
  if (!ok) return
  rows.value.splice(sel.value, 1)
  if (sel.value >= rows.value.length) sel.value = rows.value.length - 1
  await persist()
}

async function onUp() {
  if (sel.value <= 0) return
  const a = rows.value[sel.value]
  rows.value[sel.value] = rows.value[sel.value - 1]
  rows.value[sel.value - 1] = a
  sel.value -= 1
  await persist()
}

async function onDown() {
  if (sel.value < 0 || sel.value >= rows.value.length - 1) return
  const a = rows.value[sel.value]
  rows.value[sel.value] = rows.value[sel.value + 1]
  rows.value[sel.value + 1] = a
  sel.value += 1
  await persist()
}

async function onExport() {
  const modeIdx = await pickDialog(t('account.exportTitle'), t('account.exportMsg'), [
    t('account.exportSafe'),
    t('account.exportWithPass'),
    t('common.cancel')
  ])
  if (modeIdx !== 0 && modeIdx !== 1) return
  const withPassword = modeIdx === 1
  if (withPassword) {
    const ok = await confirmDialog(t('account.warnTitle'), t('account.warnMsg'))
    if (!ok) return
  }
  const path = await api.ExportAccounts(withPassword)
  if (!path) showToast(t('account.exportTitle'), t('account.exportFail'), 'error')
}

async function onImport() {
  const n = await api.ImportAccounts()
  if (!n) showToast(t('account.import'), t('account.importFail'), 'error')
}
</script>

<style scoped>
.accounts {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  flex: 0 0 auto;
}

.page-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 800;
}

.page-title i {
  color: var(--c-info);
}

.count-chip {
  font-size: 11px;
  font-weight: 700;
  color: var(--c-info);
  background: var(--c-accent-soft);
  border-radius: 999px;
  padding: 1px 9px;
}

.toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.toolbar .btn.primary {
  border-color: var(--c-info);
  color: var(--c-info);
}

.toolbar .sep {
  width: 8px;
}

.table-card {
  flex: 1 1 auto;
  min-height: 120px;
  display: flex;
  padding: 0;
  overflow: hidden;
}

.table-box {
  flex: 1;
  overflow: auto;
  border-radius: var(--radius-lg);
}

.empty {
  text-align: center;
  color: var(--c-hint);
  padding: 22px;
}

.pw-empty {
  color: var(--c-hint);
  font-size: 12px;
}

.using-chip {
  font-size: 10px;
  color: var(--c-status-online);
  border: 1px solid color-mix(in srgb, var(--c-status-online) 45%, transparent);
  border-radius: 999px;
  padding: 0 7px;
  margin-left: 6px;
}

tr.editing td {
  background: var(--c-table-sel);
}

/* 行内编辑表单 */
.form-card {
  flex: 0 0 auto;
  animation: slide-up .18s var(--ease);
}

@keyframes slide-up {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: none; }
}

.form-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-weight: 700;
  margin-bottom: 10px;
}

.form-title i {
  color: var(--c-info);
}

.form-grid {
  display: grid;
  grid-template-columns: 92px 1fr;
  align-items: center;
  gap: 8px 12px;
}

.form-grid input {
  width: 100%;
  max-width: 380px;
}

.clear-pw {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: var(--c-hint);
  cursor: pointer;
  user-select: none;
}

.clear-pw input {
  width: auto;
  accent-color: var(--c-danger, #ef4444);
}

.form-card .hint {
  margin-top: 8px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}
</style>
