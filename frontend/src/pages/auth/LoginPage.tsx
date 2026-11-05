import { useMutation } from '@tanstack/react-query'
import { useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { login } from '../../api/auth'
import { errorMessage, fieldErrorsFrom } from '../../api/errors'
import { sessionStore } from '../../auth/sessionStore'
import { useSessionState } from '../../auth/useSession'
import { Button } from '../../components/Button'
import { TextField } from '../../components/Field'
import { Banner } from '../../components/Feedback'
import { validateLogin, type FieldErrors, type LoginField } from '../../domain/validation'
import { useDocumentTitle } from '../../hooks/useDocumentTitle'
import { useFocusFirstInvalid } from '../../hooks/useFocusFirstInvalid'
import { AuthLayout } from './AuthLayout'
import { PasswordField } from './PasswordField'

const FIELDS: readonly LoginField[] = ['username', 'password']

export function LoginPage() {
  useDocumentTitle('Iniciar sesión')
  const { logoutReason } = useSessionState()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [errors, setErrors] = useState<FieldErrors<LoginField>>({})
  const [attempt, setAttempt] = useState(0)
  const formRef = useRef<HTMLFormElement>(null)
  useFocusFirstInvalid(formRef, attempt)

  const mutation = useMutation({
    mutationFn: login,
    // Al guardar la sesión, GuestOnly lleva a la página que se pidió antes del login.
    onSuccess: (auth) => sessionStore.signIn(auth),
    onError: (error) => {
      setErrors(fieldErrorsFrom(error, FIELDS))
      setPassword('')
    },
  })

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (mutation.isPending) return
    const result = validateLogin({ username, password })
    if (!result.ok) {
      setErrors(result.errors)
      setAttempt((value) => value + 1)
      return
    }
    setErrors({})
    mutation.mutate(result.value)
  }

  const sessionEnded = logoutReason === 'expired' || logoutReason === 'unauthorized'

  return (
    <AuthLayout title="Iniciar sesión" subtitle="Entra con tu usuario para gestionar el inventario y los pedidos.">
      <form ref={formRef} noValidate onSubmit={onSubmit} className="flex flex-col gap-5">
        {mutation.isError ? (
          <Banner tone="error" live>
            {errorMessage(mutation.error, { UNAUTHORIZED: 'Usuario o contraseña incorrectos.' })}
          </Banner>
        ) : sessionEnded ? (
          <Banner tone="info">Tu sesión ha caducado. Vuelve a entrar para continuar.</Banner>
        ) : null}

        <TextField
          label="Usuario"
          name="username"
          autoComplete="username"
          autoCapitalize="none"
          spellCheck={false}
          value={username}
          onChange={(event) => setUsername(event.target.value)}
          error={errors.username}
          maxLength={64}
        />
        <PasswordField
          label="Contraseña"
          name="password"
          autoComplete="current-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          error={errors.password}
          maxLength={128}
        />
        <Button type="submit" variant="primary" className="mt-1 w-full" loading={mutation.isPending}>
          {mutation.isPending ? 'Entrando…' : 'Entrar'}
        </Button>
      </form>
      <p className="mt-8 text-sm text-ink-2">
        ¿No tienes cuenta?{' '}
        <Link to="/register" className="font-semibold text-accent-text underline underline-offset-4 hover:no-underline">
          Crear una cuenta
        </Link>
      </p>
    </AuthLayout>
  )
}
