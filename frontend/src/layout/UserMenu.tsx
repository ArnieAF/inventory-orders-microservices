import { useEffect, useId, useRef } from 'react'
import { sessionStore } from '../auth/sessionStore'
import { useSession } from '../auth/useSession'
import { cx } from '../components/cx'
import { CaretDownIcon, DesktopIcon, MoonIcon, SignOutIcon, SunIcon, UserCircleIcon, type Icon } from '../components/icons'
import { usePopover } from '../hooks/usePopover'
import { setThemeMode, useThemeMode, type ThemeMode } from '../theme'

const THEMES: ReadonlyArray<{ value: ThemeMode; label: string; icon: Icon }> = [
  { value: 'system', label: 'Sistema', icon: DesktopIcon },
  { value: 'light', label: 'Claro', icon: SunIcon },
  { value: 'dark', label: 'Oscuro', icon: MoonIcon },
]

export function UserMenu() {
  const { user } = useSession()
  const { open, toggle, containerRef, triggerRef } = usePopover()
  const panelId = useId()

  return (
    <div ref={containerRef} className="relative">
      <button
        ref={triggerRef}
        type="button"
        onClick={toggle}
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        aria-label={`Cuenta de ${user.username}`}
        className={cx(
          'flex h-10 max-w-[14rem] items-center gap-2 rounded-lg pr-2 pl-2 text-sm font-medium text-ink-2 transition-colors hover:bg-sunken hover:text-ink pointer-coarse:h-11',
          open && 'bg-sunken text-ink',
        )}
      >
        <UserCircleIcon aria-hidden className="size-6 shrink-0" />
        <span className="hidden truncate lg:inline">{user.username}</span>
        <CaretDownIcon aria-hidden className="hidden size-3.5 shrink-0 lg:inline" />
      </button>
      {open ? <UserPanel id={panelId} username={user.username} email={user.email} /> : null}
    </div>
  )
}

function UserPanel({ id, username, email }: { id: string; username: string; email: string }) {
  const theme = useThemeMode()
  const firstRef = useRef<HTMLParagraphElement>(null)
  const legendId = useId()

  useEffect(() => {
    firstRef.current?.focus()
  }, [])

  return (
    <div
      id={id}
      role="region"
      aria-label="Cuenta"
      className="absolute top-full right-0 z-50 mt-2 w-[min(18rem,calc(100vw-1.5rem))] origin-top-right rounded-xl bg-raised p-2 shadow-pop ring-1 ring-line motion-safe:animate-pop-in"
    >
      <div className="px-2.5 pt-1.5 pb-3">
        <p ref={firstRef} tabIndex={-1} data-focus-target className="text-sm font-semibold text-ink [overflow-wrap:anywhere]">
          {username}
        </p>
        <p className="mt-0.5 text-xs text-ink-3 [overflow-wrap:anywhere]">{email}</p>
      </div>

      <fieldset className="border-t border-line px-2.5 pt-3 pb-3" aria-labelledby={legendId}>
        <legend id={legendId} className="float-left mb-2 w-full text-xs font-medium text-ink-2">
          Tema
        </legend>
        <div className="clear-both grid grid-cols-3 gap-1 rounded-lg bg-sunken p-1">
          {THEMES.map(({ value, label, icon: ThemeIcon }) => (
            <label
              key={value}
              className={cx(
                'flex h-9 cursor-pointer items-center justify-center gap-1.5 rounded-md text-xs font-medium transition-colors pointer-coarse:h-11',
                'has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-focus',
                theme === value ? 'bg-raised text-ink shadow-sm ring-1 ring-line' : 'text-ink-2 hover:text-ink',
              )}
            >
              <input
                type="radio"
                name="theme"
                value={value}
                checked={theme === value}
                onChange={() => setThemeMode(value)}
                className="sr-only"
              />
              <ThemeIcon aria-hidden className="size-4" />
              {label}
            </label>
          ))}
        </div>
      </fieldset>

      <div className="border-t border-line pt-2">
        <button
          type="button"
          onClick={() => sessionStore.signOut('manual')}
          className="flex h-10 w-full items-center gap-2.5 rounded-lg px-2.5 text-sm font-medium text-ink-2 transition-colors hover:bg-sunken hover:text-ink pointer-coarse:h-11"
        >
          <SignOutIcon aria-hidden className="size-4.5" />
          Cerrar sesión
        </button>
      </div>
    </div>
  )
}
