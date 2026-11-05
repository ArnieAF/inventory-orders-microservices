import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useId, useRef, useState, type FormEvent } from 'react'
import { isApiError } from '../../api/client'
import { errorMessage, fieldErrorsFrom } from '../../api/errors'
import { createProduct, updateProduct } from '../../api/products'
import { queryKeys } from '../../api/queryKeys'
import type { CreateProductRequest, Product, UpdateProductRequest } from '../../api/types'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { TextAreaField, TextField } from '../../components/Field'
import { Banner } from '../../components/Feedback'
import { formatInteger } from '../../domain/format'
import {
  EMPTY_PRODUCT_FORM,
  LIMITS,
  validateCreateProduct,
  validateUpdateProduct,
  type FieldErrors,
  type ProductField,
  type ProductFormValues,
} from '../../domain/validation'
import { useFocusFirstInvalid } from '../../hooks/useFocusFirstInvalid'
import { showToast } from '../../realtime/center'

type Payload =
  | { kind: 'create'; body: CreateProductRequest }
  | { kind: 'update'; id: string; body: UpdateProductRequest }

const FIELDS: readonly ProductField[] = ['sku', 'name', 'description', 'price', 'stock', 'lowStockThreshold']

function toFormValues(product: Product): ProductFormValues {
  return {
    sku: product.sku,
    name: product.name,
    description: product.description ?? '',
    price: product.price.toFixed(2).replace('.', ','),
    stock: String(product.stock),
    lowStockThreshold: String(product.lowStockThreshold),
  }
}

export interface ProductFormDialogProps {
  /** `null` = crear. */
  product: Product | null
  onClose: () => void
}

export function ProductFormDialog({ product, onClose }: ProductFormDialogProps) {
  const editing = product !== null
  const formId = useId()
  const formRef = useRef<HTMLFormElement>(null)
  const queryClient = useQueryClient()
  const [values, setValues] = useState<ProductFormValues>(() => (product ? toFormValues(product) : EMPTY_PRODUCT_FORM))
  const [errors, setErrors] = useState<FieldErrors<ProductField>>({})
  const [attempt, setAttempt] = useState(0)
  useFocusFirstInvalid(formRef, attempt)

  const mutation = useMutation({
    mutationFn: (payload: Payload) =>
      payload.kind === 'update' ? updateProduct(payload.id, payload.body) : createProduct(payload.body),
    onSuccess: (saved) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.products.all })
      showToast({
        tone: 'success',
        title: editing ? 'Producto actualizado' : 'Producto creado',
        message: saved.name,
      })
      onClose()
    },
    onError: (error) => {
      setErrors(fieldErrorsFrom(error, FIELDS))
      setAttempt((value) => value + 1)
      if (isApiError(error, 'NOT_FOUND')) void queryClient.invalidateQueries({ queryKey: queryKeys.products.all })
    },
  })

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (mutation.isPending) return
    if (product) {
      const result = validateUpdateProduct(values)
      if (!result.ok) return fail(result.errors)
      setErrors({})
      mutation.mutate({ kind: 'update', id: product.id, body: result.value })
    } else {
      const result = validateCreateProduct(values)
      if (!result.ok) return fail(result.errors)
      setErrors({})
      mutation.mutate({ kind: 'create', body: result.value })
    }
  }

  function fail(next: FieldErrors<ProductField>) {
    setErrors(next)
    setAttempt((value) => value + 1)
  }

  const set = (field: keyof ProductFormValues) => (event: { target: { value: string } }) =>
    setValues((current) => ({ ...current, [field]: event.target.value }))

  const showBanner = mutation.isError && !isApiError(mutation.error, 'SKU_ALREADY_EXISTS')

  return (
    <Dialog
      open
      onClose={onClose}
      dismissible={!mutation.isPending}
      title={editing ? 'Editar producto' : 'Nuevo producto'}
      description={editing ? 'El stock no se cambia aquí: usa «Ajustar stock» para dejar constancia del motivo.' : undefined}
      footer={
        <>
          <Button onClick={onClose} disabled={mutation.isPending}>
            Cancelar
          </Button>
          <Button type="submit" form={formId} variant="primary" loading={mutation.isPending}>
            {editing ? 'Guardar cambios' : 'Crear producto'}
          </Button>
        </>
      }
    >
      <form id={formId} ref={formRef} noValidate onSubmit={onSubmit} className="flex flex-col gap-5">
        {showBanner ? (
          <Banner tone="error" live>
            {errorMessage(mutation.error, { NOT_FOUND: 'Este producto ya no existe o se dio de baja.' })}
          </Banner>
        ) : null}

        <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
          <TextField
            label="SKU"
            name="sku"
            value={values.sku}
            onChange={(event) => setValues((current) => ({ ...current, sku: event.target.value.toUpperCase() }))}
            error={errors.sku}
            hint="Letras, números y . _ -"
            maxLength={LIMITS.sku.max}
            autoCapitalize="characters"
            autoComplete="off"
            spellCheck={false}
            className="font-mono"
          />
          <TextField
            label="Precio"
            name="price"
            inputMode="decimal"
            placeholder="0,00"
            value={values.price}
            onChange={set('price')}
            error={errors.price}
            autoComplete="off"
            maxLength={9}
            className="tabular-nums"
          />
        </div>

        <TextField
          label="Nombre"
          name="name"
          value={values.name}
          onChange={set('name')}
          error={errors.name}
          maxLength={LIMITS.productName.max}
          autoComplete="off"
          aside={`${values.name.length}/${LIMITS.productName.max}`}
        />

        <TextAreaField
          label="Descripción"
          name="description"
          value={values.description}
          onChange={set('description')}
          error={errors.description}
          maxLength={LIMITS.description.max}
          rows={4}
          aside={`Opcional · ${values.description.length}/${LIMITS.description.max}`}
        />

        <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
          {editing ? (
            <div className="flex flex-col gap-1.5">
              <span className="text-sm font-medium text-ink">Stock actual</span>
              <p className="flex h-10 items-center rounded-lg bg-sunken px-3 text-sm text-ink-2 tabular-nums pointer-coarse:h-11">
                {formatInteger(product.stock)} unidades
              </p>
            </div>
          ) : (
            <TextField
              label="Stock inicial"
              name="stock"
              inputMode="numeric"
              value={values.stock}
              onChange={set('stock')}
              error={errors.stock}
              autoComplete="off"
              maxLength={10}
              className="tabular-nums"
            />
          )}
          <TextField
            label="Aviso de stock bajo"
            name="lowStockThreshold"
            inputMode="numeric"
            value={values.lowStockThreshold}
            onChange={set('lowStockThreshold')}
            error={errors.lowStockThreshold}
            hint="Se avisa cuando el stock llega a este número o menos."
            autoComplete="off"
            maxLength={10}
            className="tabular-nums"
          />
        </div>
      </form>
    </Dialog>
  )
}
