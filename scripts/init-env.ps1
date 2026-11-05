# Crea .env en la raíz del repo a partir de .env.example, sustituyendo cada
# CHANGE_ME_* por un valor aleatorio criptográficamente seguro.
# Uso: powershell -ExecutionPolicy Bypass -File scripts/init-env.ps1 [-Force]
# Los secretos NUNCA se imprimen.

param(
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$examplePath = Join-Path $root '.env.example'
$envPath = Join-Path $root '.env'

if (-not (Test-Path -LiteralPath $examplePath)) {
    Write-Error "No se encuentra .env.example en $root"
    exit 1
}

if ((Test-Path -LiteralPath $envPath) -and -not $Force) {
    Write-Error "Ya existe .env en la raíz del repo. No se sobrescribe. Usa -Force para regenerarlo (se perderán los valores actuales)."
    exit 1
}

# Alfabeto alfanumérico (62 caracteres). Muestreo sin sesgo: se descartan bytes >= 248 (62 * 4).
$alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()

function New-RandomAlnum {
    param([int]$Length)
    $sb = New-Object System.Text.StringBuilder
    $buf = New-Object byte[] 64
    while ($sb.Length -lt $Length) {
        $rng.GetBytes($buf)
        foreach ($b in $buf) {
            if ($b -lt 248 -and $sb.Length -lt $Length) {
                [void]$sb.Append($alphabet[$b % 62])
            }
        }
    }
    return $sb.ToString()
}

function Get-SecretLength {
    param([string]$Key)
    if ($Key -eq 'JWT_SECRET' -or $Key -eq 'INTERNAL_API_KEY') { return 64 }
    if ($Key -like '*_DB_PASSWORD') { return 40 }
    return 48
}

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$content = [System.IO.File]::ReadAllText($examplePath)
$lines = $content -split "`r?`n"
$out = New-Object System.Collections.Generic.List[string]

foreach ($line in $lines) {
    if ($line -match '^([A-Za-z_][A-Za-z0-9_]*)=CHANGE_ME_') {
        $key = $Matches[1]
        $value = New-RandomAlnum -Length (Get-SecretLength -Key $key)
        $out.Add("$key=$value")
    }
    else {
        $out.Add($line)
    }
}

$rng.Dispose()

$result = ($out -join "`r`n")
if ($result -match '(?m)^[A-Za-z_][A-Za-z0-9_]*=CHANGE_ME_') {
    Write-Error "Quedan marcadores CHANGE_ME_ sin sustituir en la plantilla. No se ha escrito .env."
    exit 1
}

[System.IO.File]::WriteAllText($envPath, $result, $utf8NoBom)

if ($Force) {
    Write-Output ".env creado (sobrescrito)"
}
else {
    Write-Output ".env creado"
}
exit 0
