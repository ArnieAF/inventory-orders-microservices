import { useState } from 'react'
import { IconButton } from '../../components/Button'
import { TextField, type TextFieldProps } from '../../components/Field'
import { EyeIcon, EyeSlashIcon } from '../../components/icons'

export function PasswordField(props: Omit<TextFieldProps, 'type' | 'trailing'>) {
  const [visible, setVisible] = useState(false)
  return (
    <TextField
      {...props}
      type={visible ? 'text' : 'password'}
      autoCapitalize="none"
      spellCheck={false}
      trailing={
        <IconButton
          label={visible ? 'Ocultar contraseña' : 'Mostrar contraseña'}
          aria-pressed={visible}
          onClick={() => setVisible((value) => !value)}
          className="[&_svg]:size-4.5"
        >
          {visible ? <EyeSlashIcon aria-hidden /> : <EyeIcon aria-hidden />}
        </IconButton>
      }
    />
  )
}
