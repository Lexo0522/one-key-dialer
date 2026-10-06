import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import {writeFileSync} from 'node:fs'
import {resolve} from 'node:path'

/**
 * main.go 用 `//go:embed all:frontend/dist` 内嵌前端产物，而 go:embed 要求
 * 目标目录在编译期就存在。干净的 checkout 里 dist/ 只有一个占位文件，
 * 但 vite 的 emptyOutDir 会在构建前把它一起清掉——于是 `go build` 在
 * 「先 build 前端、再 build Go」之外的任何调用顺序下都会失败
 * （CI 之所以没暴露，是因为它恰好先跑了 npm run build）。
 *
 * 构建结束后立刻写回占位文件，让 dist/ 在任何时刻都非空。
 * 内容为空字符串，重复写入不产生 git diff。
 *
 * 路径取 vite 解析后的 root/outDir，而不是 import.meta.dirname——
 * config 被 esbuild 打包执行时后者会指向临时目录。
 */
function restoreDistPlaceholder() {
  let root = process.cwd()
  let outDir = 'dist'
  return {
    name: 'restore-dist-placeholder',
    apply: 'build',
    configResolved(config) {
      root = config.root
      outDir = config.build.outDir
    },
    closeBundle() {
      writeFileSync(resolve(root, outDir, 'gitkeep'), '')
    }
  }
}

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue(), restoreDistPlaceholder()],
  build: {
    outDir: 'dist',
    emptyOutDir: true
  },
  test: {
    // 只跑 src 下的 *.test.js。environment 用 node 而非 jsdom：第一批测的
    // 全是无 DOM 依赖的纯函数（format / i18n / presets），node 环境启动快、
    // 也不引入额外依赖。哪天要测组件再单独给文件加 // @vitest-environment
    // 注释，不必全局改成 jsdom。
    environment: 'node',
    include: ['src/**/*.test.js'],
    globals: true
  }
})
