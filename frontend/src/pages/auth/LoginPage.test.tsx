import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { GuestOnly, RequireAuth } from '../../auth/guards'
import { sessionStore } from '../../auth/sessionStore'
import { TEST_USER, errorResponse, jsonResponse, mockFetch, renderRoutes, signInTestUser } from '../../test/utils'
import { LoginPage } from './LoginPage'

function renderLogin(initialEntry = '/login') {
  return renderRoutes(
    [
      {
        path: '/login',
        element: (
          <GuestOnly>
            <LoginPage />
          </GuestOnly>
        ),
      },
      {
        path: '/',
        element: (
          <RequireAuth>
            <p>Panel privado</p>
          </RequireAuth>
        ),
      },
      {
        path: '/orders',
        element: (
          <RequireAuth>
            <p>Lista de pedidos</p>
          </RequireAuth>
        ),
      },
    ],
    initialEntry,
  )
}

describe('LoginPage', () => {
  it('valida en cliente y lleva el foco al primer campo con error', async () => {
    const { requests } = mockFetch({})
    const user = userEvent.setup()
    renderLogin()

    await user.click(await screen.findByRole('button', { name: 'Entrar' }))

    expect(await screen.findByText('Escribe tu nombre de usuario.')).toBeInTheDocument()
    expect(screen.getByText('Escribe tu contraseña.')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText('Usuario')).toHaveFocus())
    expect(requests).toHaveLength(0)
  })

  it('credenciales incorrectas: aviso genérico en español, sin texto del servidor ni redirección', async () => {
    mockFetch({ 'POST /api/auth/login': () => errorResponse(401, 'INVALID_CREDENTIALS') })
    const user = userEvent.setup()
    renderLogin()

    await user.type(await screen.findByLabelText('Usuario'), 'marta.ops')
    await user.type(screen.getByLabelText('Contraseña'), 'mal-password')
    await user.click(screen.getByRole('button', { name: 'Entrar' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Usuario o contraseña incorrectos.')
    expect(screen.queryByText(/RAW SERVER MESSAGE/)).not.toBeInTheDocument()
    expect(screen.getByLabelText('Contraseña')).toHaveValue('')
    expect(sessionStore.getState().session).toBeNull()
  })

  it('al entrar guarda la sesión y vuelve a la página que se pidió', async () => {
    const { requests } = mockFetch({
      'POST /api/auth/login': () =>
        jsonResponse({ accessToken: 'jwt-ok', tokenType: 'Bearer', expiresIn: 3600, user: TEST_USER }),
    })
    const user = userEvent.setup()
    // Sin sesión, /orders redirige a /login recordando el destino.
    renderLogin('/orders')

    await user.type(await screen.findByLabelText('Usuario'), 'marta.ops')
    await user.type(screen.getByLabelText('Contraseña'), 'contraseña-buena')
    await user.click(screen.getByRole('button', { name: 'Entrar' }))

    expect(await screen.findByText('Lista de pedidos')).toBeInTheDocument()
    expect(requests[0]?.body).toEqual({ username: 'marta.ops', password: 'contraseña-buena' })
    expect(sessionStore.getState().session?.token).toBe('jwt-ok')
  })

  it('avisa de que la sesión caducó tras un 401', async () => {
    mockFetch({})
    signInTestUser()
    sessionStore.signOut('unauthorized')
    renderLogin()
    expect(await screen.findByText('Tu sesión ha caducado. Vuelve a entrar para continuar.')).toBeInTheDocument()
  })

  it('permite mostrar la contraseña', async () => {
    mockFetch({})
    const user = userEvent.setup()
    renderLogin()
    const password = await screen.findByLabelText('Contraseña')
    expect(password).toHaveAttribute('type', 'password')
    await user.click(screen.getByRole('button', { name: 'Mostrar contraseña' }))
    expect(password).toHaveAttribute('type', 'text')
  })
})
