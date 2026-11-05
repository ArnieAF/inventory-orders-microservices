import { QueryClientProvider } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { createQueryClient } from './api/queryClient'
import { sessionStore } from './auth/sessionStore'
import { notificationCenter } from './realtime/center'
import { RealtimeProvider } from './realtime/RealtimeProvider'
import { routes } from './routes'

const router = createBrowserRouter(routes)

export function App() {
  const [queryClient] = useState(createQueryClient)

  // Al cerrar sesión (manual, caducidad o 401) no debe quedar nada del usuario anterior.
  useEffect(
    () =>
      sessionStore.onSignOut(() => {
        queryClient.clear()
        notificationCenter.reset()
      }),
    [queryClient],
  )

  return (
    <QueryClientProvider client={queryClient}>
      <RealtimeProvider>
        <RouterProvider router={router} />
      </RealtimeProvider>
    </QueryClientProvider>
  )
}
