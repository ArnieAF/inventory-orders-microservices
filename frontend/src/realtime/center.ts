import { useSyncExternalStore } from 'react'
import { createNotificationCenter, type NotificationCenterState, type ToastItem } from './notificationCenter'

/** Estado único de notificaciones y avisos de la pestaña. */
export const notificationCenter = createNotificationCenter()

export function useNotificationCenterState(): NotificationCenterState {
  return useSyncExternalStore(notificationCenter.subscribe, notificationCenter.getState, notificationCenter.getState)
}

/** Aviso efímero (éxito de una acción, etc.). */
export function showToast(toast: Omit<ToastItem, 'id'>): string {
  return notificationCenter.pushToast(toast)
}
