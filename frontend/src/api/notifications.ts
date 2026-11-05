import { api } from './http'
import type { Notification, NotificationListParams, Page, UnreadCount } from './types'

export function listNotifications(params: NotificationListParams, signal?: AbortSignal): Promise<Page<Notification>> {
  return api.request<Page<Notification>>('/api/notifications', {
    query: { unreadOnly: params.unreadOnly ? true : undefined, page: params.page, size: params.size },
    ...(signal ? { signal } : {}),
  })
}

export function fetchUnreadCount(signal?: AbortSignal): Promise<UnreadCount> {
  return api.request<UnreadCount>('/api/notifications/unread-count', signal ? { signal } : {})
}

export function markNotificationRead(notificationId: string): Promise<void> {
  return api.request<void>(`/api/notifications/${encodeURIComponent(notificationId)}/read`, { method: 'POST' })
}

export function markAllNotificationsRead(): Promise<void> {
  return api.request<void>('/api/notifications/read-all', { method: 'POST' })
}
