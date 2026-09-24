/**
 * 灵动岛 Toast 展开动效的“起搏器”。
 *
 * 背景（改这里之前请先读，全部是实测结论）：
 * 1. vue-goey-toast 在展开的同一帧，会给胶囊元素写内联 `transition: none !important`
 *    并改写 --offset / --initial-height，CSS 过渡被就地掐断——最新胶囊 88px 的位移
 *    在单帧内到位（60fps 采样无任何中间帧），所以调 transition 完全没有用。
 * 2. 于是展开改用 @keyframes 驱动（动画不受 transition 抑制）。但带动画的 transform
 *    会通过 fill 一直压在元素上，随后收起时 transform 的接管权仍在动画手里，
 *    过渡不会发生——收起同样变成单帧瞬跳（实测 82.9px/帧）。
 * 3. 因此动画只在“展开进行中”挂着：animationend 一到就摘掉 data-unfolding，
 *    把 transform 交还给库自己的规则，收起恢复成平滑过渡（实测 1.7px/帧）。
 * 4. 唯一需要额外接管的时刻：展开动画没跑完就移开鼠标。此时直接摘掉 data-unfolding
 *    会让胶囊从半途弹回折叠位（实测 73.3px/帧）。所以这里读取它“此刻真正的”
 *    transform（getComputedStyle 含动画当前值），用 Web Animations 续接到折叠位，
 *    终点与库的折叠表达式逐字一致，动画结束交接时不会二次跳变。
 *
 * 逐条延迟（--unfold-delay）在挂载瞬间就算成内联变量写死：动画期间队列序号如果变化
 * （悬停时又来一条新消息），基于 --toasts-before 的 animation-delay 会被改写，
 * 已完成的动画可能被倒带回起点；写死内联值可以避免。
 */

/** 相邻胶囊展开的时间差与折叠续接时长，与 style.css 中的注释保持同步。 */
const UNFOLD_STEP_MS = 45
const COLLAPSE_MS = 240
/** 胶囊逐条收进队列时的缩放递减，与库的折叠表达式一致。 */
const SCALE_STEP = 0.05

function readGap(element) {
  const parent = element.parentElement
  const gap = parent ? parseFloat(getComputedStyle(parent).getPropertyValue('--gap')) : NaN
  return Number.isFinite(gap) && gap > 0 ? gap : 10
}

function arm(element) {
  const index = Number(element.getAttribute('data-index')) || 0
  element.style.setProperty('--unfold-delay', `${index * UNFOLD_STEP_MS}ms`)
  element.setAttribute('data-unfolding', '')
}

function clearState(element) {
  element.removeAttribute('data-unfolding')
  element.style.removeProperty('--unfold-delay')
}

/**
 * 展开动画半途被收起时，从胶囊当前实际位置续接到折叠位，避免弹回。
 * 目标值与 style.css 的折叠表达式（-gap * index，scale 1 - 0.05 * index）一致。
 */
function continueToCollapsed(element) {
  const from = getComputedStyle(element).transform
  const index = Number(element.getAttribute('data-index')) || 0
  const to = `translateY(${-readGap(element) * index}px) scale(${1 - index * SCALE_STEP})`
  clearState(element)
  element.animate([{ transform: from }, { transform: to }], {
    duration: COLLAPSE_MS,
    easing: 'cubic-bezier(.4, 0, .2, 1)'
  })
}

export function installToastFoldAnimator() {
  if (typeof document === 'undefined') return

  const sync = (element) => {
    const unfolding =
      element.getAttribute('data-expanded') === 'true' &&
      element.getAttribute('data-removed') !== 'true'

    if (unfolding) {
      arm(element)
      return
    }

    // 不在展开中、且当前也没挂着动画：交给库自己的过渡，无需介入。
    if (!element.hasAttribute('data-unfolding')) return

    // 移除动画有自己的一套时序（滑出/淡出），不在这里接管。
    if (element.getAttribute('data-removed') === 'true') {
      clearState(element)
      return
    }

    continueToCollapsed(element)
  }

  new MutationObserver((records) => {
    for (const record of records) {
      if (record.type === 'attributes' && record.target instanceof HTMLElement) {
        sync(record.target)
      }
    }
  }).observe(document.body, {
    attributes: true,
    attributeFilter: ['data-expanded', 'data-removed'],
    subtree: true
  })

  // animationend 会冒泡，用事件委托即可；只有胶囊本体带 data-unfolding。
  document.addEventListener('animationend', (event) => {
    const target = event.target
    if (target instanceof HTMLElement && target.hasAttribute('data-unfolding')) {
      clearState(target)
    }
  })
}
