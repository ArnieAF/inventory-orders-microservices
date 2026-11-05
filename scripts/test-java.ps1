# Ejecuta `mvn -B test` de cada servicio Java dentro de Docker.
# Uso:
#   powershell -ExecutionPolicy Bypass -File scripts/test-java.ps1                    (todos)
#   powershell -ExecutionPolicy Bypass -File scripts/test-java.ps1 auth-service       (solo los indicados)

param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Services
)

$ErrorActionPreference = 'Stop'

$allServices = @('auth-service', 'api-gateway', 'inventory-service', 'order-service', 'notification-service')

if (-not $Services -or $Services.Count -eq 0) {
    $Services = $allServices
}

foreach ($svc in $Services) {
    if ($allServices -notcontains $svc) {
        Write-Error "Servicio desconocido: '$svc'. Válidos: $($allServices -join ', ')"
        exit 2
    }
}

$root = Split-Path -Parent $PSScriptRoot
$image = 'maven:3.9-eclipse-temurin-17'
$results = New-Object System.Collections.Generic.List[object]

foreach ($svc in $Services) {
    $servicePath = Join-Path $root (Join-Path 'services' $svc)
    if (-not (Test-Path -LiteralPath $servicePath)) {
        Write-Output "[$svc] NO EXISTE en services/$svc"
        $results.Add([pscustomobject]@{ Servicio = $svc; Resultado = 'NO EXISTE' })
        continue
    }

    $absPath = (Resolve-Path -LiteralPath $servicePath).Path
    Write-Output "==> [$svc] mvn -B test"

    & docker run --rm `
        -v "${absPath}:/app" `
        -v micro-m2:/root/.m2 `
        -w /app `
        $image `
        mvn -B test

    $code = $LASTEXITCODE
    if ($code -eq 0) {
        $results.Add([pscustomobject]@{ Servicio = $svc; Resultado = 'OK' })
    }
    else {
        $results.Add([pscustomobject]@{ Servicio = $svc; Resultado = "FALLO (código $code)" })
    }
}

Write-Output ''
Write-Output 'Resumen:'
$results | Format-Table -AutoSize | Out-String | Write-Output

$failed = @($results | Where-Object { $_.Resultado -ne 'OK' })
if ($failed.Count -gt 0) {
    Write-Output "Fallaron $($failed.Count) de $($results.Count) servicio(s)."
    exit 1
}

Write-Output "Todos los servicios pasan ($($results.Count))."
exit 0
