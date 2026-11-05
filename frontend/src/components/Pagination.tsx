import { formatInteger } from '../domain/format'
import { Button } from './Button'
import { CaretLeftIcon, CaretRightIcon } from './icons'

export interface PaginationProps {
  /** Página actual, base 0 (como la API). */
  page: number
  totalPages: number
  totalElements: number
  /** Nombre de lo que se cuenta, en singular y plural. */
  noun: readonly [singular: string, plural: string]
  onPageChange: (page: number) => void
  busy?: boolean
}

export function Pagination({ page, totalPages, totalElements, noun, onPageChange, busy }: PaginationProps) {
  const pages = Math.max(1, totalPages)
  const label = totalElements === 1 ? noun[0] : noun[1]
  return (
    <nav aria-label="Paginación" className="flex flex-wrap items-center justify-between gap-3">
      <p className="text-sm text-ink-2 tabular-nums" aria-live="polite">
        Página <span className="font-semibold text-ink">{formatInteger(page + 1)}</span> de {formatInteger(pages)}
        <span className="text-ink-3"> · </span>
        {formatInteger(totalElements)} {label}
      </p>
      <div className="flex gap-2">
        <Button
          size="sm"
          onClick={() => onPageChange(page - 1)}
          disabled={page <= 0 || busy}
          icon={<CaretLeftIcon aria-hidden className="size-4" />}
        >
          Anterior
        </Button>
        <Button size="sm" onClick={() => onPageChange(page + 1)} disabled={page >= pages - 1 || busy}>
          Siguiente
          <CaretRightIcon aria-hidden className="size-4" />
        </Button>
      </div>
    </nav>
  )
}
