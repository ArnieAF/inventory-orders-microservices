import { useEffect, type RefObject } from 'react'

/** Tras un envío fallido, lleva el foco al primer campo marcado como no válido. */
export function useFocusFirstInvalid(formRef: RefObject<HTMLElement | null>, attempt: number): void {
  useEffect(() => {
    if (attempt === 0) return
    formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus()
  }, [attempt, formRef])
}
