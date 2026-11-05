import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type InfiniteData,
  type QueryClient,
} from '@tanstack/react-query'
import { useEffect, useMemo } from 'react'
import { markAllNotificationsRead, markNotificationRead, fetchUnreadCount, listNotifications } from '../api/notifications'
import { queryKeys } from '../api/queryKeys'
import type { Notification, Page } from '../api/types'
import { notificationCenter, showToast } from './center'

export const NOTIFICATION_PAGE_SIZE = 20

type NotificationPages = InfiniteData<Page<Notification>, number>

/** Contador de no leídas: valor del servidor volcado al almacén (que suma los eventos en vivo). */
export function useUnreadCountSync(pollWhenOffline: boolean) {
  const query = useQuery({
    queryKey: queryKeys.notifications.unreadCount,
    queryFn: ({ signal }) => fetchUnreadCount(signal),
    // Sin WebSocket se consulta cada minuto como respaldo.
    refetchInterval: pollWhenOffline ? 60_000 : false,
  })
  const count = query.data?.count
  useEffect(() => {
    if (typeof count === 'number') notificationCenter.setUnreadCount(count)
  }, [count, query.dataUpdatedAt])
  return query
}

export function useNotificationHistory(unreadOnly: boolean) {
  const query = useInfiniteQuery({
    queryKey: queryKeys.notifications.list(unreadOnly),
    queryFn: ({ pageParam, signal }) =>
      listNotifications({ unreadOnly, page: pageParam, size: NOTIFICATION_PAGE_SIZE }, signal),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.page + 1 < last.totalPages ? last.page + 1 : undefined),
  })

  // Al paginar pueden repetirse elementos si llegaron notificaciones nuevas entre página y página.
  const items = useMemo(() => {
    const seen = new Set<string>()
    const result: Notification[] = []
    for (const page of query.data?.pages ?? []) {
      for (const item of page.content) {
        if (seen.has(item.id)) continue
        seen.add(item.id)
        result.push(item)
      }
    }
    return result
  }, [query.data])

  useEffect(() => {
    notificationCenter.rememberSeen(items.map((item) => item.id))
  }, [items])

  return { ...query, items }
}

function markInCache(queryClient: QueryClient, predicate: (item: Notification) => boolean) {
  queryClient.setQueriesData<NotificationPages>({ queryKey: ['notifications', 'list'] }, (old) =>
    old
      ? {
          ...old,
          pages: old.pages.map((page) => ({
            ...page,
            content: page.content.map((item) => (predicate(item) && !item.read ? { ...item, read: true } : item)),
          })),
        }
      : old,
  )
}

export function useMarkNotificationRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (notification: Notification) => markNotificationRead(notification.id),
    onMutate: (notification) => {
      markInCache(queryClient, (item) => item.id === notification.id)
      notificationCenter.markRead(notification.id, !notification.read)
    },
    onError: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.notifications.all })
      showToast({ tone: 'error', title: 'No se pudo marcar como leída', message: 'Inténtalo de nuevo en unos segundos.' })
    },
  })
}

export function useMarkAllNotificationsRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => markAllNotificationsRead(),
    onMutate: () => {
      markInCache(queryClient, () => true)
      notificationCenter.markAllRead()
    },
    onError: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.notifications.all })
      showToast({ tone: 'error', title: 'No se pudieron marcar como leídas', message: 'Inténtalo de nuevo en unos segundos.' })
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.notifications.unreadCount })
    },
  })
}
