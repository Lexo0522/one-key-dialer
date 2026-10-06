// Promise 风格的模态对话框（挂载在 #app 下的单例宿主上）。
//
// 按钮文字一律走 i18n：这个组件是全应用唯一共用的对话框，btn 硬编码中文
// 会让英文界面出现中英混排。i18n.js 里的 common.* 就是为此准备的。
import { createApp, h, nextTick, ref } from 'vue'
import { t } from './i18n.js'

const hostEl = document.createElement('div')
document.body.appendChild(hostEl)

const queue = ref([]) // { type, title, message, withCancel, resolve }

// 打开期间记住打开前的焦点，关闭后还回去；否则键盘用户被弹窗"甩"回页面顶部。
let lastFocus = null

function onClose() {
  if (lastFocus && typeof lastFocus.focus === 'function') {
    lastFocus.focus()
  }
  lastFocus = null
  document.removeEventListener('keydown', onKeydown, true)
}

// Esc = 取消。语义与点「取消」一致：resolve(null)。
// capture: true 保证即使弹窗内控件自己处理了按键也能先收到。
function onKeydown(e) {
  if (e.key !== 'Escape') return
  e.stopPropagation()
  if (queue.value.length > 0) {
    close(queue.value[queue.value.length - 1], null)
  }
}

function open(entry) {
  lastFocus = document.activeElement
  document.addEventListener('keydown', onKeydown, true)
  queue.value.push(entry)
  // 打开后把焦点交给主按钮，键盘用户可以直接回车确认。
  nextTick(() => {
    const btn = hostEl.querySelector('.dialog-actions .btn-primary')
    if (btn) btn.focus()
  })
}

function close(entry, value) {
  const idx = queue.value.indexOf(entry)
  if (idx >= 0) queue.value.splice(idx, 1)
  if (queue.value.length === 0) onClose()
  entry.resolve(value)
}

// 按钮文字集中一处，避免同一个词在两处写两份翻译。
const BTN = {
  cancel: () => t('common.cancel'),
  ok: () => t('common.ok'),
  no: () => t('dialog.no'),
  yes: () => t('dialog.yes')
}

// ------------------------------------------------------------ 确认框 ----

const ConfirmView = {
  props: ['data'],
  setup(props) {
    return () =>
      h('div', { class: 'mask' }, [
        h(
          'div',
          {
            class: 'dialog',
            role: 'dialog',
            'aria-modal': 'true',
            'aria-label': props.data.title
          },
          [
            h('div', { class: 'dialog-title' }, props.data.title),
            h('div', { class: 'dialog-body' }, props.data.message),
            h('div', { class: 'dialog-actions' }, [
              props.data.withCancel
                ? h('button', { class: 'btn', onClick: () => close(props.data, null) }, BTN.cancel())
                : null,
              h('button', { class: 'btn', onClick: () => close(props.data, false) }, BTN.no()),
              h('button', { class: 'btn btn-primary', onClick: () => close(props.data, true) }, BTN.yes())
            ])
          ]
        )
      ])
  }
}

const ModalHost = {
  setup() {
    return () =>
      queue.value.map((d, i) => {
        if (d.type === 'confirm') return h(ConfirmView, { key: i, data: d })
        return null
      })
  }
}

createApp(ModalHost).mount(hostEl)

/** 是/否确认；withCancel=true 时多一个「取消」（返回 null）。 */
export function confirmDialog(title, message, withCancel = false) {
  return new Promise((resolve) => {
    open({ type: 'confirm', title, message, withCancel, resolve })
  })
}
