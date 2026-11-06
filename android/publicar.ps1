# Compila o APK release e publica no servidor como nova versão do app.
#
# Uso (na pasta android):
#   .\publicar.ps1 -Notas "Novos gráficos"            # atualização OBRIGATÓRIA (padrão)
#   .\publicar.ps1 -Notas "Ajustes" -Opcional          # usuário pode adiar
#
# Antes: aumente versionCode (e versionName) em app\build.gradle.kts.

param(
    [string]$Notas = "",
    [switch]$Opcional
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

# Config local (não vai pro git): url e adminToken
$props = @{}
Get-Content "publish.properties" | Where-Object { $_ -match '^\s*([^#=]+)=(.*)$' } | ForEach-Object {
    $props[$Matches[1].Trim()] = $Matches[2].Trim()
}

$gradle = Get-Content "app\build.gradle.kts" -Raw
$code = [int][regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value
$name = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
Write-Host "Publicando versão $name (versionCode $code)..." -ForegroundColor Cyan

# JDK 17–21 (Gradle 8.9 não roda com Java 25, que é o embutido nas versões novas do Android Studio).
# Usa o JBR 21 que o Android Studio baixa em ~/.jdks; senão, o JBR embutido.
if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter "jbr-21*" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
    else {
        foreach ($p in @("C:\Program Files\Android\Android Studio\jbr", "$env:LOCALAPPDATA\Programs\Android Studio\jbr")) {
            if (Test-Path $p) { $env:JAVA_HOME = $p; break }
        }
    }
}

& .\gradlew.bat assembleRelease
if ($LASTEXITCODE -ne 0) { throw "Falha no build" }

$apk = "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apk)) { throw "APK não encontrado em $apk" }

$force = if ($Opcional) { "false" } else { "true" }
$query = "versionCode=$code&versionName=$([uri]::EscapeDataString($name))&force=$force&notes=$([uri]::EscapeDataString($Notas))"

$result = Invoke-RestMethod -Method Put -Uri "$($props.url)/api/admin/apk?$query" `
    -Headers @{ Authorization = "Bearer $($props.adminToken)" } `
    -InFile $apk -ContentType "application/vnd.android.package-archive"

Write-Host "Publicado!" -ForegroundColor Green
$result | Format-List
if ($props.downloadKey) {
    Write-Host "Link de instalação (para novos usuários):" -ForegroundColor Cyan
    Write-Host "  $($props.url)/baixar/$($props.downloadKey)"
}
