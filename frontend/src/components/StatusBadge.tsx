import type { OrderStatus } from '../api/types'
import { ORDER_STATUS_LABEL } from '../domain/orderStatus'
import { cx } from './cx'
import { WarningIcon } from './icons'
import { ORDER_STATUS_STYLE } from './statusStyles'

const pill = 'inline-flex h-6 items-center gap-1 rounded-full pr-2.5 pl-2 text-xs font-semibold whitespace-nowrap'

export function OrderStatusBadge({ status, className }: { status: OrderStatus; className?: string }) {
  const { badge, icon: StatusIcon } = ORDER_STATUS_STYLE[status]
  return (
    <span className={cx(pill, badge, className)}>
      <StatusIcon aria-hidden className="size-3.5" weight="bold" />
      {ORDER_STATUS_LABEL[status]}
    </span>
  )
}

export function LowStockBadge({ stock }: { stock: number }) {
  const empty = stock <= 0
  return (
    <span className={cx(pill, empty ? 'bg-danger-soft text-danger-text' : 'bg-warn-soft text-warn-text')}>
      <WarningIcon aria-hidden className="size-3.5" weight="bold" />
      {empty ? 'Agotado' : 'Stock bajo'}
    </span>
  )
}
