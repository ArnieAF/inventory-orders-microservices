import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useParams } from 'react-router'
import { ApiError, isApiError } from '../../api/client'
import { errorMessage } from '../../api/errors'
import { fetchOrder, updateOrderStatus } from '../../api/orders'
import { queryKeys } from '../../api/queryKeys'
import type { Order, OrderStatus } from '../../api/types'
import { Button, ButtonLink } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { Banner, EmptyState, ErrorState, Loading, PageHeader, Panel, Skeleton } from '../../components/Feedback'
import { ArrowLeftIcon, ReceiptIcon, XCircleIcon } from '../../components/icons'
import { OrderStatusBadge } from '../../components/StatusBadge'
import { ORDER_STATUS_STYLE } from '../../components/statusStyles'
import { formatDateTime, formatInteger, formatMoney, shortId } from '../../domain/format'
import { ORDER_ACTION_LABEL, ORDER_STATUS_LABEL, availableActions } from '../../domain/orderStatus'
import { useDocumentTitle } from '../../hooks/useDocumentTitle'
import { showToast } from '../../realtime/center'
import { StatusTrack } from './StatusTrack'

const ACTION_ERRORS: Readonly<Record<string, string>> = {
  SERVICE_UNAVAILABLE:
    'No se pudo completar ahora: el inventario no responde. El pedido no ha cambiado; inténtalo de nuevo en unos segundos.',
  NOT_FOUND: 'Este pedido ya no existe.',
}

export function OrderDetailPage() {
  const { id = '' } = useParams()
  const title = `Pedido #${shortId(id)}`
  useDocumentTitle(title)
  const queryClient = useQueryClient()
  const [confirmCancel, setConfirmCancel] = useState(false)

  const query = useQuery({
    queryKey: queryKeys.orders.detail(id),
    queryFn: ({ signal }) => fetchOrder(id, signal),
    enabled: id.length > 0,
  })

  const mutation = useMutation({
    mutationFn: (status: OrderStatus) => updateOrderStatus(id, status),
    onSuccess: (order) => {
      queryClient.setQueryData(queryKeys.orders.detail(id), order)
      void queryClient.invalidateQueries({ queryKey: queryKeys.orders.all })
      if (order.status === 'CANCELLED') void queryClient.invalidateQueries({ queryKey: queryKeys.products.all })
      setConfirmCancel(false)
      showToast({
        tone: 'success',
        title: order.status === 'CANCELLED' ? 'Pedido cancelado' : `Pedido ${ORDER_STATUS_LABEL[order.status].toLowerCase()}`,
        message: order.status === 'CANCELLED' ? 'El stock reservado ha vuelto al inventario.' : `#${shortId(order.id)}`,
      })
    },
    onError: (error) => {
      // Otro usuario cambió el estado: se recarga para mostrar el real.
      // (`orders.all` incluye el detalle: una sola recarga.)
      if (isApiError(error, 'INVALID_STATUS_TRANSITION') || isApiError(error, 'NOT_FOUND')) {
        void queryClient.invalidateQueries({ queryKey: queryKeys.orders.all })
      }
      setConfirmCancel(false)
    },
  })

  const back = (
    <ButtonLink to="/orders" variant="ghost" size="sm" className="-ml-3" icon={<ArrowLeftIcon aria-hidden className="size-4" />}>
      Pedidos
    </ButtonLink>
  )

  if (query.isPending) {
    return (
      <>
        <PageHeader title={title} back={back} />
        <Loading label="Cargando pedido…" className="space-y-6">
          <Skeleton className="h-20 w-full rounded-xl" />
          <div className="grid grid-cols-1 gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]">
            <Skeleton className="h-64 rounded-xl" />
            <Skeleton className="h-48 rounded-xl" />
          </div>
        </Loading>
      </>
    )
  }

  if (query.isError) {
    const notFound = query.error instanceof ApiError && (query.error.code === 'NOT_FOUND' || query.error.status === 400)
    return (
      <>
        <PageHeader title={title} back={back} />
        <Panel>
          {notFound ? (
            <EmptyState
              icon={ReceiptIcon}
              title="No encontramos este pedido"
              description="Puede que el enlace sea incorrecto. Vuelve a la lista para buscarlo."
              action={<ButtonLink to="/orders">Ver pedidos</ButtonLink>}
            />
          ) : (
            <ErrorState error={query.error} onRetry={() => void query.refetch()} retrying={query.isRefetching} />
          )}
        </Panel>
      </>
    )
  }

  const order = query.data
  const actions = availableActions(order.status)
  const pendingStatus = mutation.isPending ? mutation.variables : undefined

  return (
    <>
      <PageHeader
        title={title}
        back={back}
        description={
          <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
            <OrderStatusBadge status={order.status} />
            <span>Actualizado {formatDateTime(order.updatedAt)}</span>
          </span>
        }
        actions={
          actions.forward || actions.cancel ? (
            <>
              {actions.cancel ? (
                <Button
                  variant="danger-ghost"
                  onClick={() => setConfirmCancel(true)}
                  disabled={mutation.isPending}
                  icon={<XCircleIcon aria-hidden className="size-4" />}
                >
                  Cancelar pedido
                </Button>
              ) : null}
              {actions.forward ? (
                <ForwardButton
                  status={actions.forward}
                  loading={pendingStatus === actions.forward}
                  disabled={mutation.isPending}
                  onClick={(status) => mutation.mutate(status)}
                />
              ) : null}
            </>
          ) : undefined
        }
      />

      {mutation.isError ? (
        <Banner tone="error" live className="mb-6">
          {errorMessage(mutation.error, ACTION_ERRORS)}
        </Banner>
      ) : null}

      <Panel className="mb-6 px-4 py-6 sm:px-8">
        {order.status === 'CANCELLED' ? (
          <p className="mb-5 flex items-center gap-2 text-sm font-medium text-mute-text">
            <XCircleIcon aria-hidden className="size-5" weight="bold" />
            Pedido cancelado. El stock que reservaba volvió al inventario.
          </p>
        ) : null}
        <StatusTrack status={order.status} />
      </Panel>

      <div className="grid grid-cols-1 items-start gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <OrderItems order={order} />
        <OrderInfo order={order} />
      </div>

      {confirmCancel ? (
        <Dialog
          open
          variant="alert"
          onClose={() => setConfirmCancel(false)}
          dismissible={!mutation.isPending}
          title="¿Cancelar este pedido?"
          description={`Se devolverán al inventario ${formatInteger(
            order.items.reduce((sum, item) => sum + item.quantity, 0),
          )} unidades de ${formatInteger(order.items.length)} ${order.items.length === 1 ? 'producto' : 'productos'}. No se puede deshacer.`}
          footer={
            <>
              <Button onClick={() => setConfirmCancel(false)} disabled={mutation.isPending}>
                Mantener pedido
              </Button>
              <Button variant="danger" onClick={() => mutation.mutate('CANCELLED')} loading={pendingStatus === 'CANCELLED'}>
                Cancelar pedido
              </Button>
            </>
          }
        />
      ) : null}
    </>
  )
}

function ForwardButton({
  status,
  loading,
  disabled,
  onClick,
}: {
  status: OrderStatus
  loading: boolean
  disabled: boolean
  onClick: (status: OrderStatus) => void
}) {
  const { icon: StatusIcon } = ORDER_STATUS_STYLE[status]
  return (
    <Button
      variant="primary"
      onClick={() => onClick(status)}
      loading={loading}
      disabled={disabled}
      icon={<StatusIcon aria-hidden className="size-4" weight="bold" />}
    >
      {ORDER_ACTION_LABEL[status]}
    </Button>
  )
}

function OrderItems({ order }: { order: Order }) {
  const units = order.items.reduce((sum, item) => sum + item.quantity, 0)
  return (
    <Panel labelledBy="items-title" className="overflow-hidden">
      <div className="flex items-baseline justify-between gap-3 px-5 pt-5 pb-3">
        <h2 id="items-title" className="title-label text-lg text-ink">
          Artículos
        </h2>
        <p className="text-sm text-ink-3 tabular-nums">
          {formatInteger(order.items.length)} {order.items.length === 1 ? 'línea' : 'líneas'}, {formatInteger(units)} {units === 1 ? 'ud.' : 'uds.'}
        </p>
      </div>
      <p className="px-5 pb-3 text-xs text-ink-3">Precios y nombres tal como estaban al crear el pedido.</p>
      <table role="table" className="w-full text-sm">
        <thead className="max-sm:sr-only">
          <tr role="row" className="border-y border-line text-left text-xs text-ink-2">
            <th role="columnheader" scope="col" className="py-2.5 pr-3 pl-5 font-semibold">
              Producto
            </th>
            <th role="columnheader" scope="col" className="px-3 py-2.5 text-right font-semibold">
              Precio
            </th>
            <th role="columnheader" scope="col" className="px-3 py-2.5 text-right font-semibold">
              Cant.
            </th>
            <th role="columnheader" scope="col" className="py-2.5 pr-5 pl-3 text-right font-semibold">
              Importe
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line max-sm:border-t max-sm:border-line">
          {order.items.map((item) => (
            <tr
              key={item.id}
              role="row"
              className="grid grid-cols-[minmax(0,1fr)_auto] gap-x-3 gap-y-1 px-5 py-3.5 sm:table-row sm:p-0"
            >
              <th role="rowheader" scope="row" className="col-span-2 min-w-0 text-left font-normal sm:py-3 sm:pr-3 sm:pl-5">
                <span className="line-clamp-2 font-medium text-ink [overflow-wrap:anywhere]" title={item.productName}>
                  {item.productName}
                </span>
                <span className="code mt-0.5 block text-xs text-ink-3">{item.sku}</span>
              </th>
              <td role="cell" className="text-ink-2 tabular-nums sm:table-cell sm:px-3 sm:py-3 sm:text-right">
                <span className="whitespace-nowrap">{formatMoney(item.unitPrice)}</span>
                <span className="sm:hidden"> × {formatInteger(item.quantity)}</span>
              </td>
              <td role="cell" className="hidden text-ink tabular-nums sm:table-cell sm:px-3 sm:py-3 sm:text-right">
                {formatInteger(item.quantity)}
              </td>
              <td role="cell" className="text-right font-semibold whitespace-nowrap text-ink tabular-nums sm:table-cell sm:py-3 sm:pr-5 sm:pl-3">
                {formatMoney(item.lineTotal)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="flex items-center justify-between gap-4 border-t border-line px-5 py-4">
        <span className="text-sm font-semibold text-ink">Total</span>
        <span className="ledger-total pb-0.5 text-xl font-semibold whitespace-nowrap text-ink tabular-nums">
          {formatMoney(order.total)}
        </span>
      </div>
    </Panel>
  )
}

function OrderInfo({ order }: { order: Order }) {
  return (
    <Panel labelledBy="info-title" className="px-5 py-5">
      <h2 id="info-title" className="title-label text-lg text-ink">
        Datos
      </h2>
      <dl className="mt-4 space-y-4 text-sm">
        <div>
          <dt className="text-xs font-medium text-ink-3">Cliente</dt>
          <dd className="mt-0.5 font-semibold text-ink [overflow-wrap:anywhere]">{order.customerName}</dd>
        </div>
        <div>
          <dt className="text-xs font-medium text-ink-3">Creado por</dt>
          <dd className="mt-0.5 text-ink [overflow-wrap:anywhere]">{order.createdByUsername}</dd>
        </div>
        <div>
          <dt className="text-xs font-medium text-ink-3">Creado</dt>
          <dd className="mt-0.5 text-ink">
            <time dateTime={order.createdAt}>{formatDateTime(order.createdAt)}</time>
          </dd>
        </div>
        <div>
          <dt className="text-xs font-medium text-ink-3">Referencia</dt>
          <dd className="code mt-0.5 text-xs text-ink-2">{order.id}</dd>
        </div>
        <div>
          <dt className="text-xs font-medium text-ink-3">Notas</dt>
          <dd className="mt-0.5 leading-relaxed whitespace-pre-line text-ink [overflow-wrap:anywhere]">
            {order.notes ? order.notes : <span className="text-ink-3">Sin notas</span>}
          </dd>
        </div>
      </dl>
    </Panel>
  )
}
