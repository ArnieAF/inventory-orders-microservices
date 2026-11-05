import { useEffect, useId, useRef, type ReactNode } from 'react'
import { IconButton } from './Button'
import { cx } from './cx'
import { XIcon } from './icons'

export interface DialogProps {
  open: boolean
  onClose: () => void
  title: string
  description?: ReactNode
  children?: ReactNode
  footer?: ReactNode
  /** `sheet`: panel lateral (abajo en móvil) para formularios. `alert`: confirmación centrada. */
  variant?: 'sheet' | 'alert'
  /** Si es `false` (p. ej. mientras se guarda), Esc y el fondo no cierran. */
  dismissible?: boolean
}

/**
 * Diálogo modal sobre `<dialog>` nativo: foco atrapado, Esc y capa superior los da el navegador.
 * Al cerrar devuelve el foco al elemento que lo abrió.
 */
export function Dialog(props: DialogProps) {
  if (!props.open) return null
  return <DialogInner {...props} />
}

function DialogInner({
  onClose,
  title,
  description,
  children,
  footer,
  variant = 'sheet',
  dismissible = true,
}: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  const descriptionId = useId()
  const latest = useRef({ onClose, dismissible })

  useEffect(() => {
    latest.current = { onClose, dismissible }
  })

  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    if (!dialog.open) dialog.showModal()

    const onCancel = (event: Event) => {
      event.preventDefault()
      if (latest.current.dismissible) latest.current.onClose()
    }
    const onBackdropClick = (event: MouseEvent) => {
      if (event.target === dialog && latest.current.dismissible) latest.current.onClose()
    }
    dialog.addEventListener('cancel', onCancel)
    dialog.addEventListener('click', onBackdropClick)
    return () => {
      dialog.removeEventListener('cancel', onCancel)
      dialog.removeEventListener('click', onBackdropClick)
      if (dialog.open) dialog.close()
      if (opener?.isConnected) opener.focus({ preventScroll: true })
    }
  }, [])

  const isAlert = variant === 'alert'

  return (
    <dialog
      ref={ref}
      role={isAlert ? 'alertdialog' : undefined}
      aria-labelledby={titleId}
      aria-describedby={description ? descriptionId : undefined}
      className={cx(
        'bg-raised p-0 text-ink shadow-pop backdrop:bg-[oklch(0.16_0.02_245/0.5)]',
        isAlert
          ? 'm-auto w-[calc(100%-2rem)] max-w-md rounded-xl motion-safe:animate-pop-in'
          : 'mx-0 mt-auto mb-0 max-h-[92dvh] w-full max-w-none rounded-t-2xl motion-safe:animate-toast-in ' +
              'sm:mt-0 sm:mr-0 sm:ml-auto sm:h-dvh sm:max-h-dvh sm:max-w-lg sm:rounded-none sm:rounded-l-2xl sm:motion-safe:animate-sheet-in',
      )}
    >
      <div className={cx('flex max-h-[inherit] flex-col', !isAlert && 'sm:h-full')}>
        <div className="flex items-start gap-4 px-5 pt-5 pb-3 sm:px-6 sm:pt-6">
          <div className="min-w-0 flex-1">
            <h2 id={titleId} className="title-label text-lg text-ink [overflow-wrap:anywhere]">
              {title}
            </h2>
            {description ? (
              <div id={descriptionId} className="mt-1 text-sm leading-relaxed text-ink-2 [overflow-wrap:anywhere]">
                {description}
              </div>
            ) : null}
          </div>
          {!isAlert ? (
            <IconButton label="Cerrar" onClick={onClose} disabled={!dismissible} className="-mt-1.5 -mr-2">
              <XIcon aria-hidden />
            </IconButton>
          ) : null}
        </div>
        {children ? <div className="min-h-0 flex-1 overflow-y-auto px-5 pt-2 pb-5 sm:px-6">{children}</div> : null}
        {footer ? (
          <div className="flex flex-col-reverse gap-2 border-t border-line px-5 py-4 pb-[max(1rem,env(safe-area-inset-bottom))] sm:flex-row sm:justify-end sm:px-6">
            {footer}
          </div>
        ) : null}
      </div>
    </dialog>
  )
}
