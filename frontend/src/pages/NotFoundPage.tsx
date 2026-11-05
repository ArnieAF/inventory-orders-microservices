import { useLocation } from 'react-router'
import { ButtonLink } from '../components/Button'
import { EmptyState, PageHeader, Panel } from '../components/Feedback'
import { MagnifyingGlassIcon } from '../components/icons'
import { useDocumentTitle } from '../hooks/useDocumentTitle'

export function NotFoundPage() {
  useDocumentTitle('Página no encontrada')
  const { pathname } = useLocation()
  return (
    <>
      <PageHeader title="Página no encontrada" />
      <Panel>
        <EmptyState
          icon={MagnifyingGlassIcon}
          title="Esta dirección no existe"
          description={
            <>
              No hay nada en <span className="code text-ink">{pathname}</span>. Puede que el enlace esté mal escrito o
              que la página se haya movido.
            </>
          }
          action={
            <>
              <ButtonLink to="/" variant="primary">
                Ir al panel
              </ButtonLink>
              <ButtonLink to="/orders">Ver pedidos</ButtonLink>
            </>
          }
        />
      </Panel>
    </>
  )
}
