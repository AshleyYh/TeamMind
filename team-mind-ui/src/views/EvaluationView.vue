<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  DataAnalysis,
  Document,
  Refresh,
  TrendCharts,
  View,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { api } from '@/api'
import type {
  DatasetDescriptor,
  EvaluationReport,
  EvaluationRunDetail,
  EvaluationRunSummary,
  Id,
  KnowledgeBase,
} from '@/api/types'
import { useSession } from '@/composables/session'
import { asFixed, asPercent, formatTime, statusTagType } from '@/utils/format'

const { state } = useSession()
const loading = ref(false)
const running = ref('')
const datasets = ref<DatasetDescriptor[]>([])
const knowledgeBases = ref<KnowledgeBase[]>([])
const selectedKbIds = ref<Id[]>([])
const runs = ref<EvaluationRunSummary[]>([])
const report = ref<EvaluationReport | null>(null)
const activeTab = ref<'rag' | 'task' | 'memory'>('rag')
const reportVisible = ref(false)
const reportMarkdown = ref('')
const reportLoading = ref(false)
const detailVisible = ref(false)
const runDetail = ref<EvaluationRunDetail | null>(null)

const ragForm = reactive({
  dataset: 'rag-retrieval',
  strategies: ['DENSE', 'BM25', 'HYBRID', 'HYBRID_RERANK'],
  topK: 5,
  evaluateAnswers: false,
})
const taskForm = reactive({
  dataset: 'task-classification',
})
const memoryForm = reactive({
  dataset: 'memory-retrieval',
  topK: 5,
})

const strategyNames = ['DENSE', 'BM25', 'HYBRID', 'HYBRID_RERANK']

const datasetOptions = computed(() => datasets.value)
const ragStrategies = computed(() => {
  const raw = report.value?.metrics?.strategies
  if (!raw || typeof raw !== 'object') {
    return [] as Array<{ name: string; metrics: Record<string, unknown> }>
  }
  return Object.entries(raw as Record<string, Record<string, unknown>>).map(([name, metrics]) => ({
    name,
    metrics,
  }))
})
const summaryMetrics = computed(() => {
  if (!report.value) {
    return [] as Array<{ key: string; value: string }>
  }
  return Object.entries(report.value.metrics)
    .filter(([key]) => key !== 'strategies')
    .map(([key, value]) => ({ key, value: String(value) }))
})
const renderedReport = computed(() =>
  DOMPurify.sanitize(marked.parse(reportMarkdown.value || '', { async: false }) as string),
)

async function loadBaseData() {
  loading.value = true
  try {
    const [datasetList, kbList, runList] = await Promise.all([
      api.datasets(),
      api.listKnowledgeBases(state.ownerId),
      api.evaluationRuns(state.ownerId, 20),
    ])
    datasets.value = datasetList
    knowledgeBases.value = kbList
    runs.value = runList
    if (!selectedKbIds.value.length && kbList.length) {
      selectedKbIds.value = [kbList[0].id]
    }
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    loading.value = false
  }
}

async function runRag() {
  if (!selectedKbIds.value.length) {
    ElMessage.warning('RAG 评测至少选择一个知识库')
    return
  }
  if (!ragForm.strategies.length) {
    ElMessage.warning('至少选择一种检索策略')
    return
  }
  running.value = 'rag'
  try {
    report.value = await api.evaluateRag({
      ownerId: state.ownerId,
      knowledgeBaseIds: selectedKbIds.value,
      strategies: ragForm.strategies,
      topK: ragForm.topK,
      dataset: ragForm.dataset,
      evaluateAnswers: ragForm.evaluateAnswers,
    })
    ElMessage.success('RAG 评测完成')
    await loadRuns()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    running.value = ''
  }
}

async function runTasks() {
  running.value = 'task'
  try {
    report.value = await api.evaluateTasks({
      ownerId: state.ownerId,
      dataset: taskForm.dataset,
      knowledgeBaseIds: selectedKbIds.value.length ? selectedKbIds.value : undefined,
    })
    ElMessage.success('任务识别评测完成')
    await loadRuns()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    running.value = ''
  }
}

async function runMemories() {
  running.value = 'memory'
  try {
    report.value = await api.evaluateMemories({
      ownerId: state.ownerId,
      dataset: memoryForm.dataset,
      topK: memoryForm.topK,
    })
    ElMessage.success('记忆评测完成')
    await loadRuns()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    running.value = ''
  }
}

async function loadRuns() {
  try {
    runs.value = await api.evaluationRuns(state.ownerId, 20)
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

async function openRun(runId: string) {
  try {
    runDetail.value = await api.evaluationRunDetail(runId, state.ownerId)
    detailVisible.value = true
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

async function openReport(runId: string) {
  reportLoading.value = true
  reportVisible.value = true
  try {
    reportMarkdown.value = await api.evaluationReport(runId, state.ownerId)
  } catch (error) {
    reportVisible.value = false
    ElMessage.error((error as Error).message)
  } finally {
    reportLoading.value = false
  }
}

function metricRows(metrics: Record<string, unknown>) {
  return Object.entries(metrics).filter(([, value]) => typeof value !== 'object')
}

function caseMetrics(caseItem: EvaluationReport['cases'][number]) {
  return JSON.stringify(caseItem.metrics)
}

onMounted(loadBaseData)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">评测中心</h2>
        <p class="page-sub">用固定数据集比较不同检索策略，并把运行结果和 Markdown 报告保存下来。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="loadBaseData">刷新</el-button>
    </div>

    <div class="eval-grid">
      <div class="card card-pad">
        <el-tabs v-model="activeTab">
          <el-tab-pane label="RAG 检索" name="rag">
            <el-form label-position="top" class="eval-form">
              <el-form-item label="知识库" required>
                <el-select v-model="selectedKbIds" multiple collapse-tags style="width: 100%">
                  <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
                </el-select>
              </el-form-item>
              <el-form-item label="数据集">
                <el-select v-model="ragForm.dataset" style="width: 100%">
                  <el-option
                    v-for="item in datasetOptions.filter((dataset) => dataset.type === 'RAG')"
                    :key="item.name"
                    :label="`${item.name}（${item.caseCount} 例）`"
                    :value="item.name"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="对比策略">
                <el-checkbox-group v-model="ragForm.strategies">
                  <el-checkbox v-for="name in strategyNames" :key="name" :label="name" :value="name">
                    {{ name }}
                  </el-checkbox>
                </el-checkbox-group>
              </el-form-item>
              <el-form-item label="Recall@K / MRR / NDCG@K">
                <el-input-number v-model="ragForm.topK" :min="1" :max="20" />
              </el-form-item>
              <el-form-item label="答案与引用评测">
                <el-switch v-model="ragForm.evaluateAnswers" />
                <span class="form-hint" style="margin-left: 10px">会调用 Qwen 生成答案，消耗模型配额</span>
              </el-form-item>
              <el-button type="primary" :loading="running === 'rag'" style="width: 100%" @click="runRag">
                <el-icon style="margin-right: 6px"><DataAnalysis /></el-icon>
                运行 RAG 评测
              </el-button>
            </el-form>
          </el-tab-pane>

          <el-tab-pane label="任务识别" name="task">
            <el-form label-position="top" class="eval-form">
              <el-form-item label="数据集">
                <el-select v-model="taskForm.dataset" style="width: 100%">
                  <el-option
                    v-for="item in datasetOptions.filter((dataset) => dataset.type === 'TASK')"
                    :key="item.name"
                    :label="`${item.name}（${item.caseCount} 例）`"
                    :value="item.name"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="知识库范围（可选）">
                <el-select v-model="selectedKbIds" multiple collapse-tags style="width: 100%">
                  <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
                </el-select>
              </el-form-item>
              <div class="form-hint" style="margin-bottom: 14px">
                任务评测计算任务类型准确率和期望工具命中率；模型不可用时可能降级为规则分类。
              </div>
              <el-button type="primary" :loading="running === 'task'" style="width: 100%" @click="runTasks">
                <el-icon style="margin-right: 6px"><TrendCharts /></el-icon>
                运行任务评测
              </el-button>
            </el-form>
          </el-tab-pane>

          <el-tab-pane label="长期记忆" name="memory">
            <el-form label-position="top" class="eval-form">
              <el-form-item label="数据集">
                <el-select v-model="memoryForm.dataset" style="width: 100%">
                  <el-option
                    v-for="item in datasetOptions.filter((dataset) => dataset.type === 'MEMORY')"
                    :key="item.name"
                    :label="`${item.name}（${item.caseCount} 例）`"
                    :value="item.name"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="检索条数">
                <el-input-number v-model="memoryForm.topK" :min="1" :max="20" />
              </el-form-item>
              <div class="form-hint" style="margin-bottom: 14px">
                记忆评测只检索当前 ownerId 下已确认的 ACTIVE 记忆；请先在长期记忆页面准备数据。
              </div>
              <el-button type="primary" :loading="running === 'memory'" style="width: 100%" @click="runMemories">
                <el-icon style="margin-right: 6px"><Document /></el-icon>
                运行记忆评测
              </el-button>
            </el-form>
          </el-tab-pane>
        </el-tabs>

        <div class="form-hint" style="margin-top: 14px">
          内置数据集是小规模示例，使用前请按本地知识库调整相关文档名和关键词。
        </div>
      </div>

      <div class="eval-result">
        <div v-if="!report" class="card card-pad empty-state">
          <div class="empty-icon"><el-icon><TrendCharts /></el-icon></div>
          <div>选择数据集后运行评测，结果会显示在这里。</div>
        </div>

        <template v-else>
          <div class="card card-pad">
            <div style="display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap">
              <div>
                <h3 style="margin: 0">{{ report.datasetName }}</h3>
                <div class="form-hint" style="margin-top: 4px">
                  Run {{ report.runId.slice(0, 12) }}… · hash {{ report.datasetHash.slice(0, 12) }}… ·
                  {{ report.caseCount }} 个用例
                </div>
              </div>
              <el-tag :type="statusTagType(report.status)" effect="light" round>{{ report.status }}</el-tag>
            </div>

            <div v-if="report.datasetType === 'RAG'" class="strategy-cards" style="margin-top: 16px">
              <div v-for="strategy in ragStrategies" :key="strategy.name" class="strategy-card">
                <h4>{{ strategy.name }}</h4>
                <div class="strategy-metric">
                  <span>Recall@K</span><strong>{{ asPercent(strategy.metrics.recallAtK) }}</strong>
                </div>
                <div class="strategy-metric">
                  <span>MRR</span><strong>{{ asPercent(strategy.metrics.mrr) }}</strong>
                </div>
                <div class="strategy-metric">
                  <span>NDCG@K</span><strong>{{ asPercent(strategy.metrics.ndcgAtK) }}</strong>
                </div>
                <div v-if="ragForm.evaluateAnswers" class="strategy-metric">
                  <span>引用精确率</span><strong>{{ asPercent(strategy.metrics.citationPrecision) }}</strong>
                </div>
                <div v-if="ragForm.evaluateAnswers" class="strategy-metric">
                  <span>引用召回率</span><strong>{{ asPercent(strategy.metrics.citationRecall) }}</strong>
                </div>
                <div v-if="ragForm.evaluateAnswers" class="strategy-metric">
                  <span>答案覆盖</span><strong>{{ asPercent(strategy.metrics.keywordCoverage) }}</strong>
                </div>
              </div>
            </div>

            <div v-else class="strategy-cards" style="margin-top: 16px">
              <div v-for="metric in summaryMetrics" :key="metric.key" class="strategy-card">
                <div class="strategy-metric">
                  <span>{{ metric.key }}</span>
                  <strong>{{ metric.value }}</strong>
                </div>
              </div>
            </div>

            <div style="margin-top: 16px">
              <el-button text type="primary" @click="openReport(report.runId)">查看 Markdown 报告</el-button>
            </div>
          </div>

          <div class="card table-card">
            <div class="card-pad" style="padding-bottom: 4px">
              <h4 style="margin: 0; font-size: 14px">逐用例结果</h4>
            </div>
            <el-table :data="report.cases" style="width: 100%">
              <el-table-column label="用例" width="160" show-overflow-tooltip prop="caseId" />
              <el-table-column label="查询" min-width="220" show-overflow-tooltip prop="query" />
              <el-table-column label="成功" width="80">
                <template #default="{ row }">
                  <el-tag :type="row.success ? 'success' : 'danger'" effect="plain" round>
                    {{ row.success ? '是' : '否' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="指标" min-width="260" show-overflow-tooltip>
                <template #default="{ row }">{{ caseMetrics(row) }}</template>
              </el-table-column>
              <el-table-column label="耗时" width="90">
                <template #default="{ row }">{{ row.durationMillis }} ms</template>
              </el-table-column>
              <el-table-column label="错误" min-width="140" show-overflow-tooltip>
                <template #default="{ row }">{{ row.errorMessage || '—' }}</template>
              </el-table-column>
            </el-table>
          </div>
        </template>

        <div class="card table-card">
          <div class="card-pad" style="padding-bottom: 4px; display: flex; justify-content: space-between">
            <h4 style="margin: 0; font-size: 14px">历史运行</h4>
            <el-button text type="primary" @click="loadRuns">刷新</el-button>
          </div>
          <el-table :data="runs" style="width: 100%">
            <el-table-column label="数据集" min-width="180" prop="datasetName" />
            <el-table-column label="类型" width="90" prop="datasetType" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="statusTagType(row.status)" effect="light" round>{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="用例" width="70" prop="caseCount" />
            <el-table-column label="时间" width="170">
              <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="170" fixed="right">
              <template #default="{ row }">
                <el-button text type="primary" :icon="View" @click="openRun(row.runId)">详情</el-button>
                <el-button text type="primary" @click="openReport(row.runId)">报告</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </div>

    <el-dialog v-model="reportVisible" title="Markdown 评测报告" width="820px">
      <div v-loading="reportLoading" class="report-markdown">
        <div class="markdown-body" v-html="renderedReport" />
      </div>
    </el-dialog>

    <el-dialog v-model="detailVisible" title="评测运行详情" width="820px">
      <template v-if="runDetail">
        <div class="form-hint" style="margin-bottom: 12px">
          {{ runDetail.run.datasetName }} · {{ runDetail.run.runId }} · {{ runDetail.run.caseCount }} 个用例
        </div>
        <el-table :data="runDetail.cases" size="small" style="width: 100%">
          <el-table-column label="用例" width="150" prop="caseId" />
          <el-table-column label="查询" min-width="200" show-overflow-tooltip prop="query" />
          <el-table-column label="成功" width="70">
            <template #default="{ row }">
              <el-tag :type="row.success ? 'success' : 'danger'" size="small" effect="plain" round>
                {{ row.success ? '是' : '否' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="指标" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">{{ caseMetrics(row) }}</template>
          </el-table-column>
          <el-table-column label="耗时" width="80">
            <template #default="{ row }">{{ row.durationMillis }} ms</template>
          </el-table-column>
        </el-table>
      </template>
    </el-dialog>
  </div>
</template>
