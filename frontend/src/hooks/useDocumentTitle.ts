import { useEffect } from 'react'

const APP_NAME = 'Inventario y pedidos'

export function useDocumentTitle(title: string): void {
  useEffect(() => {
    document.title = title ? `${title} - ${APP_NAME}` : APP_NAME
  }, [title])
}
