import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useReducer, useRef, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { ApiError } from '../../api/client'
import { errorMessage } from '../../api/errors'
import { createOrder } from '../../api/orders'
import { listProducts } from '../../api/products'
import { queryKeys } from '../../api/queryKeys'
import type { CreateOrderRequest, Product } from '../../api/types'
import { Button, ButtonLink, IconButton } from '../../components/Button'
import { cx } from '../../components/cx'
import { TextAreaField, TextField } from '../../components/Field'
import { Banner, EmptyState, ErrorState, Loading, PageHeader, Panel, Skeleton } from '../../components/Feedback'
import {
  ArrowLeftIcon,
  BasketIcon,
  MagnifyingGlassIcon,
  MinusIcon,
  PackageIcon,
  PlusIcon,
  TrashIcon,
  WarningCircleIcon,
} from '../../components/icons'
import { formatInteger, formatMoney, fromCents, shortId, toCents } from '../../domain/format'
import {
  EMPTY_DRAFT,
  draftReducer,
  estimateTotalCents,
  parseQuantity,
  shortagesFromDetails,
  validateOrderDraft,
  type DraftErrors,
  type DraftLine,
  type StockShortage,
} from '../../domain/orderDraft'
import { LIMITS } from '../../domain/validation'
import { useDebouncedValue } from '../../hooks/useDebouncedValue'
import { useDocumentTitle } from '../../hooks/useDocumentTitle'
import { useFocusFirstInvalid } from '../../hooks/useFocusFirstInvalid'
import { showToast } from '../../realtime/center'

const PICKER_SIZE = 8
const EMPTY_ERRORS: DraftErrors = { quantities: {} }

export function NewOrderPage() {
  useDocumentTitle('Nuevo pedido')
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const formId = useId()
  const formRef = useRef<HTMLFormElement>(null)

  const [{ lines, notice }, dispatch] = useReducer(draftReducer, EMPTY_DRAFT)
  const [customerName, setCustomerName] = useState('')
  const [notes, setNotes] = useState('')
  const [errors, setErrors] = useState<DraftErrors>(EMPTY_ERRORS)
  const [attempt, setAttempt] = useState(0)
  const [shortages, setShortages] = useState<ReadonlyMap<string, StockShortage>>(new Map())
  const [unavailable, setUnavailable] = useState<ReadonlySet<string>>(new Set())
  useFocusFirstInvalid(formRef, attempt)

  const mutation = useMutation({
    mutationFn: (request: CreateOrderRequest) => createOrder(request),
    onSuccess: (order) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.orders.all })
      void queryClient.invalidateQueries({ queryKey: queryKeys.products.all })
      showToast({ tone: 'success', title: 'Pedido creado', message: `#${shortId(order.id)} para ${order.customerName}` })
      void navigate(`/orders/${encodeURIComponent(order.id)}`)
    },
    onError: (error) => {
      if (!(error instanceof ApiError)) return
      if (error.code === 'INSUFFICIENT_STOCK') {
        setShortages(shortagesFromDetails(error.details))
        void queryClient.invalidateQueries({ queryKey: queryKeys.products.all })
      }
      if (error.code === 'PRODUCT_INACTIVE' || error.code === 'NOT_FOUND') {
        setUnavailable(new Set(error.details.flatMap((detail) => (detail.productId ? [detail.productId] : []))))
        void queryClient.invalidateQueries({ queryKey: queryKeys.products.all })
      }
    },
  })

  function addProduct(product: Product) {
    dispatch({ type: 'add', product })
    if (errors.items) setErrors((current) => ({ ...current, items: undefined }))
  }

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (mutation.isPending) return
    const result = validateOrderDraft({ customerName, notes, lines })
    if (!result.ok) {
      setErrors(result.errors)
      setAttempt((value) => value + 1)
      return
    }
    setErrors(EMPTY_ERRORS)
    setShortages(new Map())
    setUnavailable(new Set())
    mutation.mutate(result.request)
  }

  const totalCents = estimateTotalCents(lines)
  const units = lines.reduce((sum, line) => sum + (parseQuantity(line.quantity) ?? 0), 0)
  const pendingShortages = lines.some((line) => {
    const shortage = shortages.get(line.productId)
    return shortage !== undefined && (parseQuantity(line.quantity) ?? 0) > shortage.available
  })

  const serverError = mutation.error
  const showStockBanner = serverError instanceof ApiError && serverError.code === 'INSUFFICIENT_STOCK' && pendingShortages

  return (
    <>
      <PageHeader
        title="Nuevo pedido"
        description="Busca los productos, ajusta cantidades y confirma. El stock se reserva al crear el pedido."
        back={
          <ButtonLink to="/orders" variant="ghost" size="sm" className="-ml-3" icon={<ArrowLeftIcon aria-hidden className="size-4" />}>
            Pedidos
          </ButtonLink>
        }
      />

      <div className="grid grid-cols-1 items-start gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,26rem)]">
        <ProductPicker lines={lines} onAdd={addProduct} />

        <Panel labelledBy={`${formId}-title`} className="lg:sticky lg:top-24">
          <form id={formId} ref={formRef} noValidate onSubmit={onSubmit} className="flex flex-col">
            <div className="flex items-baseline justify-between gap-3 px-5 pt-5">
              <h2 id={`${formId}-title`} className="title-label text-lg text-ink">
                Pedido
              </h2>
              <p className="text-sm text-ink-3 tabular-nums">
                {lines.length === 0
                  ? 'Sin productos'
                  : `${formatInteger(lines.length)} ${lines.length === 1 ? 'producto' : 'productos'}, ${formatInteger(units)} ${units === 1 ? 'ud.' : 'uds.'}`}
              </p>
            </div>

            <div className="flex flex-col gap-4 px-5 pt-4">
              {showStockBanner ? (
                <Banner
                  tone="error"
                  live
                  title="No hay stock suficiente"
                  action={
                    <Button size="sm" onClick={() => dispatch({ type: 'applyShortages', shortages })}>
                      Ajustar cantidades
                    </Button>
                  }
                >
                  Revisa las líneas marcadas: alguien pudo reservar unidades mientras preparabas el pedido.
                </Banner>
              ) : mutation.isError && !(serverError instanceof ApiError && serverError.code === 'INSUFFICIENT_STOCK') ? (
                <Banner tone="error" live>
                  {errorMessage(serverError, {
                    NOT_FOUND: 'Alguno de los productos ya no existe. Quita los marcados y vuelve a intentarlo.',
                    PRODUCT_INACTIVE: 'Alguno de los productos se dio de baja. Quita los marcados y vuelve a intentarlo.',
                  })}
                </Banner>
              ) : null}

              <TextField
                label="Cliente"
                name="customerName"
                value={customerName}
                onChange={(event) => setCustomerName(event.target.value)}
                error={errors.customerName}
                maxLength={LIMITS.customerName.max}
                autoComplete="off"
              />
            </div>

            <div className="mt-5 border-t border-line">
              {lines.length === 0 ? (
                <div
                  className={cx(
                    'flex flex-col items-center px-6 py-8 text-center',
                    errors.items && 'bg-danger-soft/50',
                  )}
                >
                  <BasketIcon aria-hidden className="size-7 text-ink-3" />
                  <p className="mt-2 text-sm font-medium text-ink">Todavía no hay productos</p>
                  <p className="mt-0.5 text-sm text-ink-2">Añádelos desde el buscador.</p>
                </div>
              ) : (
                <ul aria-label="Líneas del pedido" className="divide-y divide-line">
                  {lines.map((line) => (
                    <DraftLineRow
                      key={line.productId}
                      line={line}
                      error={errors.quantities[line.productId]}
                      shortage={shortages.get(line.productId)}
                      unavailable={unavailable.has(line.productId)}
                      onQuantity={(value) => dispatch({ type: 'quantity', productId: line.productId, value })}
                      onStep={(step) => dispatch({ type: 'step', productId: line.productId, step })}
                      onRemove={() => dispatch({ type: 'remove', productId: line.productId })}
                    />
                  ))}
                </ul>
              )}
              {errors.items ? (
                <p role="alert" className="flex items-center gap-1.5 px-5 pb-3 text-xs font-medium text-danger-text">
                  <WarningCircleIcon aria-hidden className="size-3.5" weight="bold" />
                  {errors.items}
                </p>
              ) : null}
              {notice ? (
                <p role="status" className="px-5 pb-3 text-xs font-medium text-warn-text">
                  {notice}
                </p>
              ) : null}
            </div>

            <div className="border-t border-line px-5 pt-4">
              <TextAreaField
                label="Notas"
                name="notes"
                value={notes}
                onChange={(event) => setNotes(event.target.value)}
                error={errors.notes}
                maxLength={LIMITS.notes.max}
                rows={2}
                aside={`Opcional · ${notes.length}/${LIMITS.notes.max}`}
              />
            </div>

            <div className="px-5 pt-5 pb-5">
              <div className="flex items-end justify-between gap-4">
                <div>
                  <p className="text-sm font-medium text-ink">Total estimado</p>
                  <p className="mt-0.5 max-w-[30ch] text-xs text-ink-3">Con los precios mostrados. El servidor calcula el importe final.</p>
                </div>
                <p className="ledger-total pb-0.5 text-xl font-semibold whitespace-nowrap text-ink tabular-nums" aria-live="polite">
                  {formatMoney(fromCents(totalCents))}
                </p>
              </div>
              <Button type="submit" variant="primary" className="mt-5 w-full" loading={mutation.isPending}>
                {mutation.isPending ? 'Creando pedido…' : 'Crear pedido'}
              </Button>
            </div>
          </form>
        </Panel>
      </div>
    </>
  )
}

interface DraftLineRowProps {
  line: DraftLine
  error: string | undefined
  shortage: StockShortage | undefined
  unavailable: boolean
  onQuantity: (value: string) => void
  onStep: (step: 1 | -1) => void
  onRemove: () => void
}

function DraftLineRow({ line, error, shortage, unavailable, onQuantity, onStep, onRemove }: DraftLineRowProps) {
  const inputId = useId()
  const quantity = parseQuantity(line.quantity)
  const short = shortage !== undefined && (quantity ?? 0) > shortage.available
  const overKnownStock = !short && quantity !== null && quantity > line.stock
  const lineTotal = quantity ? fromCents(toCents(line.unitPrice) * quantity) : 0
  const problem = error ?? (unavailable ? 'Ya no está disponible: quítalo del pedido.' : undefined)

  return (
    <li className={cx('px-5 py-4', (short || problem) && 'bg-danger-soft/40')}>
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <p className="line-clamp-2 text-sm font-semibold text-ink [overflow-wrap:anywhere]" title={line.name}>
            {line.name}
          </p>
          <p className="mt-0.5 flex flex-wrap gap-x-2 text-xs text-ink-3">
            <span className="code">{line.sku}</span>
            <span className="tabular-nums">{formatMoney(line.unitPrice)} / ud.</span>
          </p>
        </div>
        <IconButton label={`Quitar ${line.name}`} variant="danger-ghost" onClick={onRemove} className="-mt-1.5 -mr-2">
          <TrashIcon aria-hidden />
        </IconButton>
      </div>

      <div className="mt-3 flex items-center justify-between gap-3">
        <div className="flex items-center gap-1">
          <IconButton label={`Una unidad menos de ${line.name}`} variant="secondary" onClick={() => onStep(-1)} disabled={(quantity ?? 0) <= 1}>
            <MinusIcon aria-hidden />
          </IconButton>
          <label htmlFor={inputId} className="sr-only">
            Cantidad de {line.name}
          </label>
          <input
            id={inputId}
            inputMode="numeric"
            autoComplete="off"
            value={line.quantity}
            onChange={(event) => onQuantity(event.target.value)}
            aria-invalid={error || short ? true : undefined}
            aria-describedby={error || short || overKnownStock || unavailable ? `${inputId}-msg` : undefined}
            className="h-10 w-20 rounded-lg bg-surface text-center text-base font-semibold text-ink tabular-nums ring-1 ring-line-strong ring-inset focus:ring-2 focus:ring-focus focus:outline-none aria-invalid:ring-2 aria-invalid:ring-danger sm:text-sm pointer-coarse:h-11"
          />
          <IconButton
            label={`Una unidad más de ${line.name}`}
            variant="secondary"
            onClick={() => onStep(1)}
            disabled={(quantity ?? 0) >= LIMITS.quantity.max}
          >
            <PlusIcon aria-hidden />
          </IconButton>
        </div>
        <p className="text-right text-sm font-semibold whitespace-nowrap text-ink tabular-nums">{formatMoney(lineTotal)}</p>
      </div>

      {problem || short || overKnownStock ? (
        <p
          id={`${inputId}-msg`}
          className={cx(
            'mt-2 flex items-start gap-1.5 text-xs font-medium',
            problem || short ? 'text-danger-text' : 'text-warn-text',
          )}
        >
          <WarningCircleIcon aria-hidden className="mt-px size-3.5 shrink-0" weight="bold" />
          {problem ??
            (short && shortage
              ? `Pediste ${formatInteger(shortage.requested)}, disponibles ${formatInteger(shortage.available)}.`
              : `Solo se veían ${formatInteger(line.stock)} en stock al añadirlo.`)}
        </p>
      ) : null}
    </li>
  )
}

function ProductPicker({ lines, onAdd }: { lines: readonly DraftLine[]; onAdd: (product: Product) => void }) {
  const [search, setSearch] = useState('')
  const debounced = useDebouncedValue(search.trim(), 300)
  const query = useQuery({
    queryKey: queryKeys.products.picker(debounced),
    queryFn: ({ signal }) =>
      listProducts({ ...(debounced ? { search: debounced } : {}), page: 0, size: PICKER_SIZE, sort: 'name,asc' }, signal),
    placeholderData: keepPreviousData,
  })
  const inDraft = new Map(lines.map((line) => [line.productId, parseQuantity(line.quantity) ?? 0]))
  const data = query.data

  return (
    <Panel labelledBy="picker-title" className="overflow-hidden">
      <div className="px-5 pt-5 pb-4">
        <h2 id="picker-title" className="title-label text-lg text-ink">
          Añadir productos
        </h2>
        <form role="search" className="mt-4" onSubmit={(event) => event.preventDefault()}>
          <TextField
            label="Buscar por SKU o nombre"
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            leading={<MagnifyingGlassIcon aria-hidden />}
            autoComplete="off"
            spellCheck={false}
            maxLength={120}
          />
        </form>
      </div>

      <div className="border-t border-line">
        {query.isPending ? (
          <Loading label="Buscando productos…" className="divide-y divide-line">
            {[0, 1, 2, 3].map((key) => (
              <div key={key} className="flex items-center gap-4 px-5 py-4">
                <div className="flex-1 space-y-2">
                  <Skeleton className="h-4 w-1/2" />
                  <Skeleton className="h-3 w-1/3" />
                </div>
                <Skeleton className="h-9 w-24" />
              </div>
            ))}
          </Loading>
        ) : query.isError ? (
          <ErrorState error={query.error} onRetry={() => void query.refetch()} retrying={query.isRefetching} />
        ) : data && data.content.length === 0 ? (
          <EmptyState
            icon={debounced ? MagnifyingGlassIcon : PackageIcon}
            title={debounced ? `Nada coincide con «${debounced}»` : 'No hay productos activos'}
            description={
              debounced
                ? 'Prueba con otra parte del nombre o con el SKU completo.'
                : 'Da de alta productos en el catálogo para poder venderlos.'
            }
            action={
              debounced ? null : (
                <ButtonLink to="/products" icon={<PackageIcon aria-hidden className="size-4" />}>
                  Ir a productos
                </ButtonLink>
              )
            }
          />
        ) : data ? (
          <>
            <ul aria-label="Resultados" className={cx('divide-y divide-line transition-opacity', query.isFetching && 'opacity-70')}>
              {data.content.map((product) => {
                const added = inDraft.get(product.id)
                const soldOut = product.stock <= 0
                return (
                  <li key={product.id} className="flex items-center gap-4 px-5 py-3.5">
                    <div className="min-w-0 flex-1">
                      <p className="line-clamp-2 text-sm font-semibold text-ink [overflow-wrap:anywhere]" title={product.name}>
                        {product.name}
                      </p>
                      <p className="mt-0.5 flex flex-wrap gap-x-3 text-xs text-ink-3">
                        <span className="code">{product.sku}</span>
                        <span className="tabular-nums">{formatMoney(product.price)}</span>
                        <span className={cx('tabular-nums', soldOut ? 'font-semibold text-danger-text' : product.stock <= product.lowStockThreshold && 'text-warn-text')}>
                          {soldOut ? 'Agotado' : `${formatInteger(product.stock)} en stock`}
                        </span>
                      </p>
                    </div>
                    <Button
                      size="sm"
                      variant={added ? 'secondary' : 'primary'}
                      onClick={() => onAdd(product)}
                      disabled={soldOut}
                      aria-label={added ? `Añadir otra unidad de ${product.name}` : `Añadir ${product.name}`}
                      icon={<PlusIcon aria-hidden className="size-4" weight="bold" />}
                    >
                      {added ? `${formatInteger(added)} en pedido` : 'Añadir'}
                    </Button>
                  </li>
                )
              })}
            </ul>
            {data.totalElements > data.content.length ? (
              <p className="border-t border-line px-5 py-3 text-xs text-ink-3">
                Mostrando {formatInteger(data.content.length)} de {formatInteger(data.totalElements)}. Afina la búsqueda para encontrar otros.
              </p>
            ) : null}
          </>
        ) : null}
      </div>
    </Panel>
  )
}
