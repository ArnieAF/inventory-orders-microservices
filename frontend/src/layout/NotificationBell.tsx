import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useId, useRef, useState } from 'react'
import { Link } from 'react-router'
import { queryKeys } from '../api/queryKeys'
import type { Notification } from '../api/types'
import { Button } from '../components/Button'
import { cx } from '../components/cx'
import { ErrorState, Skeleton } from '../components/Feedback'
import {
  ArrowsClockwiseIcon,
  BellIcon,
  CheckIcon,
  ChecksIcon,
  ReceiptIcon,
  TrayIcon,
  WarningIcon,
  WifiHighIcon,
  WifiSlashIcon,
  type Icon,
} from '../components/icons'
import { formatBadgeCount, formatDateTime, formatRelative } from '../domain/format'
import { useNow } from '../hooks/useNow'
import { usePopover } from '../hooks/usePopover'
import { useNotificationCenterState } from '../realtime/center'
import { NOTIFICATION_TYPE_LABEL, notificationHref, type ConnectionStatus } from '../realtime/notificationCenter'
import {
  useMarkAllNotificationsRead,
  useMarkNotificationRead,
  useNotificationHistory,
  useUnreadCountSync,
} from '../realtime/useNotifications'

const TYPE_ICON: Record<Notification['type'], { icon: Icon; tone: string }> = {
  NEW_ORDER: { icon: ReceiptIcon, tone: 'bg-info-soft text-info-text' },
  LOW_STOCK: { icon: WarningIcon, tone: 'bg-warn-soft text-warn-text' },
  ORDER_STATUS_CHANGED: { icon: ArrowsClockwiseIcon, tone: 'bg-ship-soft text-ship-text' },
}

export function NotificationBell() {
  const { unreadCount, connection } = useNotificationCenterState()
  const countQuery = useUnreadCountSync(connection !== 'online')
  const { open, toggle, close, containerRef, triggerRef } = usePopover()
  const panelId = useId()
  const count = unreadCount ?? countQuery.data?.count ?? 0
  const label = count > 0 ? `Notificaciones, ${count} sin leer` : 'Notificaciones'

  return (
    <div ref={containerRef} className="relative">
      <button
        ref={triggerRef}
        type="button"
        onClick={toggle}
        aria-label={label}
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        title="Notificaciones"
        className={cx(
          'relative grid size-10 place-items-center rounded-lg text-ink-2 transition-colors hover:bg-sunken hover:text-ink pointer-coarse:size-11',
          open && 'bg-sunken text-ink',
        )}
      >
        <BellIcon aria-hidden className="size-5" weight={count > 0 ? 'fill' : 'regular'} />
        {count > 0 ? (
          <span
            aria-hidden
            className="absolute -top-0.5 -right-0.5 grid h-5 min-w-5 place-items-center rounded-full bg-accent px-1 text-[0.6875rem] leading-none font-bold text-accent-ink ring-2 ring-canvas tabular-nums"
          >
            {formatBadgeCount(count)}
          </span>
        ) : null}
      </button>
      {open ? (
        <NotificationPanel id={panelId} connection={connection} unreadCount={count} onNavigate={() => close()} />
      ) : null}
    </div>
  )
}

interface PanelProps {
  id: string
  connection: ConnectionStatus
  unreadCount: number
  onNavigate: () => void
}

function NotificationPanel({ id, connection, unreadCount, onNavigate }: PanelProps) {
  const [unreadOnly, setUnreadOnly] = useState(false)
  const history = useNotificationHistory(unreadOnly)
  const markRead = useMarkNotificationRead()
  const markAll = useMarkAllNotificationsRead()
  const queryClient = useQueryClient()
  const headingRef = useRef<HTMLHeadingElement>(null)
  const now = useNow(30_000)

  useEffect(() => {
    headingRef.current?.focus()
    // Al abrir, re-sincroniza el contador con el servidor.
    void queryClient.invalidateQueries({ queryKey: queryKeys.notifications.unreadCount })
  }, [queryClient])

  return (
    <div
      id={id}
      role="region"
      aria-labelledby={`${id}-title`}
      className="absolute top-full right-0 z-50 mt-2 flex max-h-[min(36rem,calc(100dvh-5.5rem))] w-[min(26rem,calc(100vw-1.5rem))] origin-top-right flex-col overflow-hidden rounded-xl bg-raised shadow-pop ring-1 ring-line motion-safe:animate-pop-in"
    >
      <div className="flex items-center justify-between gap-3 px-4 pt-4 pb-2">
        <h2 id={`${id}-title`} ref={headingRef} tabIndex={-1} data-focus-target className="text-base font-semibold text-ink">
          Notificaciones
        </h2>
        <Button
          size="sm"
          variant="ghost"
          onClick={() => markAll.mutate()}
          disabled={unreadCount === 0 || markAll.isPending}
          icon={<ChecksIcon aria-hidden className="size-4" />}
        >
          Marcar todas como leídas
        </Button>
      </div>

      <div className="flex gap-1 px-4 pb-3" role="group" aria-label="Filtrar notificaciones">
        {[
          { value: false, text: 'Todas' },
          { value: true, text: 'Sin leer' },
        ].map((option) => (
          <button
            key={option.text}
            type="button"
            aria-pressed={unreadOnly === option.value}
            onClick={() => setUnreadOnly(option.value)}
            className={cx(
              'h-8 rounded-full px-3 text-sm font-medium transition-colors pointer-coarse:h-11',
              unreadOnly === option.value ? 'bg-ink text-canvas' : 'text-ink-2 hover:bg-sunken hover:text-ink',
            )}
          >
            {option.text}
          </button>
        ))}
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain border-t border-line">
        {history.isPending ? (
          <div role="status" aria-live="polite" className="divide-y divide-line">
            <span className="sr-only">Cargando notificaciones…</span>
            {[0, 1, 2].map((key) => (
              <div key={key} className="flex gap-3 px-4 py-3.5">
                <Skeleton className="size-8 shrink-0 rounded-lg" />
                <div className="flex-1 space-y-2 pt-1">
                  <Skeleton className="h-3.5 w-2/3" />
                  <Skeleton className="h-3 w-full" />
                </div>
              </div>
            ))}
          </div>
        ) : history.isError ? (
          <ErrorState
            className="py-8"
            error={history.error}
            title="No se pudieron cargar"
            onRetry={() => void history.refetch()}
            retrying={history.isRefetching}
          />
        ) : history.items.length === 0 ? (
          <div className="flex flex-col items-center px-6 py-10 text-center">
            <span className="grid size-11 place-items-center rounded-xl bg-sunken text-ink-2">
              <TrayIcon aria-hidden className="size-5" />
            </span>
            <p className="mt-3 text-sm font-semibold text-ink">
              {unreadOnly ? 'Estás al día' : 'Sin notificaciones todavía'}
            </p>
            <p className="mt-1 max-w-[36ch] text-sm text-ink-2">
              {unreadOnly
                ? 'No tienes notificaciones sin leer.'
                : 'Aquí verás los pedidos nuevos, los avisos de stock bajo y los cambios de estado.'}
            </p>
          </div>
        ) : (
          <ul className="divide-y divide-line">
            {history.items.map((item) => (
              <NotificationRow
                key={item.id}
                item={item}
                now={now}
                onOpen={() => {
                  if (!item.read) markRead.mutate(item)
                  onNavigate()
                }}
                onMarkRead={() => markRead.mutate(item)}
              />
            ))}
          </ul>
        )}
        {history.hasNextPage ? (
          <div className="border-t border-line p-3">
            <Button
              size="sm"
              className="w-full"
              onClick={() => void history.fetchNextPage()}
              loading={history.isFetchingNextPage}
            >
              Cargar más
            </Button>
          </div>
        ) : null}
      </div>

      <ConnectionLine status={connection} />
    </div>
  )
}

function NotificationRow({
  item,
  now,
  onOpen,
  onMarkRead,
}: {
  item: Notification
  now: number
  onOpen: () => void
  onMarkRead: () => void
}) {
  const { icon: TypeIcon, tone } = TYPE_ICON[item.type]
  return (
    <li className={cx('group relative flex gap-3 px-4 py-3.5', !item.read && 'bg-accent-soft/40')}>
      <span className={cx('grid size-8 shrink-0 place-items-center rounded-lg', tone)}>
        <TypeIcon aria-hidden className="size-4" weight="bold" />
      </span>
      <div className="min-w-0 flex-1">
        <Link
          to={notificationHref(item)}
          onClick={onOpen}
          className="block rounded-sm text-sm font-semibold text-ink [overflow-wrap:anywhere] after:absolute after:inset-0 hover:underline focus-visible:outline-offset-4"
        >
          <span className="sr-only">{item.read ? '' : 'Sin leer. '}{NOTIFICATION_TYPE_LABEL[item.type]}: </span>
          {item.title || NOTIFICATION_TYPE_LABEL[item.type]}
        </Link>
        {item.message ? (
          <p className="mt-0.5 line-clamp-3 text-sm leading-snug text-ink-2 [overflow-wrap:anywhere]">{item.message}</p>
        ) : null}
        <p className="mt-1 text-xs text-ink-3">
          <time dateTime={item.createdAt} title={formatDateTime(item.createdAt)}>
            {formatRelative(item.createdAt, now)}
          </time>
        </p>
      </div>
      {!item.read ? (
        <button
          type="button"
          onClick={onMarkRead}
          aria-label="Marcar como leída"
          title="Marcar como leída"
          className="relative z-10 -mr-1 grid size-9 shrink-0 place-items-center self-start rounded-lg text-accent-text transition-colors hover:bg-sunken pointer-coarse:size-11"
        >
          <CheckIcon aria-hidden className="size-4" weight="bold" />
        </button>
      ) : null}
    </li>
  )
}

function ConnectionLine({ status }: { status: ConnectionStatus }) {
  const online = status === 'online'
  const text =
    status === 'online'
      ? 'Recibiendo avisos en tiempo real'
      : status === 'offline'
        ? 'Sin conexión en tiempo real. Reintentando…'
        : 'Conectando…'
  const StatusIcon = online ? WifiHighIcon : WifiSlashIcon
  return (
    <p
      className={cx(
        'flex items-center gap-2 border-t border-line px-4 py-2.5 text-xs',
        online ? 'text-ink-3' : 'text-warn-text',
      )}
    >
      <StatusIcon aria-hidden className="size-4 shrink-0" />
      {text}
    </p>
  )
}
