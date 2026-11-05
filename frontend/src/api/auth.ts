import { api } from './http'
import type { AuthResponse, LoginRequest, RegisterRequest, User } from './types'

export function login(body: LoginRequest): Promise<AuthResponse> {
  return api.request<AuthResponse>('/api/auth/login', { method: 'POST', body, auth: false })
}

export function register(body: RegisterRequest): Promise<AuthResponse> {
  return api.request<AuthResponse>('/api/auth/register', { method: 'POST', body, auth: false })
}

export function fetchMe(signal?: AbortSignal): Promise<User> {
  return api.request<User>('/api/auth/me', signal ? { signal } : {})
}
