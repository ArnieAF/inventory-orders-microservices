import { useEffect, useRef } from 'react'
import { Link, Outlet, ScrollRestoration, useLocation } from 'react-router'
import { ButtonLink } from '../components/Button'
import { cx } from '../components/cx'
import { PackageIcon, PlusIcon, ReceiptIcon, SquaresFourIcon, type Icon } from '../components/icons'
import { Toaster } from '../components/Toaster'
import { NotificationBell } from './NotificationBell'
import { UserMenu } from './UserMenu'

interface NavItem {
  to: string
  label: string
  icon: Icon
  isActive: (pathname: string) => boolean
}

const NAV: readonly NavItem[] = [
  { to: '/', label: 'Panel', icon: SquaresFourIcon, isActive: (path) => path === '/' },
  { to: '/products', label: 'Productos', icon: PackageIcon, isActive: (path) => path.startsWith('/products') },
  {
    to: '/orders',
    label: 'Pedidos',
    icon: ReceiptIcon,
    isActive: (path) => path.startsWith('/orders') && path !== '/orders/new',
  },
]

const NEW_ORDER_ITEM: NavItem = {
  to: '/orders/new',
  label: 'Nuevo',
  icon: PlusIcon,
  isActive: (path) => path === '/orders/new',
}

export function Brand() {
  return (
    <Link to="/" className="flex shrink-0 items-center gap-2.5 rounded-lg pr-1" aria-label="Inventario y pedidos, ir al panel">
      <span className="grid size-8 place-items-center rounded-lg bg-accent text-accent-ink">
        <PackageIcon aria-hidden className="size-4.5" weight="bold" />
      </span>
      <span className="title-label hidden text-[0.9375rem] text-ink sm:inline">Inventario</span>
    </Link>
  )
}

/** Mueve el foco al título de la página tras cada navegación (lectores de pantalla y teclado). */
function useFocusOnNavigate() {
  const { pathname } = useLocation()
  const first = useRef(true)
  useEffect(() => {
    if (first.current) {
      first.current = false
      return
    }
    document.getElementById('page-title')?.focus({ preventScroll: true })
  }, [pathname])
}

export function AppShell() {
  useFocusOnNavigate()
  const { pathname } = useLocation()
  return (
    <div className="min-h-dvh">
      <a
        href="#main"
        className="sr-only z-50 rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-accent-ink focus:not-sr-only focus:fixed focus:top-3 focus:left-3"
      >
        Saltar al contenido
      </a>

      <header className="sticky top-0 z-30 border-b border-line bg-canvas/85 backdrop-blur-md backdrop-saturate-150 [@media(prefers-reduced-transparency:reduce)]:bg-canvas">
        <div className="mx-auto flex h-16 max-w-7xl items-center gap-3 px-4 sm:px-6 lg:px-8">
          <Brand />
          <nav aria-label="Principal" className="ml-4 hidden h-full items-stretch gap-1 md:flex">
            {NAV.map((item) => {
              const active = item.isActive(pathname)
              return (
                <Link
                  key={item.to}
                  to={item.to}
                  aria-current={active ? 'page' : undefined}
                  className={cx(
                    'relative flex items-center gap-2 px-3 text-sm font-medium transition-colors',
                    'after:absolute after:inset-x-3 after:-bottom-px after:h-0.5 after:rounded-full after:transition-colors',
                    active ? 'text-ink after:bg-accent' : 'text-ink-2 after:bg-transparent hover:text-ink',
                  )}
                >
                  <item.icon aria-hidden className="size-4.5" weight={active ? 'bold' : 'regular'} />
                  {item.label}
                </Link>
              )
            })}
          </nav>
          <div className="ml-auto flex items-center gap-1.5">
            {/* En móvil la acción vive en la barra inferior ("Nuevo"). */}
            <div className="mr-1.5 hidden md:block">
              <ButtonLink
                to="/orders/new"
                variant="primary"
                size="sm"
                icon={<PlusIcon aria-hidden className="size-4" weight="bold" />}
              >
                Nuevo pedido
              </ButtonLink>
            </div>
            <NotificationBell />
            <UserMenu />
          </div>
        </div>
      </header>

      <main id="main" className="mx-auto max-w-7xl px-4 pt-6 pb-28 sm:px-6 sm:pt-8 md:pb-16 lg:px-8">
        <Outlet />
      </main>

      <nav
        aria-label="Principal"
        className="fixed inset-x-0 bottom-0 z-30 border-t border-line bg-canvas/90 pb-[env(safe-area-inset-bottom)] backdrop-blur-md md:hidden [@media(prefers-reduced-transparency:reduce)]:bg-canvas"
      >
        <ul className="mx-auto grid max-w-md grid-cols-4">
          {[...NAV, NEW_ORDER_ITEM].map((item) => {
            const active = item.isActive(pathname)
            return (
              <li key={item.to}>
                <Link
                  to={item.to}
                  aria-current={active ? 'page' : undefined}
                  className={cx(
                    'flex h-16 flex-col items-center justify-center gap-1 text-[0.6875rem] font-medium transition-colors',
                    active ? 'text-accent-text' : 'text-ink-2',
                  )}
                >
                  <item.icon aria-hidden className="size-6" weight={active ? 'fill' : 'regular'} />
                  {item.label}
                </Link>
              </li>
            )
          })}
        </ul>
      </nav>

      <Toaster />
      <ScrollRestoration />
    </div>
  )
}
