import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * Panel desplegable no modal: se cierra con Esc (devolviendo el foco al botón),
 * con un clic fuera o cuando el foco sale del contenedor.
 */
export function usePopover() {
  const [open, setOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)

  const close = useCallback((restoreFocus = false) => {
    setOpen(false)
    if (restoreFocus) triggerRef.current?.focus()
  }, [])

  const toggle = useCallback(() => setOpen((value) => !value), [])

  useEffect(() => {
    if (!open) return
    const container = containerRef.current
    const onPointerDown = (event: PointerEvent) => {
      if (container && event.target instanceof Node && !container.contains(event.target)) setOpen(false)
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false)
        triggerRef.current?.focus()
      }
    }
    const onFocusOut = (event: FocusEvent) => {
      const next = event.relatedTarget
      if (container && next instanceof Node && !container.contains(next)) setOpen(false)
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    container?.addEventListener('focusout', onFocusOut)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
      container?.removeEventListener('focusout', onFocusOut)
    }
  }, [open])

  return { open, toggle, close, containerRef, triggerRef }
}
