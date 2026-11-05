#!/usr/bin/env bash
# Ejecuta `mvn -B test` de cada servicio Java dentro de Docker.
# Uso:
#   scripts/test-java.sh                     (todos)
#   scripts/test-java.sh auth-service ...    (solo los indicados)

set -euo pipefail

ALL_SERVICES=(auth-service api-gateway inventory-service order-service notification-service)
IMAGE="maven:3.9-eclipse-temurin-17"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ "$#" -eq 0 ]; then
  SERVICES=("${ALL_SERVICES[@]}")
else
  SERVICES=("$@")
fi

for svc in "${SERVICES[@]}"; do
  valid=0
  for known in "${ALL_SERVICES[@]}"; do
    if [ "$svc" = "$known" ]; then valid=1; break; fi
  done
  if [ "$valid" -ne 1 ]; then
    echo "Error: servicio desconocido: '$svc'. Válidos: ${ALL_SERVICES[*]}" >&2
    exit 2
  fi
done

# Ruta del host en formato que entiende Docker Desktop (Windows con Git Bash).
host_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$1"
  else
    echo "$1"
  fi
}

RESULT_NAMES=()
RESULT_STATUS=()
failed=0

for svc in "${SERVICES[@]}"; do
  dir="$ROOT/services/$svc"
  if [ ! -d "$dir" ]; then
    echo "[$svc] NO EXISTE en services/$svc"
    RESULT_NAMES+=("$svc")
    RESULT_STATUS+=("NO EXISTE")
    failed=1
    continue
  fi

  echo "==> [$svc] mvn -B test"
  code=0
  # MSYS_NO_PATHCONV evita que Git Bash reescriba /app y /root/.m2 como rutas de Windows.
  MSYS_NO_PATHCONV=1 docker run --rm \
    -v "$(host_path "$dir"):/app" \
    -v micro-m2:/root/.m2 \
    -w /app \
    "$IMAGE" \
    mvn -B test || code=$?

  RESULT_NAMES+=("$svc")
  if [ "$code" -eq 0 ]; then
    RESULT_STATUS+=("OK")
  else
    RESULT_STATUS+=("FALLO (código $code)")
    failed=1
  fi
done

echo
echo "Resumen:"
for i in "${!RESULT_NAMES[@]}"; do
  printf '  %-24s %s\n' "${RESULT_NAMES[$i]}" "${RESULT_STATUS[$i]}"
done

if [ "$failed" -ne 0 ]; then
  echo "Hay servicios con fallos."
  exit 1
fi

echo "Todos los servicios pasan (${#RESULT_NAMES[@]})."
