import { createApp } from 'vue'
import App from './App.vue'
// 本地打包的 Font Awesome 5.15.4(CSS + woff2),离线可用,不再依赖 CDN
import './assets/fontawesome/css/all.min.css'
import './style.css'
import 'vue-goey-toast/styles.css'

createApp(App).mount('#app')
