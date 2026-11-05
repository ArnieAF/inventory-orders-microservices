import type { OrderStatus } from '../../api/types'
import { cx } from '../../components/cx'
import { CheckIcon } from '../../components/icons'
import { ORDER_STATUS_STYLE } from '../../components/statusStyles'
import { ORDER_FLOW, ORDER_STATUS_LABEL, flowIndex } from '../../domain/orderStatus'

/**
 * Recorrido del pedido (Pendiente → Confirmado → Enviado → Entregado). La barra avanza
 * con una transición corta cuando cambia el estado; un pedido cancelado se ve apagado.
 */
export function StatusTrack({ status }: { status: OrderStatus }) {
  const cancelled = status === 'CANCELLED'
  const current = flowIndex(status)
  const progress = cancelled ? 0 : current / (ORDER_FLOW.length - 1)

  return (
    <ol aria-label="Progreso del pedido" className={cx('relative grid grid-cols-4', cancelled && 'opacity-55')}>
      <span aria-hidden className="absolute top-4 right-[12.5%] left-[12.5%] h-0.5 rounded-full bg-line" />
      <span
        aria-hidden
        className="absolute top-4 right-[12.5%] left-[12.5%] h-0.5 origin-left rounded-full bg-accent transition-transform duration-500 ease-out-expo motion-reduce:transition-none"
        style={{ transform: `scaleX(${progress})` }}
      />
      {ORDER_FLOW.map((step, index) => {
        const done = !cancelled && index < current
        const isCurrent = !cancelled && index === current
        const { icon: StepIcon } = ORDER_STATUS_STYLE[step]
        return (
          <li key={step} aria-current={isCurrent ? 'step' : undefined} className="relative flex flex-col items-center gap-2 text-center">
            <span
              className={cx(
                'grid size-8 place-items-center rounded-full ring-4 ring-surface transition-colors duration-300',
                done && 'bg-accent text-accent-ink',
                isCurrent && 'bg-accent text-accent-ink shadow-[0_0_0_6px_var(--color-accent-soft)]',
                !done && !isCurrent && 'bg-sunken text-ink-3',
              )}
            >
              {done ? (
                <CheckIcon aria-hidden className="size-4" weight="bold" />
              ) : (
                <StepIcon aria-hidden className="size-4" weight={isCurrent ? 'bold' : 'regular'} />
              )}
            </span>
            <span className={cx('text-xs font-medium sm:text-sm', isCurrent ? 'text-ink' : done ? 'text-ink-2' : 'text-ink-3')}>
              {ORDER_STATUS_LABEL[step]}
              <span className="sr-only">{done ? ' (completado)' : isCurrent ? ' (estado actual)' : ' (pendiente)'}</span>
            </span>
          </li>
        )
      })}
    </ol>
  )
}
