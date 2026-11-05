import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router'
import { fetchOrderStatusSummary, listOrders } from '../../api/orders'
import { queryKeys } from '../../api/queryKeys'
import {
  ORDER_SORT_FIELDS,
  ORDER_STATUSES,
  type OrderListParams,
  type OrderSort,
  type OrderStatus,
  type OrderSummary,
} from '../../api/types'
import { Button, ButtonLink } from '../../components/Button'
import { cx } from '../../components/cx'
import { SelectField } from '../../components/Field'
import { EmptyState, ErrorState, Loading, PageHeader, Panel, Skeleton } from '../../components/Feedback'
import { PlusIcon, ReceiptIcon, XIcon } from '../../components/icons'
import { Pagination } from '../../components/Pagination'
import { OrderStatusBadge } from '../../components/StatusBadge'
import { formatDate, formatDateTime, formatInteger, formatMoney, shortId } from '../../domain/format'
import { ORDER_STATUS_LABEL } from '../../domain/orderStatus'
import { useDocumentTitle } from '../../hooks/useDocumentTitle'

export const ORDERS_PAGE_SIZE = 20

const SORT_OPTIONS: ReadonlyArray<{ value: OrderSort; label: string }> = [
  { value: 'createdAt,desc', label: 'Más recientes' },
  { value: 'createdAt,asc', label: 'Más antiguos' },
  { value: 'total,desc', label: 'Importe más alto' },
  { value: 'total,asc', label: 'Importe más bajo' },
  { value: 'customerName,asc', label: 'Cliente (A-Z)' },
  { value: 'status,asc', label: 'Estado' },
]

function parseSort(value: string | null): OrderSort {
  if (!value) return 'createdAt,desc'
  const [field, direction] = value.split(',')
  const ok = (ORDER_SORT_FIELDS as readonly string[]).includes(field ?? '') && (direction === 'asc' || direction === 'desc')
  return ok ? (value as OrderSort) : 'createdAt,desc'
}

function parseStatus(value: string | null): OrderStatus | null {
  return value && (ORDER_STATUSES as readonly string[]).includes(value) ? (value as OrderStatus) : null
}

function parsePage(value: string | null): number {
  const page = Number.parseInt(value ?? '1', 10)
  return Number.isFinite(page) && page >= 1 ? page - 1 : 0
}

export function OrdersPage() {
  useDocumentTitle('Pedidos')
  const [params, setParams] = useSearchParams()
  const status = parseStatus(params.get('status'))
  const sort = parseSort(params.get('sort'))
  const page = parsePage(params.get('page'))

  function updateParams(changes: Record<string, string | null>) {
    setParams((current) => {
      const next = new URLSearchParams(current)
      for (const [key, value] of Object.entries(changes)) {
        if (value === null) next.delete(key)
        else next.set(key, value)
      }
      return next
    })
  }

  const listParams: OrderListParams = { ...(status ? { status } : {}), page, size: ORDERS_PAGE_SIZE, sort }
  const query = useQuery({
    queryKey: queryKeys.orders.list(listParams),
    queryFn: ({ signal }) => listOrders(listParams, signal),
    placeholderData: keepPreviousData,
  })
  const summary = useQuery({
    queryKey: queryKeys.orders.summary,
    queryFn: ({ signal }) => fetchOrderStatusSummary(signal),
  })

  const data = query.data
  const filters: ReadonlyArray<{ value: OrderStatus | null; label: string; count: number | undefined }> = [
    { value: null, label: 'Todos', count: summary.data?.total },
    ...ORDER_STATUSES.map((value) => ({ value, label: ORDER_STATUS_LABEL[value], count: summary.data?.byStatus[value] })),
  ]

  return (
    <>
      <PageHeader
        title="Pedidos"
        description="Cada pedido reserva stock al crearse. Cancelarlo lo devuelve al inventario."
        actions={
          <ButtonLink to="/orders/new" variant="primary" icon={<PlusIcon aria-hidden className="size-4" weight="bold" />}>
            Nuevo pedido
          </ButtonLink>
        }
      />

      <div className="mb-4 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <div
          role="group"
          aria-label="Filtrar por estado"
          className="-mx-4 flex gap-1.5 overflow-x-auto px-4 pb-1 [scrollbar-width:none] sm:mx-0 sm:flex-wrap sm:px-0"
        >
          {filters.map((filter) => {
            const active = status === filter.value
            return (
              <button
                key={filter.label}
                type="button"
                aria-pressed={active}
                onClick={() => updateParams({ status: filter.value, page: null })}
                className={cx(
                  'flex h-9 shrink-0 items-center gap-2 rounded-full px-3.5 text-sm font-medium whitespace-nowrap transition-colors pointer-coarse:h-11',
                  active ? 'bg-ink text-canvas' : 'bg-surface text-ink-2 ring-1 ring-line ring-inset hover:text-ink',
                )}
              >
                {filter.label}
                {filter.count !== undefined ? (
                  <span className={cx('text-xs tabular-nums', active ? 'opacity-75' : 'text-ink-3')}>
                    {formatInteger(filter.count)}
                  </span>
                ) : null}
              </button>
            )
          })}
        </div>
        <SelectField
          label="Ordenar pedidos"
          hideLabel
          value={sort}
          onChange={(event) => updateParams({ sort: event.target.value, page: null })}
          containerClassName="sm:w-56"
        >
          {SORT_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </SelectField>
      </div>

      <Panel labelledBy="orders-table-caption" className="overflow-hidden">
        {query.isPending ? (
          <OrdersSkeleton />
        ) : query.isError && !data ? (
          <ErrorState error={query.error} onRetry={() => void query.refetch()} retrying={query.isRefetching} />
        ) : data && data.content.length === 0 ? (
          status ? (
            <EmptyState
              icon={ReceiptIcon}
              title={`No hay pedidos en «${ORDER_STATUS_LABEL[status]}»`}
              description="Cuando un pedido llegue a este estado aparecerá aquí."
              action={
                <Button onClick={() => updateParams({ status: null, page: null })} icon={<XIcon aria-hidden className="size-4" />}>
                  Ver todos
                </Button>
              }
            />
          ) : (
            <EmptyState
              icon={ReceiptIcon}
              title="Aún no hay pedidos"
              description="Crea el primero: eliges productos y cantidades, y el stock se reserva al confirmar."
              action={
                <ButtonLink to="/orders/new" variant="primary" icon={<PlusIcon aria-hidden className="size-4" weight="bold" />}>
                  Nuevo pedido
                </ButtonLink>
              }
            />
          )
        ) : data ? (
          <OrderTable orders={data.content} dimmed={query.isPlaceholderData} />
        ) : null}
      </Panel>

      {data && data.totalElements > 0 ? (
        <div className="mt-4">
          <Pagination
            page={page}
            totalPages={data.totalPages}
            totalElements={data.totalElements}
            noun={['pedido', 'pedidos']}
            onPageChange={(next) => updateParams({ page: next > 0 ? String(next + 1) : null })}
            busy={query.isFetching}
          />
        </div>
      ) : null}
    </>
  )
}

function OrderTable({ orders, dimmed }: { orders: readonly OrderSummary[]; dimmed: boolean }) {
  return (
    <table
      role="table"
      className={cx('w-full text-sm transition-opacity', dimmed && 'opacity-60')}
      aria-busy={dimmed || undefined}
    >
      <caption id="orders-table-caption" className="sr-only">
        Pedidos
      </caption>
      <thead className="max-md:sr-only">
        <tr role="row" className="border-b border-line text-left text-xs text-ink-2">
          <th role="columnheader" scope="col" className="py-3 pr-3 pl-5 font-semibold">
            Pedido
          </th>
          <th role="columnheader" scope="col" className="px-3 py-3 font-semibold">
            Estado
          </th>
          <th role="columnheader" scope="col" className="px-3 py-3 text-right font-semibold">
            Artículos
          </th>
          <th role="columnheader" scope="col" className="px-3 py-3 text-right font-semibold">
            Total
          </th>
          <th role="columnheader" scope="col" className="py-3 pr-5 pl-3 text-right font-semibold">
            Creado
          </th>
        </tr>
      </thead>
      <tbody className="divide-y divide-line">
        {orders.map((order) => (
          <tr
            key={order.id}
            role="row"
            className="relative grid grid-cols-[minmax(0,1fr)_auto] gap-x-3 gap-y-1.5 px-4 py-4 md:table-row md:p-0 md:hover:bg-sunken/60"
          >
            <th role="rowheader" scope="row" className="min-w-0 text-left font-normal md:py-3.5 md:pr-3 md:pl-5">
              <Link
                to={`/orders/${encodeURIComponent(order.id)}`}
                className="line-clamp-2 font-semibold text-ink [overflow-wrap:anywhere] after:absolute after:inset-0 hover:underline"
              >
                {order.customerName}
              </Link>
              <span className="code mt-0.5 block text-xs text-ink-3">#{shortId(order.id)}</span>
            </th>
            <td role="cell" className="justify-self-end md:table-cell md:px-3 md:py-3.5">
              <OrderStatusBadge status={order.status} />
            </td>
            <td role="cell" className="text-ink-2 tabular-nums md:table-cell md:px-3 md:py-3.5 md:text-right">
              {formatInteger(order.itemCount)} {order.itemCount === 1 ? 'artículo' : 'artículos'}
            </td>
            <td role="cell" className="text-right font-semibold whitespace-nowrap text-ink tabular-nums md:table-cell md:px-3 md:py-3.5">
              {formatMoney(order.total)}
            </td>
            <td role="cell" className="col-span-2 text-xs text-ink-3 md:table-cell md:py-3.5 md:pr-5 md:pl-3 md:text-right">
              <time dateTime={order.createdAt} title={formatDateTime(order.createdAt)} className="whitespace-nowrap text-ink-2">
                {formatDate(order.createdAt)}
              </time>
              <span className="block truncate [overflow-wrap:anywhere] md:max-w-40 md:ml-auto">por {order.createdByUsername}</span>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function OrdersSkeleton() {
  return (
    <Loading label="Cargando pedidos…" className="divide-y divide-line">
      {[0, 1, 2, 3, 4].map((key) => (
        <div key={key} className="flex items-center gap-4 px-5 py-4">
          <div className="flex-1 space-y-2">
            <Skeleton className="h-4 w-2/5 max-w-56" />
            <Skeleton className="h-3 w-20" />
          </div>
          <Skeleton className="h-6 w-24 rounded-full" />
          <Skeleton className="hidden h-4 w-16 sm:block" />
          <Skeleton className="h-4 w-20" />
        </div>
      ))}
    </Loading>
  )
}
