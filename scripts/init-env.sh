#!/usr/bin/env bash
# Crea .env en la raíz del repo a partir de .env.example, sustituyendo cada
# CHANGE_ME_* por un valor aleatorio criptográficamente seguro.
# Uso: scripts/init-env.sh [--force]
# Los secretos NUNCA se imprimen.

set -euo pipefail

FORCE=0
for arg in "$@"; do
  case "$arg" in
    --force) FORCE=1 ;;
    -h|--help)
      echo "Uso: scripts/init-env.sh [--force]"
      exit 0
      ;;
    *)
      echo "Error: argumento desconocido: $arg" >&2
      echo "Uso: scripts/init-env.sh [--force]" >&2
      exit 2
      ;;
  esac
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXAMPLE_FILE="$ROOT/.env.example"
ENV_FILE="$ROOT/.env"

if [ ! -f "$EXAMPLE_FILE" ]; then
  echo "Error: no se encuentra .env.example en $ROOT" >&2
  exit 1
fi

if [ -e "$ENV_FILE" ] && [ "$FORCE" -ne 1 ]; then
  echo "Error: ya existe .env en la raíz del repo. No se sobrescribe." >&2
  echo "Usa --force para regenerarlo (se perderán los valores actuales)." >&2
  exit 1
fi

# Genera N caracteres alfanuméricos aleatorios desde /dev/urandom.
# El pipefail se desactiva solo en la subshell: head cierra el pipe (SIGPIPE) a propósito.
random_alnum() {
  local len="$1" out
  out="$( (set +o pipefail; LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c "$len") )"
  if [ "${#out}" -ne "$len" ]; then
    echo "Error: no se pudo generar un valor aleatorio de $len caracteres" >&2
    exit 1
  fi
  printf '%s' "$out"
}

secret_length() {
  case "$1" in
    JWT_SECRET|INTERNAL_API_KEY) echo 64 ;;
    *_DB_PASSWORD) echo 40 ;;
    *) echo 48 ;;
  esac
}

TMP_FILE="$(mktemp "$ROOT/.env.tmp.XXXXXX")"
trap 'rm -f "$TMP_FILE"' EXIT
umask 077

while IFS= read -r line || [ -n "$line" ]; do
  if [[ "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)=CHANGE_ME_ ]]; then
    key="${BASH_REMATCH[1]}"
    value="$(random_alnum "$(secret_length "$key")")"
    printf '%s=%s\n' "$key" "$value" >> "$TMP_FILE"
  else
    printf '%s\n' "$line" >> "$TMP_FILE"
  fi
done < "$EXAMPLE_FILE"

if grep -qE '^[A-Za-z_][A-Za-z0-9_]*=CHANGE_ME_' "$TMP_FILE"; then
  echo "Error: quedan marcadores CHANGE_ME_ sin sustituir. No se ha escrito .env." >&2
  exit 1
fi

mv -f "$TMP_FILE" "$ENV_FILE"
trap - EXIT

if [ "$FORCE" -eq 1 ]; then
  echo ".env creado (sobrescrito)"
else
  echo ".env creado"
fi
