import { useMutation, useQueryClient } from '@tanstack/react-query'
import { isApiError } from '../../api/client'
import { errorMessage } from '../../api/errors'
import { deleteProduct } from '../../api/products'
import { queryKeys } from '../../api/queryKeys'
import type { Product } from '../../api/types'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { Banner } from '../../components/Feedback'
import { showToast } from '../../realtime/center'

export function DeleteProductDialog({ product, onClose }: { product: Product; onClose: () => void }) {
  const queryClient = useQueryClient()
  const mutation = useMutation({
    mutationFn: () => deleteProduct(product.id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.products.all })
      showToast({ tone: 'success', title: 'Producto dado de baja', message: product.name })
      onClose()
    },
    onError: (error) => {
      if (isApiError(error, 'NOT_FOUND')) void queryClient.invalidateQueries({ queryKey: queryKeys.products.all })
    },
  })

  return (
    <Dialog
      open
      variant="alert"
      onClose={onClose}
      dismissible={!mutation.isPending}
      title="¿Dar de baja este producto?"
      description={
        <>
          <span className="font-semibold text-ink">{product.name}</span> dejará de aparecer en el catálogo y no se
          podrá añadir a pedidos nuevos. Los pedidos existentes no cambian.
        </>
      }
      footer={
        <>
          <Button onClick={onClose} disabled={mutation.isPending}>
            Mantener
          </Button>
          <Button variant="danger" onClick={() => mutation.mutate()} loading={mutation.isPending}>
            Dar de baja
          </Button>
        </>
      }
    >
      {mutation.isError ? (
        <Banner tone="error" live>
          {errorMessage(mutation.error, { NOT_FOUND: 'Este producto ya no existe o ya se dio de baja.' })}
        </Banner>
      ) : null}
    </Dialog>
  )
}
