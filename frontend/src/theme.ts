import { useSyncExternalStore } from 'react'

export type ThemeMode = 'system' | 'light' | 'dark'

const STORAGE_KEY = 'micro.theme'
const listeners = new Set<() => void>()

function readStored(): ThemeMode {
  try {
    const value = window.localStorage.getItem(STORAGE_KEY)
    return value === 'light' || value === 'dark' || value === 'system' ? value : 'system'
  } catch {
    return 'system'
  }
}

function systemQuery(): MediaQueryList | null {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia('(prefers-color-scheme: dark)')
    : null
}

let mode: ThemeMode = typeof window !== 'undefined' ? readStored() : 'system'

function apply() {
  if (typeof document === 'undefined') return
  const dark = mode === 'dark' || (mode === 'system' && (systemQuery()?.matches ?? false))
  const root = document.documentElement
  root.classList.toggle('dark', dark)
  root.style.colorScheme = dark ? 'dark' : 'light'
}

systemQuery()?.addEventListener('change', () => {
  if (mode === 'system') apply()
})

export function getThemeMode(): ThemeMode {
  return mode
}

export function setThemeMode(next: ThemeMode): void {
  mode = next
  try {
    window.localStorage.setItem(STORAGE_KEY, next)
  } catch {
    // Sin almacenamiento: el tema dura lo que la pestaña.
  }
  apply()
  for (const listener of listeners) listener()
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function useThemeMode(): ThemeMode {
  return useSyncExternalStore(subscribe, getThemeMode, getThemeMode)
}
