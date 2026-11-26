<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Clock, Coin, CopyDocument, Monitor, Refresh, Warning } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import type { EvidenceItem, TraceDetail, TraceSpan, TraceSummary } from '@/api/types'
import { useSession } from '@/composables/session'
import { formatDuration, formatTime, formatTokens, statusTagType } from '@/utils/format'

const route = useRoute()
const { state } = useSession()
const traces = ref<TraceSummary[]>([])
const loading = ref(false)
const detail = ref<TraceDetail | null>(null)
const evidences = ref<EvidenceItem[]>([])
const detailLoading = ref(false)
const selectedTraceId = ref('')

const selectedTrace = computed(() => detail.value?.trace || null)
const spans = computed<TraceSpan[]>(() => {
  const list = detail.value?.spans || []
  return [...list].sort((a, b) =>
    String(a.id || '').localeCompare(String(b.id || ''), undefined, { numeric: true }),
  )
})

async function loadTraces() {
  loading.value = true
  try {
    traces.value = await api.listTraces(state.ownerId, 30)
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    loading.value = false
  }
}

async function openTrace(traceId: string) {
  if (!traceId) {
    return
  }
  selectedTraceId.value = traceId
  detailLoading.value = true
  try {
    detail.value = await api.traceDetail(traceId, state.ownerId)
    evidences.value = await api.traceEvidences(traceId, state.ownerId)
  } catch (error) {
    detail.value = null
    evidences.value = []
    ElMessage.error((error as Error).message)
  } finally {
    detailLoading.value = false
  }
}

async function copyTraceId() {
  if (!selectedTrace.value?.traceId) {
    return
  }
  try {
    await navigator.clipboard.writeText(selectedTrace.value.traceId)
    ElMessage.success('Trace ID 已复制')
  } catch (_) {
    ElMessage.warning('浏览器不允许访问剪贴板')
  }
}

function spanColor(status: string) {
  if (status === 'FAILED') {
    return '#c2413b'
  }
  if (status === 'RUNNING') {
    return '#d97706'
  }
  return '#0f766e'
}

onMounted(async () => {
  await loadTraces()
  const queryTrace = typeof route.query.traceId === 'string' ? route.query.traceId : ''
  if (queryTrace) {
    await openTrace(queryTrace)
  } else if (traces.value.length) {
    await openTrace(traces.value[0].traceId)
  }
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">执行监控</h2>
        <p class="page-sub">按 Trace ID 查看任务理解、Agent 决策、工具调用、RAG 检索、Token 与失败节点。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="loadTraces">刷新</el-button>
    </div>

    <div class="trace-layout">
      <div class="card table-card">
        <el-table
          v-loading="loading"
          :data="traces"
          height="640"
          highlight-current-row
          @row-click="(row: TraceSummary) => openTrace(row.traceId)"
        >
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" effect="light" round>{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="问题" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ row.query || '—' }}</template>
          </el-table-column>
          <el-table-column label="类型/策略" width="150" show-overflow-tooltip>
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
            <div class="empty-state" style="padding: 40px">还没有 Trace，先去 AI 对话发起一次请求。</div>
          </template>
        </el-table>
      </div>

      <div v-loading="detailLoading" class="card card-pad trace-detail">
        <div v-if="!selectedTrace" class="empty-state" style="padding: 76px 20px">
          <div class="empty-icon"><el-icon><Monitor /></el-icon></div>
          <div>选择左侧一条 Trace 查看执行过程。</div>
        </div>

        <template v-else>
          <div class="trace-head">
            <div>
              <div style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap">
                <h3 style="margin: 0; font-size: 17px">执行详情</h3>
                <el-tag :type="statusTagType(selectedTrace.status)" effect="light" round>
                  {{ selectedTrace.status }}
                </el-tag>
              </div>
              <div class="trace-id mono">{{ selectedTrace.traceId }}</div>
            </div>
            <el-button text :icon="CopyDocument" @click="copyTraceId">复制</el-button>
          </div>

          <div class="trace-kpis">
            <div class="trace-kpi">
              <div class="label"><el-icon><Clock /></el-icon> 耗时</div>
              <div class="value">{{ formatDuration(selectedTrace.totalDuration) }}</div>
            </div>
            <div class="trace-kpi">
              <div class="label"><el-icon><Coin /></el-icon> 总 Token</div>
              <div class="value">{{ formatTokens(selectedTrace.totalTokens) }}</div>
            </div>
            <div class="trace-kpi">
              <div class="label">输入</div>
              <div class="value">{{ formatTokens(selectedTrace.promptTokens) }}</div>
            </div>
            <div class="trace-kpi">
              <div class="label">输出</div>
              <div class="value">{{ formatTokens(selectedTrace.completionTokens) }}</div>
            </div>
          </div>

          <div class="span-meta" style="margin-bottom: 12px">
            <span>策略 {{ selectedTrace.strategy || '—' }}</span>
            <span v-if="selectedTrace.conversationId">会话 {{ selectedTrace.conversationId }}</span>
            <span>{{ formatTime(selectedTrace.createdAt) }}</span>
          </div>

          <el-alert
            v-if="selectedTrace.errorMessage"
            type="error"
            :closable="false"
            show-icon
            :title="selectedTrace.errorMessage"
            style="margin-bottom: 14px"
          />

          <h4 style="margin: 14px 0 10px; font-size: 13px">执行节点</h4>
          <div v-if="!spans.length" class="form-hint">没有 Span 记录。</div>
          <div
            v-for="span in spans"
            :key="span.spanId"
            class="span-card"
            :class="{ failed: span.status === 'FAILED' }"
            :style="{ borderLeft: `3px solid ${spanColor(span.status)}` }"
          >
            <div class="span-head">
              <div>
                <span class="span-type">{{ span.spanType }}</span>
                <el-tag
                  :type="statusTagType(span.status)"
                  size="small"
                  effect="plain"
                  round
                  style="margin-left: 8px"
                >
                  {{ span.status }}
                </el-tag>
              </div>
              <span class="form-hint">{{ formatDuration(span.duration) }}</span>
            </div>
            <div class="span-meta">
              <span v-if="span.inputTokens != null || span.outputTokens != null">
                Token {{ (span.inputTokens || 0) + (span.outputTokens || 0) }}
                ({{ span.inputTokens || 0 }} / {{ span.outputTokens || 0 }})
              </span>
              <span v-if="span.errorType" style="color: var(--tm-danger)">{{ span.errorType }}</span>
              <span v-if="span.parentSpanId" class="mono">parent {{ span.parentSpanId.slice(0, 8) }}…</span>
            </div>
            <div v-if="span.inputSummary" class="span-summary">输入：{{ span.inputSummary }}</div>
            <div v-if="span.outputSummary" class="span-summary">输出：{{ span.outputSummary }}</div>
            <div v-if="span.errorMessage" class="span-summary" style="color: var(--tm-danger)">
              错误：{{ span.errorMessage }}
            </div>
          </div>

          <h4 style="margin: 18px 0 10px; font-size: 13px">Evidence</h4>
          <el-table :data="evidences" size="small" style="width: 100%">
            <el-table-column label="文档" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">{{ row.documentName }} v{{ row.documentVersion }}</template>
            </el-table-column>
            <el-table-column label="页" width="60">
              <template #default="{ row }">{{ row.pageNumber || '—' }}</template>
            </el-table-column>
            <el-table-column label="检索" width="90">
              <template #default="{ row }">{{ row.retrievalType }}</template>
            </el-table-column>
            <el-table-column label="引用" width="80">
              <template #default="{ row }">
                <el-tag :type="row.cited ? 'success' : 'info'" size="small" effect="plain" round>
                  {{ row.cited ? '已引用' : '未引用' }}
                </el-tag>
              </template>
            </el-table-column>
            <template #empty>
              <div class="form-hint" style="padding: 12px">本次执行没有 Evidence。</div>
            </template>
          </el-table>
        </template>
      </div>
    </div>
  </div>
</template>
