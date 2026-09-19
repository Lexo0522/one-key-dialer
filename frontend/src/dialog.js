// Promise 风格的模态对话框（挂载在 #app 下的单例宿主上）。
import { createApp, h, ref } from 'vue'

const hostEl = document.createElement('div')
document.body.appendChild(hostEl)

const queue = ref([]) // { type, title, message, options, resolve }

const ModalHost = {
  setup() {
    return () =>
      queue.value.map((d, i) => {
        if (d.type === 'confirm') return h(ConfirmView, { key: i, data: d })
        if (d.type === 'pick') return h(PickView, { key: i, data: d })
        if (d.type === 'form') return h(FormView, { key: i, data: d })
        return null
      })
  }
}

// ------------------------------------------------------------ 确认框 ----

const ConfirmView = {
  props: ['data'],
  setup(props) {
    const { title, message, withCancel } = props.data
    const close = (v) => {
      const idx = queue.value.indexOf(props.data)
      if (idx >= 0) queue.value.splice(idx, 1)
      props.data.resolve(v)
    }
    return () =>
      h('div', { class: 'mask' }, [
        h('div', { class: 'dialog' }, [
          h('div', { class: 'dialog-title' }, title),
          h('div', { class: 'dialog-body' }, message),
          h('div', { class: 'dialog-actions' }, [
            withCancel
              ? h('button', { class: 'btn', onClick: () => close(null) }, '取消')
              : null,
            h('button', { class: 'btn', onClick: () => close(false) }, '否'),
            h('button', { class: 'btn btn-primary', onClick: () => close(true) }, '是')
          ])
        ])
      ])
  }
}

// ------------------------------------------------------------ 选择框 ----

const PickView = {
  props: ['data'],
  setup(props) {
    const sel = ref(0)
    const close = (v) => {
      const idx = queue.value.indexOf(props.data)
      if (idx >= 0) queue.value.splice(idx, 1)
      props.data.resolve(v)
    }
    return () =>
      h('div', { class: 'mask' }, [
        h('div', { class: 'dialog' }, [
          h('div', { class: 'dialog-title' }, props.data.title),
          h('div', { class: 'dialog-body' }, props.data.message),
          h(
            'div',
            { class: 'pick-list' },
            props.data.options.map((o, i) =>
              h(
                'div',
                {
                  class: 'pick-item' + (sel.value === i ? ' sel' : ''),
                  onClick: () => {
                    sel.value = i
                  }
                },
                o
              )
            )
          ),
          h('div', { class: 'dialog-actions' }, [
            h('button', { class: 'btn', onClick: () => close(-1) }, '取消'),
            h('button', { class: 'btn btn-primary', onClick: () => close(sel.value) }, '确定')
          ])
        ])
      ])
  }
}

// ------------------------------------------------------------ 输入框 ----

const FormView = {
  props: ['data'],
  setup(props) {
    const draft = ref(props.data.fields.map((f) => ({ ...f })))
    const close = (v) => {
      const idx = queue.value.indexOf(props.data)
      if (idx >= 0) queue.value.splice(idx, 1)
      props.data.resolve(v)
    }
    const submit = () => {
      const out = {}
      for (const f of draft.value) out[f.key] = f.value || ''
      close(out)
    }
    return () =>
      h('div', { class: 'mask' }, [
        h('div', { class: 'dialog' }, [
          h('div', { class: 'dialog-title' }, props.data.title),
          h(
            'div',
            { class: 'form-grid' },
            draft.value.map((f, i) => [
              h('label', { class: 'field-label' }, f.label),
              f.type === 'password'
                ? h('input', {
                    type: 'password',
                    value: f.value,
                    onInput: (e) => {
                      draft.value[i].value = e.target.value
                    }
                  })
                : h('input', {
                    type: 'text',
                    value: f.value,
                    onInput: (e) => {
                      draft.value[i].value = e.target.value
                    }
                  })
            ])
          ),
          props.data.hint ? h('div', { class: 'hint' }, props.data.hint) : null,
          h('div', { class: 'dialog-actions' }, [
            h('button', { class: 'btn', onClick: () => close(null) }, '取消'),
            h('button', { class: 'btn btn-primary', onClick: submit }, '确定')
          ])
        ])
      ])
  }
}

createApp(ModalHost).mount(hostEl)

function push(type, payload) {
  return new Promise((resolve) => {
    queue.value.push({ type, ...payload, resolve })
  })
}

/** 是/否确认；withCancel=true 时多一个「取消」（返回 null）。 */
export function confirmDialog(title, message, withCancel = false) {
  return push('confirm', { title, message, withCancel })
}

/** 单选列表；返回选中下标，取消返回 -1。 */
export function pickDialog(title, message, options) {
  return push('pick', { title, message, options })
}

/** 多字段表单；取消返回 null。 */
export function formDialog(title, fields, hint = '') {
  return push('form', { title, fields, hint })
}
