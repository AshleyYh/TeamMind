<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Delete, Document, EditPen, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api'
import type { KnowledgeBase } from '@/api/types'
import { useSession } from '@/composables/session'
import { formatTime } from '@/utils/format'

const router = useRouter()
const { state } = useSession()
const loading = ref(false)
const items = ref<KnowledgeBase[]>([])
const keyword = ref('')
const dialogVisible = ref(false)
const saving = ref(false)
const editing = ref<KnowledgeBase | null>(null)
const form = reactive({ name: '', description: '' })

const filtered = () => {
  const value = keyword.value.trim().toLowerCase()
  if (!value) {
    return items.value
  }
  return items.value.filter(
    (item) =>
      item.name.toLowerCase().includes(value) ||
      (item.description || '').toLowerCase().includes(value),
  )
}

async function load() {
  loading.value = true
  try {
    items.value = await api.listKnowledgeBases(state.ownerId)
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.name = ''
  form.description = ''
  dialogVisible.value = true
}

function openEdit(item: KnowledgeBase) {
  editing.value = item
  form.name = item.name
  form.description = item.description || ''
  dialogVisible.value = true
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('知识库名称不能为空')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await api.updateKnowledgeBase(editing.value.id, {
        name: form.name.trim(),
        description: form.description.trim() || undefined,
        ownerId: state.ownerId,
      })
      ElMessage.success('知识库已更新')
    } else {
      await api.createKnowledgeBase({
        name: form.name.trim(),
        description: form.description.trim() || undefined,
        ownerId: state.ownerId,
      })
      ElMessage.success('知识库已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    saving.value = false
  }
}

async function remove(item: KnowledgeBase) {
  try {
    await ElMessageBox.confirm(
      `删除知识库「${item.name}」？库内仍有文档时会被后端拒绝。`,
      '删除知识库',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch (_) {
    return
  }
  try {
    await api.deleteKnowledgeBase(item.id, state.ownerId)
    ElMessage.success('知识库已删除')
    await load()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

function openDocuments(item: KnowledgeBase) {
  router.push({ path: '/documents', query: { kbId: String(item.id) } })
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">知识库</h2>
        <p class="page-sub">按业务域组织研发文档；同名知识库在同一用户下唯一。</p>
      </div>
      <div style="display: flex; gap: 8px">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建知识库</el-button>
      </div>
    </div>

    <div class="card card-pad">
      <div style="display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-bottom: 12px">
        <el-input v-model="keyword" :prefix-icon="Search" placeholder="按名称或描述筛选" clearable style="max-width: 320px" />
        <div class="form-hint">共 {{ items.length }} 个知识库</div>
      </div>

      <el-table v-loading="loading" :data="filtered()" style="width: 100%">
        <el-table-column label="名称" min-width="180">
          <template #default="{ row }">
            <div style="display: flex; align-items: center; gap: 8px">
              <div class="metric-icon" style="width: 32px; height: 32px; border-radius: 9px; font-size: 15px">
                <el-icon><Document /></el-icon>
              </div>
              <strong>{{ row.name }}</strong>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="描述" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
        <el-table-column label="文档" width="100">
          <template #default="{ row }">
            <el-button text type="primary" @click="openDocuments(row)">管理</el-button>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="180">
          <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" :icon="EditPen" @click="openEdit(row)">编辑</el-button>
            <el-button text type="danger" :icon="Delete" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <div class="empty-icon"><el-icon><Document /></el-icon></div>
            <div>还没有知识库，先创建一个研发知识空间。</div>
          </div>
        </template>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑知识库' : '新建知识库'" width="520px">
      <el-form label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="128" show-word-limit placeholder="例如：订单系统研发知识库" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="4"
            maxlength="2000"
            show-word-limit
            placeholder="存放接口文档、设计文档、故障记录等"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
