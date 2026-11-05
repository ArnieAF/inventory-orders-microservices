import { ApiError } from './client'

/**
 * Mensajes en español por código. Es lo ÚNICO que ve el usuario ante un error:
 * el `message` del servidor nunca se muestra.
 */
export const ERROR_MESSAGES: Readonly<Record<string, string>> = {
  VALIDATION_ERROR: 'Hay datos que no son válidos. Revisa los campos marcados.',
  INVALID_CREDENTIALS: 'Usuario o contraseña incorrectos.',
  UNAUTHORIZED: 'Tu sesión ha caducado. Vuelve a iniciar sesión.',
  FORBIDDEN: 'No tienes permiso para hacer esto.',
  NOT_FOUND: 'No encontramos lo que buscas. Puede que ya no exista.',
  USERNAME_TAKEN: 'Ese nombre de usuario ya está en uso.',
  EMAIL_TAKEN: 'Ese correo ya está registrado.',
  SKU_ALREADY_EXISTS: 'Ya existe un producto con ese SKU.',
  INSUFFICIENT_STOCK: 'No hay stock suficiente para completar la operación.',
  PRODUCT_INACTIVE: 'Alguno de los productos ya no está disponible.',
  INVALID_STATUS_TRANSITION: 'El pedido cambió de estado mientras tanto. Hemos actualizado la información.',
  REGISTRATION_DISABLED: 'El registro de cuentas nuevas está desactivado. Pide acceso a quien administra el sistema.',
  CONFLICT: 'La operación choca con el estado actual. Actualiza e inténtalo de nuevo.',
  TOO_MANY_REQUESTS: 'Demasiadas solicitudes seguidas. Espera un momento e inténtalo de nuevo.',
  SERVICE_UNAVAILABLE: 'El servicio no está disponible ahora mismo. Inténtalo de nuevo en unos segundos.',
  INTERNAL_ERROR: 'Algo falló en el servidor. Inténtalo de nuevo.',
  NETWORK_ERROR: 'No hay conexión con el servidor. Comprueba tu red e inténtalo de nuevo.',
  UNKNOWN_ERROR: 'Ha ocurrido un error inesperado. Inténtalo de nuevo.',
}

const FALLBACK = ERROR_MESSAGES.UNKNOWN_ERROR ?? 'Ha ocurrido un error inesperado.'

/** Mensaje para el usuario. `overrides` permite afinar el texto según el contexto (p. ej. NOT_FOUND al crear un pedido). */
export function errorMessage(error: unknown, overrides: Readonly<Record<string, string>> = {}): string {
  if (error instanceof ApiError) {
    return overrides[error.code] ?? ERROR_MESSAGES[error.code] ?? FALLBACK
  }
  return FALLBACK
}

const GENERIC_FIELD_MESSAGE = 'Revisa este valor.'

/** Errores de campo concretos que el servidor puede devolver para un formulario. */
const FIELD_CODES: Readonly<Record<string, { field: string; message: string }>> = {
  USERNAME_TAKEN: { field: 'username', message: 'Ese nombre de usuario ya está en uso.' },
  EMAIL_TAKEN: { field: 'email', message: 'Ese correo ya está registrado.' },
  SKU_ALREADY_EXISTS: { field: 'sku', message: 'Ya existe un producto con ese SKU.' },
}

/**
 * Traduce un error del servidor a errores por campo del formulario.
 * - Códigos con campo propio (USERNAME_TAKEN, EMAIL_TAKEN, SKU_ALREADY_EXISTS).
 * - VALIDATION_ERROR con `details[].field`: se marca el campo con un texto genérico en español.
 * Los campos que el formulario no conoce se ignoran (el aviso general ya los cubre).
 */
export function fieldErrorsFrom<F extends string>(error: unknown, knownFields: readonly F[]): Partial<Record<F, string>> {
  const result: Partial<Record<F, string>> = {}
  if (!(error instanceof ApiError)) return result
  const isKnown = (field: string): field is F => (knownFields as readonly string[]).includes(field)

  const byCode = FIELD_CODES[error.code]
  if (byCode && isKnown(byCode.field)) {
    result[byCode.field] = byCode.message
    return result
  }

  if (error.code === 'VALIDATION_ERROR') {
    for (const detail of error.details) {
      if (detail.field && isKnown(detail.field) && !result[detail.field]) {
        result[detail.field] = GENERIC_FIELD_MESSAGE
      }
    }
  }
  return result
}
