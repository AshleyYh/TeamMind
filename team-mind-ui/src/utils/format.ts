export function formatTime(value?: string | null): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString('zh-CN', { hour12: false })
}

export function formatDuration(value?: number | null): string {
  if (value == null) {
    return '—'
  }
  if (value < 1000) {
    return `${value} ms`
  }
  return `${(value / 1000).toFixed(2)} s`
}

export function formatTokens(value?: number | null): string {
  if (value == null) {
    return '—'
  }
  return value.toLocaleString('zh-CN')
}

export function statusTagType(status?: string | null): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'SUCCESS' || status === 'COMPLETED' || status === 'ACTIVE') {
    return 'success'
  }
  if (status === 'FAILED' || status === 'INVALID') {
    return 'danger'
  }
  if (status === 'PROCESSING' || status === 'RUNNING' || status === 'DRAFT') {
    return 'warning'
  }
  return 'info'
}

export function asNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

export function asPercent(value: unknown): string {
  const number = asNumber(value)
  return number == null ? '—' : `${(number * 100).toFixed(1)}%`
}

export function asFixed(value: unknown, digits = 3): string {
  const number = asNumber(value)
  return number == null ? '—' : number.toFixed(digits)
}
