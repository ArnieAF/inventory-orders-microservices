/**
 * Formato de cifras y fechas en español.
 * El contrato no fija moneda: los importes se muestran con 2 decimales y sin símbolo.
 * Si se decide una moneda, basta con fijar MONEY_CURRENCY (p. ej. 'EUR').
 */
export const MONEY_CURRENCY: string | null = null
const LOCALE = 'es-ES'

const moneyFormat = new Intl.NumberFormat(LOCALE, {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
  useGrouping: 'always',
  ...(MONEY_CURRENCY ? { style: 'currency', currency: MONEY_CURRENCY } : {}),
})

const integerFormat = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 0, useGrouping: 'always' })

const dateTimeFormat = new Intl.DateTimeFormat(LOCALE, { dateStyle: 'medium', timeStyle: 'short' })
const dateFormat = new Intl.DateTimeFormat(LOCALE, { day: 'numeric', month: 'short', year: 'numeric' })
const relativeFormat = new Intl.RelativeTimeFormat(LOCALE, { numeric: 'auto' })

export function formatMoney(value: number): string {
  return Number.isFinite(value) ? moneyFormat.format(value) : '-'
}

export function formatInteger(value: number): string {
  return Number.isFinite(value) ? integerFormat.format(value) : '-'
}

function toDate(iso: string): Date | null {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? null : date
}

export function formatDateTime(iso: string): string {
  const date = toDate(iso)
  return date ? dateTimeFormat.format(date) : '-'
}

export function formatDate(iso: string): string {
  const date = toDate(iso)
  return date ? dateFormat.format(date) : '-'
}

/** "hace 5 minutos", "ayer"… (cae a fecha absoluta pasada una semana). */
export function formatRelative(iso: string, now: number): string {
  const date = toDate(iso)
  if (!date) return '-'
  const seconds = Math.round((date.getTime() - now) / 1000)
  const abs = Math.abs(seconds)
  if (abs < 45) return 'ahora mismo'
  if (abs < 3600) return relativeFormat.format(Math.round(seconds / 60), 'minute')
  if (abs < 86_400) return relativeFormat.format(Math.round(seconds / 3600), 'hour')
  if (abs < 7 * 86_400) return relativeFormat.format(Math.round(seconds / 86_400), 'day')
  return dateFormat.format(date)
}

/** Contador de la campana: a partir de 100 se muestra "99+". */
export function formatBadgeCount(count: number): string {
  return count > 99 ? '99+' : String(Math.max(0, Math.floor(count)))
}

/** Referencia corta y legible de un UUID para mostrar ("#3F2A9C1B"). */
export function shortId(id: string): string {
  return id.replace(/-/g, '').slice(0, 8).toUpperCase()
}

/** Suma de importes en céntimos para evitar errores de coma flotante. */
export function toCents(value: number): number {
  return Math.round(value * 100)
}

export function fromCents(cents: number): number {
  return cents / 100
}
