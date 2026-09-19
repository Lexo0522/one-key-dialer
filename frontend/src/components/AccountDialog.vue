<template>
  <div class="mask" @click.self="close">
    <div class="dialog account-dialog">
      <div class="dialog-title">{{ t('account.managerTitle') }}</div>

      <div class="table-box">
        <table class="grid">
          <thead>
          <tr>
            <th style="width:40px">{{ t('account.col.id') }}</th>
            <th style="width:110px">{{ t('account.col.nickname') }}</th>
            <th style="width:140px">{{ t('account.col.user') }}</th>
            <th style="width:90px">{{ t('account.col.pass') }}</th>
            <th>{{ t('account.col.remark') }}</th>
          </tr>
          </thead>
          <tbody>
          <tr v-for="(a, i) in rows" :key="i"
              :class="{ selected: sel === i }"
              @click="sel = i">
            <td>{{ i + 1 }}</td>
            <td :title="a.name">{{ a.name }}</td>
            <td :title="a.username">{{ a.username }}</td>
            <td>{{ a.hasPassword ? t('account.masked') : '' }}</td>
            <td :title="a.remark">{{ a.remark }}</td>
          </tr>
          </tbody>
        </table>
      </div>

      <div class="dialog-actions center">
        <button class="btn" @click="onAdd">{{ t('account.add') }}</button>
        <button class="btn" @click="onEdit">{{ t('account.edit') }}</button>
        <button class="btn" @click="onDelete">{{ t('account.delete') }}</button>
        <span class="sep"></span>
        <button class="btn" :disabled="sel <= 0" @click="onUp">{{ t('account.up') }}</button>
        <button class="btn" :disabled="sel < 0 || sel >= rows.length - 1" @click="onDown">{{ t('account.down') }}</button>
        <span class="sep"></span>
        <button class="btn" @click="onExport">{{ t('account.export') }}</button>
        <button class="btn" @click="onImport">{{ t('account.import') }}</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { state, showToast } from '../store'
import { api } from '../bridge'
import { t } from '../i18n'
import { confirmDialog, pickDialog, formDialog } from '../dialog'

const emit = defineEmits(['close'])

const rows = ref([])
const sel = ref(0)

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

function close() {
  emit('close')
}

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

async function onAdd() {
  const v = await formDialog(
    t('account.formAdd'),
    [
      { key: 'name', label: t('account.nickname'), value: '', type: 'text' },
      { key: 'username', label: t('account.user'), value: '', type: 'text' },
      { key: 'password', label: t('account.pass'), value: '', type: 'password' },
      { key: 'remark', label: t('account.remark'), value: '', type: 'text' }
    ],
    t('account.nameHint')
  )
  if (!v) return
  const name = (v.name || '').trim() || (v.username || '').trim() || '未设置'
  rows.value.push({
    name,
    username: (v.username || '').trim(),
    password: v.password || '',
    remark: (v.remark || '').trim(),
    hasPassword: !!(v.password || '')
  })
  sel.value = rows.value.length - 1
  await persist()
}

async function onEdit() {
  if (sel.value < 0) {
    showToast(t('account.managerTitle'), t('account.selectFirst'), 'error')
    return
  }
  const cur = rows.value[sel.value]
  const v = await formDialog(
    t('account.formEdit'),
    [
      { key: 'name', label: t('account.nickname'), value: cur.name || '', type: 'text' },
      { key: 'username', label: t('account.user'), value: cur.username || '', type: 'text' },
      { key: 'password', label: t('account.pass'), value: '', type: 'password' },
      { key: 'remark', label: t('account.remark'), value: cur.remark || '', type: 'text' }
    ],
    t('account.nameHint')
  )
  if (!v) return
  cur.name = (v.name || '').trim() || (v.username || '').trim() || '未设置'
  cur.username = (v.username || '').trim()
  cur.remark = (v.remark || '').trim()
  if (v.password) {
    cur.password = v.password
    cur.hasPassword = true
  }
  await persist()
}

async function onDelete() {
  if (sel.value < 0) {
    showToast(t('account.managerTitle'), t('account.selectFirst'), 'error')
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
  const mode = await pickDialog(t('account.exportTitle'), t('account.exportMsg'), [
    t('account.exportSafe'),
    t('account.exportWithPass'),
    t('common.cancel')
  ])
  if (mode !== 0 && mode !== 1) return
  const withPassword = mode === 1
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
.account-dialog {
  min-width: 640px;
}

.table-box {
  max-height: 320px;
  overflow: auto;
  border: 1px solid var(--c-border-light);
  border-radius: 6px;
  background: var(--c-viewport);
}

.dialog-actions.center {
  justify-content: center;
  flex-wrap: wrap;
}

.sep {
  width: 10px;
}
</style>
