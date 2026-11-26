<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ChatDotRound,
  Collection,
  Document,
  MagicStick,
  Monitor,
  Refresh,
  TrendCharts,
  Warning,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import type { DashboardStats } from '@/api/types'
import { useSession } from '@/composables/session'
import { formatDuration, formatTime, formatTokens, statusTagType } from '@/utils/format'

const router = useRouter()
const { state } = useSession()
const loading = ref(true)
const stats = ref<DashboardStats | null>(null)

async function load() {
  loading.value = true
  try {
    stats.value = await api.dashboard(state.ownerId)
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function openTrace(traceId: string) {
  router.push({ path: '/traces', query: { traceId } })
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">工作台</h2>
        <p class="page-sub">资源总览、最近 Agent 执行和常用入口。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-alert
      v-if="stats && stats.failedTraceCount > 0"
      type="warning"
      show-icon
      :closable="false"
      :title="`有 ${stats.failedTraceCount} 次执行失败`"
      description="进入执行监控查看失败节点、错误信息和 Token 消耗。"
    >
      <template #default>
        <el-button text type="warning" @click="router.push('/traces')">查看执行监控</el-button>
      </template>
    </el-alert>

    <el-skeleton v-if="loading && !stats" :rows="5" animated />

    <template v-else-if="stats">
      <div class="metric-grid">
        <div class="metric-card">
          <div class="metric-icon"><el-icon><Collection /></el-icon></div>
          <div>
            <div class="metric-value">{{ stats.knowledgeBaseCount }}</div>
            <div class="metric-label">知识库</div>
          </div>
        </div>
        <div class="metric-card">
          <div class="metric-icon"><el-icon><Document /></el-icon></div>
          <div>
            <div class="metric-value">{{ stats.documentCount }}</div>
            <div class="metric-label">文档</div>
          </div>
        </div>
        <div class="metric-card">
          <div class="metric-icon"><el-icon><ChatDotRound /></el-icon></div>
          <div>
            <div class="metric-value">{{ stats.conversationCount }}</div>
            <div class="metric-label">会话</div>
          </div>
        </div>
        <div class="metric-card">
          <div class="metric-icon"><el-icon><MagicStick /></el-icon></div>
          <div>
            <div class="metric-value">{{ stats.memoryCount }}</div>
            <div class="metric-label">长期记忆</div>
          </div>
        </div>
        <div class="metric-card">
          <div class="metric-icon"><el-icon><Monitor /></el-icon></div>
          <div>
            <div class="metric-value">{{ stats.traceCount }}</div>
            <div class="metric-label">Trace 执行</div>
          </div>
        </div>
        <div class="metric-card">
          <div class="metric-icon amber"><el-icon><TrendCharts /></el-icon></div>
          <div>
            <div class="metric-value">{{ stats.evaluationRunCount }}</div>
            <div class="metric-label">评测运行</div>
          </div>
        </div>
        <div class="metric-card">
          <div class="metric-icon rose"><el-icon><Warning /></el-icon></div>
          <div>
            <div class="metric-value">{{ stats.failedTraceCount }}</div>
            <div class="metric-label">失败执行</div>
          </div>
        </div>
      </div>

      <div class="grid-2">
        <div class="card">
          <div class="card-pad" style="padding-bottom: 8px">
            <div style="display: flex; justify-content: space-between; align-items: center">
              <div>
                <h3 style="margin: 0; font-size: 15px">最近执行</h3>
                <p class="page-sub" style="margin: 4px 0 0">最近 5 次 Agent / RAG 请求</p>
              </div>
              <el-button text type="primary" @click="router.push('/traces')">全部</el-button>
            </div>
          </div>
          <div class="table-card">
            <el-table
              :data="stats.recentTraces"
              style="width: 100%"
              @row-click="(row: any) => openTrace(row.traceId)"
            >
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag :type="statusTagType(row.status)" effect="light" round>{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="问题" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">{{ row.query || '—' }}</template>
              </el-table-column>
              <el-table-column label="策略" width="150" show-overflow-tooltip>
                <template #default="{ row }">{{ row.strategy || '—' }}</template>
              </el-table-column>
              <el-table-column label="耗时" width="90">
                <template #default="{ row }">{{ formatDuration(row.totalDuration) }}</template>
              </el-table-column>
              <el-table-column label="Token" width="80">
                <template #default="{ row }">{{ formatTokens(row.totalTokens) }}</template>
              </el-table-column>
              <el-table-column label="时间" width="170">
                <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
              </el-table-column>
              <template #empty>
                <div class="empty-state" style="padding: 30px">还没有执行记录，先去 AI 对话提问。</div>
              </template>
            </el-table>
          </div>
        </div>

        <div class="card card-pad">
          <h3 style="margin: 0 0 4px; font-size: 15px">快速开始</h3>
          <p class="page-sub" style="margin: 0 0 14px">从文档入库到 Agent 回答的常用路径。</p>
          <div class="quick-actions">
            <button class="quick-action" @click="router.push('/knowledge-bases')">
              <strong>创建知识库</strong>
              <span>建立研发文档空间</span>
            </button>
            <button class="quick-action" @click="router.push('/documents')">
              <strong>上传文档</strong>
              <span>PDF / DOCX / TXT / Markdown</span>
            </button>
            <button class="quick-action" @click="router.push('/chat')">
              <strong>向 Agent 提问</strong>
              <span>多轮对话、工具调用与引用</span>
            </button>
            <button class="quick-action" @click="router.push('/evaluation')">
              <strong>运行评测</strong>
              <span>对比 Dense / BM25 / Hybrid</span>
            </button>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
