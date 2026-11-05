import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router'
import { listProducts } from '../../api/products'
import { queryKeys } from '../../api/queryKeys'
import { PRODUCT_SORT_FIELDS, type Product, type ProductListParams, type ProductSort } from '../../api/types'
import { Button, IconButton } from '../../components/Button'
import { cx } from '../../components/cx'
import { SelectField, TextField } from '../../components/Field'
import { EmptyState, ErrorState, Loading, PageHeader, Panel, Skeleton } from '../../components/Feedback'
import {
  ArrowsDownUpIcon,
  MagnifyingGlassIcon,
  PackageIcon,
  PencilSimpleIcon,
  PlusIcon,
  TrashIcon,
  WarningIcon,
  XIcon,
} from '../../components/icons'
import { Pagination } from '../../components/Pagination'
import { LowStockBadge } from '../../components/StatusBadge'
import { isLowStock } from '../../components/statusStyles'
import { formatInteger, formatMoney } from '../../domain/format'
import { useDebouncedValue } from '../../hooks/useDebouncedValue'
import { useDocumentTitle } from '../../hooks/useDocumentTitle'
import { DeleteProductDialog } from './DeleteProductDialog'
import { ProductFormDialog } from './ProductFormDialog'
import { StockAdjustDialog } from './StockAdjustDialog'

export const PRODUCTS_PAGE_SIZE = 20

const SORT_OPTIONS: ReadonlyArray<{ value: ProductSort; label: string }> = [
  { value: 'name,asc', label: 'Nombre (A-Z)' },
  { value: 'name,desc', label: 'Nombre (Z-A)' },
  { value: 'stock,asc', label: 'Menos stock primero' },
  { value: 'stock,desc', label: 'Más stock primero' },
  { value: 'price,asc', label: 'Precio más bajo' },
  { value: 'price,desc', label: 'Precio más alto' },
  { value: 'createdAt,desc', label: 'Más recientes' },
  { value: 'sku,asc', label: 'SKU (A-Z)' },
]

function parseSort(value: string | null): ProductSort {
  if (!value) return 'name,asc'
  const [field, direction] = value.split(',')
  const validField = (PRODUCT_SORT_FIELDS as readonly string[]).includes(field ?? '')
  const validDirection = direction === 'asc' || direction === 'desc'
  return validField && validDirection ? (value as ProductSort) : 'name,asc'
}

function parsePage(value: string | null): number {
  const page = Number.parseInt(value ?? '1', 10)
  return Number.isFinite(page) && page >= 1 ? page - 1 : 0
}

type DialogState =
  | { kind: 'create' }
  | { kind: 'edit'; product: Product }
  | { kind: 'adjust'; product: Product }
  | { kind: 'delete'; product: Product }
  | null

export function ProductsPage() {
  useDocumentTitle('Productos')
  const [params, setParams] = useSearchParams()
  const search = params.get('q')?.trim() ?? ''
  const lowStock = params.get('lowStock') === 'true'
  const page = parsePage(params.get('page'))
  const sort = parseSort(params.get('sort'))

  const [searchInput, setSearchInput] = useState(search)
  const debouncedSearch = useDebouncedValue(searchInput, 300)
  const lastPushed = useRef(debouncedSearch)
  const [dialog, setDialog] = useState<DialogState>(null)

  // La búsqueda va a la URL (enlazable) cuando el usuario deja de escribir.
  useEffect(() => {
    if (lastPushed.current === debouncedSearch) return
    lastPushed.current = debouncedSearch
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        const value = debouncedSearch.trim()
        if (value) next.set('q', value)
        else next.delete('q')
        next.delete('page')
        return next
      },
      { replace: true },
    )
  }, [debouncedSearch, setParams])

  function updateParams(changes: Record<string, string | null>, replace = false) {
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        for (const [key, value] of Object.entries(changes)) {
          if (value === null) next.delete(key)
          else next.set(key, value)
        }
        return next
      },
      { replace },
    )
  }

  const listParams: ProductListParams = {
    ...(search ? { search } : {}),
    ...(lowStock ? { lowStock: true } : {}),
    page,
    size: PRODUCTS_PAGE_SIZE,
    sort,
  }

  const query = useQuery({
    queryKey: queryKeys.products.list(listParams),
    queryFn: ({ signal }) => listProducts(listParams, signal),
    placeholderData: keepPreviousData,
  })

  // Si la página pedida ya no existe (p. ej. tras dar de baja el último de la página), ir a la última.
  const totalPages = query.data?.totalPages
  useEffect(() => {
    if (totalPages !== undefined && totalPages > 0 && page >= totalPages && !query.isPlaceholderData) {
      setParams(
        (current) => {
          const next = new URLSearchParams(current)
          next.set('page', String(totalPages))
          return next
        },
        { replace: true },
      )
    }
  }, [totalPages, page, query.isPlaceholderData, setParams])

  const clearFilters = () => {
    setSearchInput('')
    lastPushed.current = ''
    updateParams({ q: null, lowStock: null, page: null })
  }

  const filtered = Boolean(search) || lowStock
  const data = query.data

  return (
    <>
      <PageHeader
        title="Productos"
        description="Catálogo activo con su stock. Los ajustes quedan registrados con su motivo."
        actions={
          <Button
            variant="primary"
            onClick={() => setDialog({ kind: 'create' })}
            icon={<PlusIcon aria-hidden className="size-4" weight="bold" />}
          >
            Nuevo producto
          </Button>
        }
      />

      <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-end">
        <form role="search" className="min-w-0 flex-1" onSubmit={(event) => event.preventDefault()}>
          <TextField
            label="Buscar por SKU o nombre"
            type="search"
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
            leading={<MagnifyingGlassIcon aria-hidden />}
            placeholder="Ej. CAJ-0042 o caja de cartón"
            autoComplete="off"
            spellCheck={false}
            maxLength={120}
          />
        </form>
        <div className="flex gap-3">
          <Button
            aria-pressed={lowStock}
            onClick={() => updateParams({ lowStock: lowStock ? null : 'true', page: null })}
            icon={<WarningIcon aria-hidden className="size-4" weight={lowStock ? 'fill' : 'regular'} />}
            className={cx(
              'flex-1 sm:flex-none',
              lowStock && 'bg-warn-soft text-warn-text ring-warn-mark hover:bg-warn-soft',
            )}
          >
            Solo stock bajo
          </Button>
          <SelectField
            label="Ordenar productos"
            hideLabel
            value={sort}
            onChange={(event) => updateParams({ sort: event.target.value, page: null })}
            containerClassName="flex-1 sm:w-52 sm:flex-none"
          >
            {SORT_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </SelectField>
        </div>
      </div>

      <Panel labelledBy="products-table-caption" className="overflow-hidden">
        {query.isPending ? (
          <ProductsSkeleton />
        ) : query.isError && !data ? (
          <ErrorState error={query.error} onRetry={() => void query.refetch()} retrying={query.isRefetching} />
        ) : data && data.content.length === 0 ? (
          filtered ? (
            <EmptyState
              icon={lowStock && !search ? PackageIcon : MagnifyingGlassIcon}
              title={
                lowStock && !search
                  ? 'Ningún producto con stock bajo'
                  : search
                    ? `Nada coincide con «${search}»`
                    : 'Sin resultados'
              }
              description={
                lowStock && !search
                  ? 'Todo el catálogo está por encima de su aviso de stock bajo.'
                  : 'Prueba con otra parte del nombre o con el SKU completo.'
              }
              action={
                <Button onClick={clearFilters} icon={<XIcon aria-hidden className="size-4" />}>
                  Quitar filtros
                </Button>
              }
            />
          ) : (
            <EmptyState
              icon={PackageIcon}
              title="Aún no hay productos"
              description="Da de alta el primero con su SKU, precio y stock inicial. Después podrás usarlo en pedidos."
              action={
                <Button
                  variant="primary"
                  onClick={() => setDialog({ kind: 'create' })}
                  icon={<PlusIcon aria-hidden className="size-4" weight="bold" />}
                >
                  Nuevo producto
                </Button>
              }
            />
          )
        ) : data ? (
          <ProductTable
            products={data.content}
            dimmed={query.isPlaceholderData}
            onEdit={(product) => setDialog({ kind: 'edit', product })}
            onAdjust={(product) => setDialog({ kind: 'adjust', product })}
            onDelete={(product) => setDialog({ kind: 'delete', product })}
          />
        ) : null}
      </Panel>

      {data && data.totalElements > 0 ? (
        <div className="mt-4">
          <Pagination
            page={page}
            totalPages={data.totalPages}
            totalElements={data.totalElements}
            noun={['producto', 'productos']}
            onPageChange={(next) => updateParams({ page: next > 0 ? String(next + 1) : null })}
            busy={query.isFetching}
          />
        </div>
      ) : null}

      {dialog?.kind === 'create' ? <ProductFormDialog product={null} onClose={() => setDialog(null)} /> : null}
      {dialog?.kind === 'edit' ? <ProductFormDialog product={dialog.product} onClose={() => setDialog(null)} /> : null}
      {dialog?.kind === 'adjust' ? <StockAdjustDialog product={dialog.product} onClose={() => setDialog(null)} /> : null}
      {dialog?.kind === 'delete' ? <DeleteProductDialog product={dialog.product} onClose={() => setDialog(null)} /> : null}
    </>
  )
}

interface ProductTableProps {
  products: readonly Product[]
  dimmed: boolean
  onEdit: (product: Product) => void
  onAdjust: (product: Product) => void
  onDelete: (product: Product) => void
}

/** Tabla en escritorio; en móvil cada fila se apila como una ficha (mismo DOM, solo cambia el CSS). */
function ProductTable({ products, dimmed, onEdit, onAdjust, onDelete }: ProductTableProps) {
  return (
    // Roles explícitos: al cambiar el display en móvil algunos navegadores pierden la semántica de tabla.
    <table
      role="table"
      className={cx('w-full text-sm transition-opacity', dimmed && 'opacity-60')}
      aria-busy={dimmed || undefined}
    >
      <caption id="products-table-caption" className="sr-only">
        Productos del catálogo
      </caption>
      <thead className="max-md:sr-only">
        <tr role="row" className="border-b border-line text-left text-xs font-semibold text-ink-2">
          <th role="columnheader" scope="col" className="py-3 pr-3 pl-5 font-semibold">
            Producto
          </th>
          <th role="columnheader" scope="col" className="px-3 py-3 text-right font-semibold">
            Precio
          </th>
          <th role="columnheader" scope="col" className="px-3 py-3 text-right font-semibold">
            Stock
          </th>
          <th role="columnheader" scope="col" className="py-3 pr-5 pl-3 text-right font-semibold">
            <span className="sr-only">Acciones</span>
          </th>
        </tr>
      </thead>
      <tbody className="divide-y divide-line">
        {products.map((product) => {
          const low = isLowStock(product.stock, product.lowStockThreshold)
          return (
            <tr
              key={product.id}
              role="row"
              className="grid grid-cols-[minmax(0,1fr)_auto] gap-x-3 gap-y-2 px-4 py-4 md:table-row md:p-0 md:hover:bg-sunken/60"
            >
              <th role="rowheader" scope="row" className="col-span-2 min-w-0 text-left font-normal md:py-3.5 md:pr-3 md:pl-5 md:align-top">
                <span className="line-clamp-2 font-semibold text-ink [overflow-wrap:anywhere]" title={product.name}>
                  {product.name}
                </span>
                <span className="code mt-0.5 block text-xs text-ink-3">{product.sku}</span>
                {product.description ? (
                  <span className="mt-1 line-clamp-1 max-w-[60ch] text-xs text-ink-3 [overflow-wrap:anywhere] max-md:hidden">
                    {product.description}
                  </span>
                ) : null}
              </th>
              <td role="cell" className="flex items-baseline gap-1.5 md:table-cell md:px-3 md:py-3.5 md:text-right md:align-top">
                <span className="text-xs text-ink-3 md:hidden">Precio</span>
                <span className="font-medium whitespace-nowrap text-ink tabular-nums">{formatMoney(product.price)}</span>
              </td>
              <td role="cell" className="flex flex-wrap items-center justify-end gap-x-2 gap-y-1 md:table-cell md:px-3 md:py-3.5 md:text-right md:align-top">
                <span className="text-xs text-ink-3 md:hidden">Stock</span>
                <span className={cx('font-semibold whitespace-nowrap tabular-nums', low ? 'text-warn-text' : 'text-ink')}>
                  {formatInteger(product.stock)}
                </span>
                <span className="block text-xs whitespace-nowrap text-ink-3 tabular-nums max-md:hidden">
                  Aviso en {formatInteger(product.lowStockThreshold)}
                </span>
                {low ? (
                  <span className="md:mt-1 md:flex md:justify-end">
                    <LowStockBadge stock={product.stock} />
                  </span>
                ) : null}
              </td>
              <td role="cell" className="col-span-2 md:table-cell md:py-2.5 md:pr-4 md:pl-3 md:align-top">
                <div className="flex justify-end gap-1 max-md:-mr-2 max-md:border-t max-md:border-line max-md:pt-2">
                  <IconButton label={`Ajustar stock de ${product.name}`} onClick={() => onAdjust(product)}>
                    <ArrowsDownUpIcon aria-hidden />
                  </IconButton>
                  <IconButton label={`Editar ${product.name}`} onClick={() => onEdit(product)}>
                    <PencilSimpleIcon aria-hidden />
                  </IconButton>
                  <IconButton
                    label={`Dar de baja ${product.name}`}
                    variant="danger-ghost"
                    onClick={() => onDelete(product)}
                  >
                    <TrashIcon aria-hidden />
                  </IconButton>
                </div>
              </td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

function ProductsSkeleton() {
  return (
    <Loading label="Cargando productos…" className="divide-y divide-line">
      {[0, 1, 2, 3, 4].map((key) => (
        <div key={key} className="flex items-center gap-4 px-5 py-4">
          <div className="flex-1 space-y-2">
            <Skeleton className="h-4 w-1/2 max-w-64" />
            <Skeleton className="h-3 w-24" />
          </div>
          <Skeleton className="h-4 w-16" />
          <Skeleton className="h-4 w-12" />
          <Skeleton className="hidden h-8 w-28 md:block" />
        </div>
      ))}
    </Loading>
  )
}
