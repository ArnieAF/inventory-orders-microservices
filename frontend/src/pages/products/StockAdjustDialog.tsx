import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useId, useRef, useState, type FormEvent } from 'react'
import { isApiError } from '../../api/client'
import { errorMessage, fieldErrorsFrom } from '../../api/errors'
import { adjustStock } from '../../api/products'
import { queryKeys } from '../../api/queryKeys'
import type { Product, StockAdjustmentRequest } from '../../api/types'
import { Button } from '../../components/Button'
import { cx } from '../../components/cx'
import { Dialog } from '../../components/Dialog'
import { TextAreaField, TextField } from '../../components/Field'
import { Banner } from '../../components/Feedback'
import { ArrowLeftIcon, MinusIcon, PlusIcon } from '../../components/icons'
import { formatInteger } from '../../domain/format'
import {
  LIMITS,
  parseNonNegativeInt,
  validateStockAdjustment,
  type FieldErrors,
  type StockAdjustmentField,
  type StockDirection,
} from '../../domain/validation'
import { useFocusFirstInvalid } from '../../hooks/useFocusFirstInvalid'
import { showToast } from '../../realtime/center'

const FIELDS: readonly StockAdjustmentField[] = ['quantity', 'reason']

export function StockAdjustDialog({ product, onClose }: { product: Product; onClose: () => void }) {
  const formId = useId()
  const formRef = useRef<HTMLFormElement>(null)
  const queryClient = useQueryClient()
  const [direction, setDirection] = useState<StockDirection>('in')
  const [quantity, setQuantity] = useState('')
  const [reason, setReason] = useState('')
  const [errors, setErrors] = useState<FieldErrors<StockAdjustmentField>>({})
  const [attempt, setAttempt] = useState(0)
  useFocusFirstInvalid(formRef, attempt)

  const mutation = useMutation({
    mutationFn: (body: StockAdjustmentRequest) => adjustStock(product.id, body),
    onSuccess: (updated) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.products.all })
      showToast({
        tone: 'success',
        title: 'Stock actualizado',
        message: `${updated.name}: ${formatInteger(updated.stock)} unidades.`,
      })
      onClose()
    },
    onError: (error) => {
      setErrors(fieldErrorsFrom(error, FIELDS))
      if (isApiError(error, 'INSUFFICIENT_STOCK') || isApiError(error, 'NOT_FOUND')) {
        void queryClient.invalidateQueries({ queryKey: queryKeys.products.all })
      }
    },
  })

  const parsed = parseNonNegativeInt(quantity)
  const delta = parsed ? (direction === 'in' ? parsed : -parsed) : 0
  const resulting = product.stock + delta

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (mutation.isPending) return
    const result = validateStockAdjustment({ direction, quantity, reason }, product.stock)
    if (!result.ok) {
      setErrors(result.errors)
      setAttempt((value) => value + 1)
      return
    }
    setErrors({})
    mutation.mutate(result.value)
  }

  return (
    <Dialog
      open
      onClose={onClose}
      dismissible={!mutation.isPending}
      title="Ajustar stock"
      description={
        <>
          <span className="font-medium text-ink">{product.name}</span>{' '}
          <span className="code text-ink-3">{product.sku}</span>
        </>
      }
      footer={
        <>
          <Button onClick={onClose} disabled={mutation.isPending}>
            Cancelar
          </Button>
          <Button type="submit" form={formId} variant="primary" loading={mutation.isPending}>
            Registrar ajuste
          </Button>
        </>
      }
    >
      <form id={formId} ref={formRef} noValidate onSubmit={onSubmit} className="flex flex-col gap-5">
        {mutation.isError ? (
          <Banner tone="error" live>
            {errorMessage(mutation.error, {
              INSUFFICIENT_STOCK:
                'El ajuste dejaría el stock por debajo de cero. Puede que alguien haya retirado unidades; revisa el stock actual.',
              NOT_FOUND: 'Este producto ya no existe o se dio de baja.',
            })}
          </Banner>
        ) : null}

        <fieldset>
          <legend className="mb-1.5 text-sm font-medium text-ink">Tipo de movimiento</legend>
          <div className="grid grid-cols-2 gap-1 rounded-lg bg-sunken p-1">
            {(
              [
                { value: 'in', label: 'Entrada', icon: PlusIcon },
                { value: 'out', label: 'Salida', icon: MinusIcon },
              ] as const
            ).map(({ value, label, icon: DirectionIcon }) => (
              <label
                key={value}
                className={cx(
                  'flex h-9 cursor-pointer items-center justify-center gap-1.5 rounded-md text-sm font-medium transition-colors pointer-coarse:h-11',
                  'has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-focus',
                  direction === value ? 'bg-raised text-ink shadow-sm ring-1 ring-line' : 'text-ink-2 hover:text-ink',
                )}
              >
                <input
                  type="radio"
                  name="direction"
                  value={value}
                  checked={direction === value}
                  onChange={() => setDirection(value)}
                  className="sr-only"
                />
                <DirectionIcon aria-hidden className="size-4" weight="bold" />
                {label}
              </label>
            ))}
          </div>
        </fieldset>

        <TextField
          label={direction === 'in' ? 'Unidades que entran' : 'Unidades que salen'}
          name="quantity"
          inputMode="numeric"
          autoComplete="off"
          value={quantity}
          onChange={(event) => setQuantity(event.target.value.replace(/[^\d]/g, ''))}
          error={errors.quantity}
          maxLength={10}
          className="tabular-nums"
        />

        <div aria-live="polite" className="flex items-center justify-between rounded-lg bg-sunken px-4 py-3 text-sm">
          <span className="text-ink-2">Stock resultante</span>
          <span className="flex items-baseline gap-2 tabular-nums">
            <span className="text-ink-3">{formatInteger(product.stock)}</span>
            <ArrowLeftIcon aria-hidden className="size-3.5 rotate-180 self-center text-ink-3" />
            <span className={cx('text-base font-semibold', resulting < 0 ? 'text-danger-text' : 'text-ink')}>
              {formatInteger(resulting)}
            </span>
            <span className="sr-only">unidades</span>
          </span>
        </div>

        <TextAreaField
          label="Motivo"
          name="reason"
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          error={errors.reason}
          maxLength={LIMITS.reason.max}
          rows={3}
          placeholder="Recepción de proveedor, rotura, recuento…"
          aside={`${reason.length}/${LIMITS.reason.max}`}
        />
      </form>
    </Dialog>
  )
}
