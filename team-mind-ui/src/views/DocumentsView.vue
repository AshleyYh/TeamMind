<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  Delete,
  Document,
  Refresh,
  RefreshLeft,
  UploadFilled,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type UploadRequestOptions } from 'element-plus'
import { api } from '@/api'
import type { DocumentItem, Id, KnowledgeBase } from '@/api/types'
import { useSession } from '@/composables/session'
import { formatTime, statusTagType } from '@/utils/format'

const route = useRoute()
const { state } = useSession()
const knowledgeBases = ref<KnowledgeBase[]>([])
const selectedKbId = ref<Id | undefined>(undefined)
const documents = ref<DocumentItem[]>([])
const loading = ref(false)
const uploading = ref(false)
let pollTimer: number | undefined

const selectedKb = computed(() => knowledgeBases.value.find((item) => item.id === selectedKbId.value))
const hasProcessing = computed(() =>
  documents.value.some((item) => item.processingStatus === 'PENDING' || item.processingStatus === 'PROCESSING'),
)

async function loadKnowledgeBases() {
  try {
    knowledgeBases.value = await api.listKnowledgeBases(state.ownerId)
    const queryKb = typeof route.query.kbId === 'string' ? route.query.kbId : undefined
    if (queryKb && knowledgeBases.value.some((item) => item.id === queryKb)) {
      selectedKbId.value = queryKb
    } else if (!selectedKbId.value && knowledgeBases.value.length) {
      selectedKbId.value = knowledgeBases.value[0].id
    }
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

async function loadDocuments(silent = false) {
  if (!selectedKbId.value) {
    documents.value = []
    return
  }
  if (!silent) {
    loading.value = true
  }
  try {
    documents.value = await api.listDocuments(selectedKbId.value, state.ownerId)
    syncPolling()
  } catch (error) {
    if (!silent) {
      ElMessage.error((error as Error).message)
    }
  } finally {
    loading.value = false
  }
}

function syncPolling() {
  if (hasProcessing.value && pollTimer == null) {
    pollTimer = window.setInterval(() => loadDocuments(true), 3000)
  } else if (!hasProcessing.value && pollTimer != null) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
}

function beforeUpload(file: File) {
  const allowed = ['pdf', 'docx', 'txt', 'md']
  const extension = file.name.split('.').pop()?.toLowerCase() || ''
  if (!allowed.includes(extension)) {
    ElMessage.warning('只支持 PDF、DOCX、TXT 和 Markdown 文件')
    return false
  }
  if (file.size > 20 * 1024 * 1024) {
    ElMessage.warning('文件大小不能超过 20 MB')
    return false
  }
  return true
}

async function handleUpload(options: UploadRequestOptions) {
  if (!selectedKbId.value) {
    ElMessage.warning('请先选择知识库')
    throw new Error('未选择知识库')
  }
  uploading.value = true
  try {
    const uploaded = await api.uploadDocument(selectedKbId.value, state.ownerId, options.file)
    ElMessage.success(`已上传：${uploaded.documentName}，后台解析中`)
    await loadDocuments()
    return uploaded
  } catch (error) {
    ElMessage.error((error as Error).message)
    throw error
  } finally {
    uploading.value = false
  }
}

async function reprocess(item: DocumentItem) {
  try {
    await api.reprocessDocument(item.id, state.ownerId)
    ElMessage.success('已重新提交处理')
    await loadDocuments()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

async function remove(item: DocumentItem) {
  try {
    await ElMessageBox.confirm(`删除文档「${item.documentName}」会同时清理分块和向量，是否继续？`, '删除文档', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch (_) {
    return
  }
  try {
    await api.deleteDocument(item.id, state.ownerId)
    ElMessage.success('文档已删除')
    await loadDocuments()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

watch(selectedKbId, () => loadDocuments())
onMounted(async () => {
  await loadKnowledgeBases()
  await loadDocuments()
})
onUnmounted(() => {
  if (pollTimer != null) {
    window.clearInterval(pollTimer)
  }
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">文档</h2>
        <p class="page-sub">上传后异步解析、分块、生成 Embedding 并写入 Qdrant；状态会自动轮询。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="loadDocuments()">刷新</el-button>
    </div>

    <div class="card card-pad">
      <div style="display: flex; gap: 12px; align-items: center; flex-wrap: wrap">
        <el-select v-model="selectedKbId" placeholder="选择知识库" style="width: 280px">
          <el-option v-for="item in knowledgeBases" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <div v-if="selectedKb" class="form-hint">
          当前：<strong>{{ selectedKb.name }}</strong> · {{ documents.length }} 篇文档
        </div>
        <div style="margin-left: auto">
          <el-tag v-if="hasProcessing" type="warning" effect="light" round>后台处理中，自动刷新</el-tag>
        </div>
      </div>

      <el-empty v-if="!knowledgeBases.length" description="还没有知识库，请先创建知识库">
        <el-button type="primary" @click="$router.push('/knowledge-bases')">去创建</el-button>
      </el-empty>

      <template v-else-if="selectedKbId">
        <el-upload
          drag
          :show-file-list="false"
          :http-request="handleUpload"
          :before-upload="beforeUpload"
          accept=".pdf,.docx,.txt,.md"
          class="upload-zone"
          style="margin: 18px 0"
        >
          <div class="upload-inner" :class="{ disabled: uploading }">
            <div class="upload-icon"><el-icon><UploadFilled /></el-icon></div>
            <div class="upload-title">
              {{ uploading ? '上传中…' : '拖拽文件到这里，或点击选择文件' }}
            </div>
            <div class="upload-hint">支持 PDF / DOCX / TXT / Markdown，单文件不超过 20 MB</div>
          </div>
        </el-upload>

        <el-table v-loading="loading" :data="documents" style="width: 100%">
          <el-table-column label="文档" min-width="230">
            <template #default="{ row }">
              <div style="display: flex; align-items: center; gap: 8px">
                <div class="metric-icon" style="width: 30px; height: 30px; border-radius: 9px; font-size: 14px">
                  <el-icon><Document /></el-icon>
                </div>
                <div>
                  <div style="font-weight: 600">{{ row.documentName }}</div>
                  <div class="form-hint">{{ row.fileType.toUpperCase() }}</div>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="版本" width="80">
            <template #default="{ row }">v{{ row.currentVersion }}</template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.processingStatus)" effect="light" round>
                {{ row.processingStatus }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="失败原因" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <span :style="{ color: row.failureReason ? 'var(--tm-danger)' : 'var(--tm-ink-soft)' }">
                {{ row.failureReason || '—' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="180">
            <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button text type="primary" :icon="RefreshLeft" @click="reprocess(row)">重处理</el-button>
              <el-button text type="danger" :icon="Delete" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty-state">
              <div class="empty-icon"><el-icon><Document /></el-icon></div>
              <div>还没有文档，上传第一份研发资料。</div>
            </div>
          </template>
        </el-table>
      </template>
    </div>
  </div>
</template>

<style scoped>
.upload-zone :deep(.el-upload-dragger) {
  border: 1px dashed #c9dad9;
  background: #f8fbfb;
  border-radius: 14px;
  padding: 26px;
  transition: border-color 0.2s ease, background 0.2s ease;
}

.upload-zone :deep(.el-upload-dragger:hover) {
  border-color: var(--tm-primary);
  background: var(--tm-primary-soft);
}

.upload-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.upload-icon {
  width: 46px;
  height: 46px;
  border-radius: 14px;
  background: #e6f4f1;
  color: var(--tm-primary);
  display: grid;
  place-items: center;
  font-size: 22px;
}

.upload-title {
  font-weight: 600;
  font-size: 14px;
}

.upload-hint {
  color: var(--tm-ink-soft);
  font-size: 12px;
}
</style>
