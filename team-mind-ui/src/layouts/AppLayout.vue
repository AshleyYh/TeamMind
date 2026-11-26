<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ChatDotRound,
  Collection,
  Document,
  HomeFilled,
  MagicStick,
  Monitor,
  SwitchButton,
  TrendCharts,
} from '@element-plus/icons-vue'
import { useSession } from '@/composables/session'

const route = useRoute()
const router = useRouter()
const { state, logout } = useSession()

const menu = [
  { path: '/dashboard', label: '工作台', icon: HomeFilled },
  { path: '/knowledge-bases', label: '知识库', icon: Collection },
  { path: '/documents', label: '文档', icon: Document },
  { path: '/chat', label: 'AI 对话', icon: ChatDotRound },
  { path: '/traces', label: '执行监控', icon: Monitor },
  { path: '/memories', label: '长期记忆', icon: MagicStick },
  { path: '/evaluation', label: '评测中心', icon: TrendCharts },
]

const activeMenu = computed(() => route.path)
const pageTitle = computed(() => (route.meta.title as string) || 'TeamMind')
const initial = computed(() => (state.displayName || 'T').slice(0, 1).toUpperCase())

function handleLogout() {
  logout()
  router.replace('/login')
}
</script>

<template>
  <el-container class="shell">
    <el-aside width="232px" class="sidebar">
      <div class="brand">
        <div class="brand-mark">
          <div class="logo">TM</div>
          <div class="brand-text">
            <div class="brand-title">TeamMind</div>
            <div class="brand-sub">R&amp;D Knowledge Agent</div>
          </div>
        </div>
      </div>

      <el-menu :default-active="activeMenu" router class="nav-menu">
        <el-menu-item v-for="item in menu" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <span class="nav-label">{{ item.label }}</span>
        </el-menu-item>
      </el-menu>

      <div class="sidebar-user">
        <div class="avatar">{{ initial }}</div>
        <div class="user-meta">
          <div class="user-name">{{ state.displayName }}</div>
          <div class="user-role">ownerId {{ state.ownerId }} · 开发模式</div>
        </div>
        <el-tooltip content="退出开发会话" placement="top">
          <el-button text circle class="logout-btn" @click="handleLogout">
            <el-icon><SwitchButton /></el-icon>
          </el-button>
        </el-tooltip>
      </div>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div>
          <h1>{{ pageTitle }}</h1>
          <div class="top-sub">企业研发知识库 · Agent 检索 · 记忆与可观测性</div>
        </div>
        <div class="topbar-actions">
          <div class="owner-chip">
            <span class="mono">ownerId</span>
            <strong>{{ state.ownerId }}</strong>
          </div>
          <el-button text @click="router.push('/chat')">开始提问</el-button>
        </div>
      </el-header>

      <el-main class="content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.logout-btn {
  color: #8296a6;
}
</style>
