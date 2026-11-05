import { useMutation } from '@tanstack/react-query'
import { useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { register } from '../../api/auth'
import { isApiError } from '../../api/client'
import { errorMessage, fieldErrorsFrom } from '../../api/errors'
import { sessionStore } from '../../auth/sessionStore'
import { Button } from '../../components/Button'
import { TextField } from '../../components/Field'
import { Banner } from '../../components/Feedback'
import { LIMITS, validateRegister, type FieldErrors, type RegisterField } from '../../domain/validation'
import { useDocumentTitle } from '../../hooks/useDocumentTitle'
import { useFocusFirstInvalid } from '../../hooks/useFocusFirstInvalid'
import { AuthLayout } from './AuthLayout'
import { PasswordField } from './PasswordField'

const FIELDS: readonly RegisterField[] = ['username', 'email', 'password', 'confirmPassword']

export function RegisterPage() {
  useDocumentTitle('Crear cuenta')
  const [values, setValues] = useState({ username: '', email: '', password: '', confirmPassword: '' })
  const [errors, setErrors] = useState<FieldErrors<RegisterField>>({})
  const [attempt, setAttempt] = useState(0)
  const formRef = useRef<HTMLFormElement>(null)
  useFocusFirstInvalid(formRef, attempt)

  const mutation = useMutation({
    mutationFn: register,
    onSuccess: (auth) => sessionStore.signIn(auth),
    onError: (error) => {
      setErrors(fieldErrorsFrom(error, FIELDS))
      setAttempt((value) => value + 1)
    },
  })

  const set = (field: keyof typeof values) => (event: { target: { value: string } }) =>
    setValues((current) => ({ ...current, [field]: event.target.value }))

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (mutation.isPending) return
    const result = validateRegister(values)
    if (!result.ok) {
      setErrors(result.errors)
      setAttempt((value) => value + 1)
      return
    }
    setErrors({})
    mutation.mutate(result.value)
  }

  // Usuario o correo repetidos se explican en su propio campo; el resto, en el aviso general.
  const fieldLevel = isApiError(mutation.error, 'USERNAME_TAKEN') || isApiError(mutation.error, 'EMAIL_TAKEN')

  return (
    <AuthLayout title="Crear cuenta" subtitle="Todas las cuentas pueden gestionar productos y pedidos.">
      <form ref={formRef} noValidate onSubmit={onSubmit} className="flex flex-col gap-5">
        {mutation.isError && !fieldLevel ? (
          <Banner tone="error" live>
            {errorMessage(mutation.error)}
          </Banner>
        ) : null}

        <TextField
          label="Usuario"
          name="username"
          autoComplete="username"
          autoCapitalize="none"
          spellCheck={false}
          value={values.username}
          onChange={set('username')}
          error={errors.username}
          hint={`${LIMITS.username.min} a ${LIMITS.username.max} caracteres: letras sin tilde, números, _ . -`}
          maxLength={LIMITS.username.max}
        />
        <TextField
          label="Correo"
          name="email"
          type="email"
          inputMode="email"
          autoComplete="email"
          autoCapitalize="none"
          spellCheck={false}
          value={values.email}
          onChange={set('email')}
          error={errors.email}
          maxLength={LIMITS.email.max}
        />
        <PasswordField
          label="Contraseña"
          name="password"
          autoComplete="new-password"
          value={values.password}
          onChange={set('password')}
          error={errors.password}
          hint={`Entre ${LIMITS.password.min} y ${LIMITS.password.max} caracteres.`}
          maxLength={LIMITS.password.max}
        />
        <PasswordField
          label="Repite la contraseña"
          name="confirmPassword"
          autoComplete="new-password"
          value={values.confirmPassword}
          onChange={set('confirmPassword')}
          error={errors.confirmPassword}
          maxLength={LIMITS.password.max}
        />
        <Button type="submit" variant="primary" className="mt-1 w-full" loading={mutation.isPending}>
          {mutation.isPending ? 'Creando cuenta…' : 'Crear cuenta'}
        </Button>
      </form>
      <p className="mt-8 text-sm text-ink-2">
        ¿Ya tienes cuenta?{' '}
        <Link to="/login" className="font-semibold text-accent-text underline underline-offset-4 hover:no-underline">
          Iniciar sesión
        </Link>
      </p>
    </AuthLayout>
  )
}
