import axios, { type AxiosRequestConfig } from 'axios'
import type { ApiResponse } from './types'

export const http = axios.create({
  baseURL: '/api/v1',
  timeout: 180_000,
})

export class ApiError extends Error {
  readonly code: string
  readonly details?: Array<{ field: string; message: string }>

  constructor(code: string, message: string, details?: Array<{ field: string; message: string }>) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.details = details
  }
}

export async function apiRequest<T>(config: AxiosRequestConfig): Promise<T> {
  try {
    const response = await http.request<ApiResponse<T>>(config)
    const body = response.data
    if (body.code !== '0') {
      throw new ApiError(body.code, body.message || '请求失败', body.errors)
    }
    return body.data
  } catch (error) {
    if (error instanceof ApiError) {
      throw error
    }
    if (axios.isAxiosError(error)) {
      const body = error.response?.data as ApiResponse<unknown> | undefined
      const message = body?.message || error.message || '网络请求失败'
      throw new ApiError(body?.code || 'NETWORK_ERROR', message, body?.errors)
    }
    throw error
  }
}
