import type { ReactNode } from 'react'
import { BellIcon, PackageIcon, ReceiptIcon, type Icon } from '../../components/icons'

const POINTS: ReadonlyArray<{ icon: Icon; text: string }> = [
  { icon: PackageIcon, text: 'Catálogo con stock al día y ajustes con motivo.' },
  { icon: ReceiptIcon, text: 'Pedidos que reservan el stock al crearse.' },
  { icon: BellIcon, text: 'Avisos en tiempo real de pedidos y stock bajo.' },
]

export function AuthLayout({ title, subtitle, children }: { title: string; subtitle: ReactNode; children: ReactNode }) {
  return (
    <div className="grid grid-cols-1 min-h-dvh lg:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]">
      <aside className="relative hidden flex-col justify-between overflow-hidden bg-accent p-10 text-accent-ink lg:flex xl:p-14">
        <div className="flex items-center gap-3">
          <span className="grid size-10 place-items-center rounded-xl bg-accent-ink/12 ring-1 ring-accent-ink/20">
            <PackageIcon aria-hidden className="size-5" weight="bold" />
          </span>
          <span className="title-label text-lg">Inventario</span>
        </div>
        <div className="max-w-md">
          <p className="title-label text-3xl leading-tight xl:text-4xl">Stock y pedidos en una sola mesa de trabajo.</p>
          <ul className="mt-8 space-y-4">
            {POINTS.map(({ icon: PointIcon, text }) => (
              <li key={text} className="flex items-start gap-3 text-[0.9375rem] leading-relaxed opacity-90">
                <PointIcon aria-hidden className="mt-0.5 size-5 shrink-0" />
                {text}
              </li>
            ))}
          </ul>
        </div>
        <p className="text-sm opacity-75">Herramienta interna de back-office.</p>
      </aside>

      <main className="flex items-center justify-center px-5 py-10 sm:px-8">
        <div className="w-full max-w-sm">
          <div className="mb-8 flex items-center gap-2.5 lg:hidden">
            <span className="grid size-9 place-items-center rounded-lg bg-accent text-accent-ink">
              <PackageIcon aria-hidden className="size-5" weight="bold" />
            </span>
            <span className="title-label text-base text-ink">Inventario</span>
          </div>
          <h1 id="page-title" tabIndex={-1} data-focus-target className="title-label text-2xl text-ink">
            {title}
          </h1>
          <p className="mt-1.5 text-sm text-ink-2">{subtitle}</p>
          <div className="mt-8">{children}</div>
        </div>
      </main>
    </div>
  )
}
