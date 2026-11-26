<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Check,
  Delete,
  EditPen,
  Link,
  MagicStick,
  Plus,
  Refresh,
  Search,
  Warning,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api'
import type { MemoryItem, MemoryStatus, MemoryType } from '@/api/types'
import { useSession } from '@/composables/session'
import { formatTime, statusTagType } from '@/utils/format'

const router = useRouter()
const { state } = useSession()
const loading = ref(false)
const items = ref<MemoryItem[]>([])
const keyword = ref('')
const statusFilter = ref<MemoryStatus | ''>('')
const typeFilter = ref<MemoryType | ''>('')
const dialogVisible = ref(false)
const saving = ref(false)
const editing = ref<MemoryItem | null>(null)
const form = reactive({
  memoryType: 'FACT' as MemoryType,
  title: '',
  content: '',
  summary: '',
  confidence: 0.8,
})

const typeOptions: Array<{ label: string; value: MemoryType }> = [
  { label: '事实', value: 'FACT' },
  { label: '偏好', value: 'PREFERENCE' },
  { label: '决策', value: 'DECISION' },
  { label: '经验', value: 'EXPERIENCE' },
  { label: '画像', value: 'PROFILE' },
  { label: '其他', value: 'OTHER' },
]

async function load() {
  loading.value = true
  try {
    const query = keyword.value.trim()
    if (query) {
      items.value = await api.searchMemories(state.ownerId, query, 20)
    } else {
      items.value = await api.listMemories({
        ownerId: state.ownerId,
        status: statusFilter.value || undefined,
        type: typeFilter.value || undefined,
        limit: 100,
      })
    }
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.memoryType = 'FACT'
  form.title = ''
  form.content = ''
  form.summary = ''
  form.confidence = 0.8
  dialogVisible.value = true
}

function openEdit(item: MemoryItem) {
  editing.value = item
  form.memoryType = item.memoryType
  form.title = item.title
  form.content = item.content
  form.summary = item.summary || ''
  form.confidence = item.confidence ?? 0.8
  dialogVisible.value = true
}

async function save() {
  if (!form.content.trim()) {
    ElMessage.warning('记忆内容不能为空')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await api.updateMemory(editing.value.id, {
        ownerId: state.ownerId,
        memoryType: form.memoryType,
        title: form.title || undefined,
        content: form.content.trim(),
        summary: form.summary || undefined,
      })
      ElMessage.success('记忆已更新，版本已递增')
    } else {
      await api.createMemory({
        ownerId: state.ownerId,
        memoryType: form.memoryType,
        title: form.title || undefined,
        content: form.content.trim(),
        summary: form.summary || undefined,
        confidence: form.confidence,
      })
      ElMessage.success('记忆草稿已创建，确认后才会参与检索')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    saving.value = false
  }
}

async function confirmMemory(item: MemoryItem) {
  try {
    await api.confirmMemory(item.id, state.ownerId)
    ElMessage.success('记忆已启用')
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

async function invalidate(item: MemoryItem) {
  try {
    await api.invalidateMemory(item.id, state.ownerId)
    ElMessage.success('记忆已失效')
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

async function remove(item: MemoryItem) {
  try {
    await ElMessageBox.confirm(`删除记忆「${item.title}」？`, '删除记忆', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch (_) {
    return
  }
  try {
    await api.deleteMemory(item.id, state.ownerId)
    ElMessage.success('记忆已删除')
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

function openSource(item: MemoryItem) {
  if (item.sourceTraceId) {
    router.push({ path: '/traces', query: { traceId: item.sourceTraceId } })
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">长期记忆</h2>
        <p class="page-sub">只有确认后的 ACTIVE 记忆会被 Agent 检索；来源会话和 Trace 可追溯。</p>
      </div>
      <div style="display: flex; gap: 8px">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新增记忆</el-button>
      </div>
    </div>

    <div class="card card-pad">
      <div style="display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 14px">
        <el-input
          v-model="keyword"
          :prefix-icon="Search"
          placeholder="检索 ACTIVE 记忆内容"
          clearable
          style="max-width: 320px"
          @keyup.enter="load"
          @clear="load"
        />
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px" @change="load">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已启用" value="ACTIVE" />
          <el-option label="已失效" value="INVALID" />
        </el-select>
        <el-select v-model="typeFilter" placeholder="全部类型" clearable style="width: 140px" @change="load">
          <el-option v-for="option in typeOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-button :icon="Search" @click="load">查询</el-button>
      </div>

      <el-table v-loading="loading" :data="items" style="width: 100%">
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag effect="plain" round>{{ row.memoryType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="记忆" min-width="300">
          <template #default="{ row }">
            <div style="font-weight: 600">{{ row.title }}</div>
            <div class="form-hint line-2" style="margin-top: 3px">{{ row.content }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light" round>{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="版本" width="70">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
        <el-table-column label="置信度" width="90">
          <template #default="{ row }">
            {{ row.confidence == null ? '—' : `${(row.confidence * 100).toFixed(0)}%` }}
          </template>
        </el-table-column>
        <el-table-column label="来源" width="100">
          <template #default="{ row }">
            <el-button v-if="row.sourceTraceId" text type="primary" :icon="Link" @click="openSource(row)">
              Trace
            </el-button>
            <span v-else class="form-hint">—</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'DRAFT'" text type="success" :icon="Check" @click="confirmMemory(row)">
              确认
            </el-button>
            <el-button v-if="row.status === 'ACTIVE'" text type="warning" :icon="Warning" @click="invalidate(row)">
              失效
            </el-button>
            <el-button text type="primary" :icon="EditPen" @click="openEdit(row)">编辑</el-button>
            <el-button text type="danger" :icon="Delete" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <div class="empty-icon"><el-icon><MagicStick /></el-icon></div>
            <div>还没有长期记忆。可以在 AI 对话中提取候选，或手动新增。</div>
          </div>
        </template>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑记忆' : '新增记忆'" width="600px">
      <el-form label-position="top">
        <el-form-item label="类型">
          <el-select v-model="form.memoryType" style="width: 100%">
            <el-option v-for="option in typeOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="form.title" maxlength="255" show-word-limit placeholder="不填时从内容截取" />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="5"
            maxlength="4000"
            show-word-limit
            placeholder="例如：订单模块使用 HikariCP，最大连接数为 30"
          />
        </el-form-item>
        <el-form-item label="摘要">
          <el-input v-model="form.summary" type="textarea" :rows="2" maxlength="2000" show-word-limit />
        </el-form-item>
        <el-form-item v-if="!editing" label="置信度">
          <el-slider v-model="form.confidence" :min="0" :max="1" :step="0.05" show-input />
        </el-form-item>
        <div class="form-hint">
          新增记忆默认为 DRAFT，需要在列表中点击“确认”后才会变为 ACTIVE 并被 Agent 检索。
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
