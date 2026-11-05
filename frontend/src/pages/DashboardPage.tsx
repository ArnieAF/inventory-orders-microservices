import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { fetchOrderStatusSummary, listOrders } from '../api/orders'
import { fetchInventorySummary, listProducts } from '../api/products'
import { queryKeys } from '../api/queryKeys'
import { ORDER_STATUSES, type OrderListParams, type ProductListParams } from '../api/types'
import { useSession } from '../auth/useSession'
import { ButtonLink } from '../components/Button'
import { cx } from '../components/cx'
import { ErrorState, Loading, PageHeader, Panel, Skeleton } from '../components/Feedback'
import { ArrowUpRightIcon, CheckCircleIcon, PlusIcon, ReceiptIcon } from '../components/icons'
import { OrderStatusBadge } from '../components/StatusBadge'
import { ORDER_STATUS_STYLE } from '../components/statusStyles'
import { formatDateTime, formatInteger, formatMoney, formatRelative } from '../domain/format'
import { ORDER_STATUS_LABEL } from '../domain/orderStatus'
import { useDocumentTitle } from '../hooks/useDocumentTitle'
import { useNow } from '../hooks/useNow'

const LOW_STOCK_PARAMS: ProductListParams = { lowStock: true, page: 0, size: 5, sort: 'stock,asc' }
const RECENT_PARAMS: OrderListParams = { page: 0, size: 5, sort: 'createdAt,desc' }

export function DashboardPage() {
  useDocumentTitle('Panel')
  const { user } = useSession()
  return (
    <>
      <PageHeader
        title="Panel"
        description={
          <>
            Sesión de <span className="font-medium text-ink [overflow-wrap:anywhere]">{user.username}</span>. Datos al
            momento; se actualizan solos con cada aviso.
          </>
        }
      />
      <div className="grid grid-cols-1 gap-6">
        <InventoryFigures />
        <div className="grid grid-cols-1 items-start gap-6 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
          <OrdersByStatus />
          <LowStockList />
        </div>
        <RecentOrders />
      </div>
    </>
  )
}

function SectionHeading({ id, title, link }: { id: string; title: string; link?: { to: string; label: string } }) {
  return (
    <div className="flex items-center justify-between gap-3 px-5 pt-5 pb-3">
      <h2 id={id} className="title-label text-base text-ink">
        {title}
      </h2>
      {link ? (
        <Link
          to={link.to}
          className="-mr-2 inline-flex h-9 items-center gap-1 rounded-lg px-2 text-sm font-medium text-accent-text hover:bg-sunken pointer-coarse:h-11"
        >
          {link.label}
          <ArrowUpRightIcon aria-hidden className="size-3.5" weight="bold" />
        </Link>
      ) : null}
    </div>
  )
}

function InventoryFigures() {
  const query = useQuery({ queryKey: queryKeys.products.summary, queryFn: ({ signal }) => fetchInventorySummary(signal) })

  return (
    <Panel labelledBy="inventory-title">
      <SectionHeading id="inventory-title" title="Inventario" link={{ to: '/products', label: 'Productos' }} />
      {query.isPending ? (
        <Loading label="Cargando inventario…" className="grid grid-cols-1 gap-px px-5 pb-5 sm:grid-cols-3">
          {[0, 1, 2].map((key) => (
            <div key={key} className="space-y-2 py-2">
              <Skeleton className="h-3 w-24" />
              <Skeleton className="h-8 w-28" />
            </div>
          ))}
        </Loading>
      ) : query.isError ? (
        <ErrorState className="py-8" error={query.error} onRetry={() => void query.refetch()} retrying={query.isRefetching} />
      ) : (
        <dl className="grid grid-cols-1 divide-line px-5 pb-5 max-sm:divide-y sm:grid-cols-3 sm:divide-x">
          <Figure label="Productos activos" value={formatInteger(query.data.totalProducts)} first />
          <Figure label="Unidades en stock" value={formatInteger(query.data.totalUnits)} />
          <Figure
            label="Con stock bajo"
            value={formatInteger(query.data.lowStockCount)}
            tone={query.data.lowStockCount > 0 ? 'warn' : 'ok'}
            action={
              query.data.lowStockCount > 0 ? (
                <Link
                  to="/products?lowStock=true"
                  className="text-sm font-medium text-warn-text underline underline-offset-4 hover:no-underline"
                >
                  Revisar
                </Link>
              ) : (
                <span className="inline-flex items-center gap-1 text-sm text-ok-text">
                  <CheckCircleIcon aria-hidden className="size-4" weight="bold" />
                  Todo por encima del aviso
                </span>
              )
            }
          />
        </dl>
      )}
    </Panel>
  )
}

function Figure({
  label,
  value,
  tone,
  action,
  first,
}: {
  label: string
  value: string
  tone?: 'warn' | 'ok'
  action?: ReactNode
  first?: boolean
}) {
  return (
    <div className={cx('min-w-0 py-3 sm:py-1', !first && 'sm:pl-6')}>
      <dt className="text-sm text-ink-2">{label}</dt>
      <dd className="mt-1 flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <span
          className={cx(
            'title-label text-3xl tabular-nums [overflow-wrap:anywhere]',
            tone === 'warn' ? 'text-warn-text' : 'text-ink',
          )}
        >
          {value}
        </span>
        {action}
      </dd>
    </div>
  )
}

function OrdersByStatus() {
  const query = useQuery({ queryKey: queryKeys.orders.summary, queryFn: ({ signal }) => fetchOrderStatusSummary(signal) })

  return (
    <Panel labelledBy="orders-status-title">
      <SectionHeading id="orders-status-title" title="Pedidos por estado" link={{ to: '/orders', label: 'Pedidos' }} />
      {query.isPending ? (
        <Loading label="Cargando pedidos…" className="space-y-4 px-5 pb-5">
          <Skeleton className="h-2.5 w-full rounded-full" />
          {[0, 1, 2, 3, 4].map((key) => (
            <Skeleton key={key} className="h-5 w-full" />
          ))}
        </Loading>
      ) : query.isError ? (
        <ErrorState className="py-8" error={query.error} onRetry={() => void query.refetch()} retrying={query.isRefetching} />
      ) : query.data.total === 0 ? (
        <div className="px-5 pb-6">
          <p className="text-sm text-ink-2">Todavía no hay pedidos. Cuando crees el primero verás aquí cómo avanzan.</p>
          <ButtonLink to="/orders/new" className="mt-4" icon={<PlusIcon aria-hidden className="size-4" weight="bold" />}>
            Crear pedido
          </ButtonLink>
        </div>
      ) : (
        <div className="px-5 pb-5">
          <p className="text-sm text-ink-2">
            <span className="title-label mr-1.5 text-2xl text-ink tabular-nums">{formatInteger(query.data.total)}</span>
            {query.data.total === 1 ? 'pedido en total' : 'pedidos en total'}
          </p>
          <div aria-hidden className="mt-4 flex h-2.5 gap-0.5 overflow-hidden rounded-full bg-sunken">
            {ORDER_STATUSES.map((status) => {
              const count = query.data.byStatus[status] ?? 0
              return count > 0 ? (
                <span
                  key={status}
                  className={cx('min-w-1.5 transition-[flex-grow] duration-500 ease-out-expo', ORDER_STATUS_STYLE[status].mark)}
                  style={{ flexGrow: count }}
                />
              ) : null
            })}
          </div>
          <ul className="mt-4 divide-y divide-line">
            {ORDER_STATUSES.map((status) => {
              const count = query.data.byStatus[status] ?? 0
              const { mark } = ORDER_STATUS_STYLE[status]
              return (
                <li key={status}>
                  <Link
                    to={`/orders?status=${status}`}
                    className="-mx-2 flex h-11 items-center gap-3 rounded-lg px-2 text-sm transition-colors hover:bg-sunken"
                  >
                    <span aria-hidden className={cx('size-2.5 shrink-0 rounded-full', mark)} />
                    <span className="flex-1 text-ink">{ORDER_STATUS_LABEL[status]}</span>
                    <span className="font-semibold text-ink tabular-nums">{formatInteger(count)}</span>
                    <span className="w-12 text-right text-xs text-ink-3 tabular-nums">
                      {query.data.total > 0 ? `${Math.round((count / query.data.total) * 100)} %` : ''}
                    </span>
                  </Link>
                </li>
              )
            })}
          </ul>
        </div>
      )}
    </Panel>
  )
}

function LowStockList() {
  const query = useQuery({
    queryKey: queryKeys.products.list(LOW_STOCK_PARAMS),
    queryFn: ({ signal }) => listProducts(LOW_STOCK_PARAMS, signal),
  })
  const data = query.data

  return (
    <Panel labelledBy="low-stock-title">
      <SectionHeading
        id="low-stock-title"
        title="Necesitan reposición"
        link={data && data.totalElements > 0 ? { to: '/products?lowStock=true', label: 'Ver todos' } : undefined}
      />
      {query.isPending ? (
        <Loading label="Cargando productos con stock bajo…" className="space-y-3 px-5 pb-5">
          {[0, 1, 2].map((key) => (
            <Skeleton key={key} className="h-10 w-full" />
          ))}
        </Loading>
      ) : query.isError ? (
        <ErrorState className="py-8" error={query.error} onRetry={() => void query.refetch()} retrying={query.isRefetching} />
      ) : query.data.content.length === 0 ? (
        <p className="flex items-start gap-2 px-5 pb-6 text-sm text-ink-2">
          <CheckCircleIcon aria-hidden className="mt-0.5 size-4 shrink-0 text-ok-text" weight="bold" />
          Ningún producto está en su aviso de stock bajo.
        </p>
      ) : (
        <ul className="divide-y divide-line border-t border-line">
          {query.data.content.map((product) => (
            <li key={product.id} className="flex items-center gap-3 px-5 py-3">
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-ink" title={product.name}>
                  {product.name}
                </p>
                <p className="code text-xs text-ink-3">{product.sku}</p>
              </div>
              <p className="text-right text-sm tabular-nums">
                <span className={cx('font-semibold', product.stock <= 0 ? 'text-danger-text' : 'text-warn-text')}>
                  {formatInteger(product.stock)}
                </span>
                <span className="text-ink-3"> / {formatInteger(product.lowStockThreshold)}</span>
              </p>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  )
}

function RecentOrders() {
  const now = useNow()
  const query = useQuery({
    queryKey: queryKeys.orders.list(RECENT_PARAMS),
    queryFn: ({ signal }) => listOrders(RECENT_PARAMS, signal),
  })
  const data = query.data

  return (
    <Panel labelledBy="recent-title">
      <SectionHeading
        id="recent-title"
        title="Pedidos recientes"
        link={data && data.totalElements > 0 ? { to: '/orders', label: 'Ver todos' } : undefined}
      />
      {query.isPending ? (
        <Loading label="Cargando pedidos recientes…" className="space-y-3 px-5 pb-5">
          {[0, 1, 2].map((key) => (
            <Skeleton key={key} className="h-12 w-full" />
          ))}
        </Loading>
      ) : query.isError ? (
        <ErrorState className="py-8" error={query.error} onRetry={() => void query.refetch()} retrying={query.isRefetching} />
      ) : query.data.content.length === 0 ? (
        <div className="flex items-center gap-3 px-5 pb-6 text-sm text-ink-2">
          <ReceiptIcon aria-hidden className="size-5 shrink-0 text-ink-3" />
          Aquí aparecerán los últimos pedidos en cuanto se creen.
        </div>
      ) : (
        <ul className="divide-y divide-line border-t border-line">
          {query.data.content.map((order) => (
            <li key={order.id} className="relative flex flex-wrap items-center gap-x-4 gap-y-1 px-5 py-3.5 hover:bg-sunken/60">
              <div className="min-w-0 flex-1 basis-48">
                <Link
                  to={`/orders/${encodeURIComponent(order.id)}`}
                  className="block truncate text-sm font-semibold text-ink after:absolute after:inset-0 hover:underline"
                  title={order.customerName}
                >
                  {order.customerName}
                </Link>
                <p className="text-xs text-ink-3">
                  <time dateTime={order.createdAt} title={formatDateTime(order.createdAt)}>
                    {formatRelative(order.createdAt, now)}
                  </time>{' '}
                  por <span className="[overflow-wrap:anywhere]">{order.createdByUsername}</span>
                </p>
              </div>
              <OrderStatusBadge status={order.status} />
              <p className="w-32 text-right text-sm font-semibold text-ink tabular-nums max-sm:w-auto">{formatMoney(order.total)}</p>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  )
}
