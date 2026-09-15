<#
  Publie une version : compilation propre en release SIGNÉE sur ce poste,
  vérification de ce que l'APK dit de lui-même (version, signature), puis tag,
  push et Release GitHub avec l'APK attaché.

  Rien n'est délégué au CI : la clé de signature reste sur le PC
  (~/.android-keys, keystore.properties ignoré par git), GitHub ne reçoit que
  l'APK final.

  Repris de Portainer Remote, qui avait publié une 1.6.0 contenant l'APK de la
  1.5.0 : d'où le « clean » et la lecture de la version DANS le fichier.

  Usage :
    .\tools\publier.ps1 -Version 0.1.2
    .\tools\publier.ps1 -Version 0.1.2 -Notes notes.md
#>
param(
  [Parameter(Mandatory = $true)][string]$Version,
  [string]$Notes,
  [string]$Titre
)

$ErrorActionPreference = "Stop"
$racine = Split-Path $PSScriptRoot -Parent
Set-Location $racine

function Echec($message) {
  Write-Host ""
  Write-Host "  REFUS : $message" -ForegroundColor Red
  exit 1
}

# git écrit son avancement sur stderr : ne juger que le code de sortie.
function Executer($programme, [string[]]$arguments) {
  $ancien = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  & $programme @arguments 2>&1 | ForEach-Object { Write-Host ("  {0}" -f $_) -ForegroundColor DarkGray }
  $code = $LASTEXITCODE
  $ErrorActionPreference = $ancien
  if ($code -ne 0) { Echec ("{0} {1} a échoué (code {2})" -f $programme, ($arguments -join " "), $code) }
}

# ------------------------------------------------------- ce qui est déclaré
if (-not (Test-Path "keystore.properties")) { Echec "keystore.properties absent : lance d'abord signing\new-keystore.ps1" }
$gradle = Get-Content "app\build.gradle.kts" -Raw
if ($gradle -notmatch 'versionName\s*=\s*"([^"]+)"') { Echec "versionName introuvable" }
$declaree = $Matches[1]
if ($gradle -notmatch 'versionCode\s*=\s*(\d+)') { Echec "versionCode introuvable" }
$code = [int]$Matches[1]
if ($declaree -ne $Version) { Echec "build.gradle.kts déclare $declaree, la commande demande $Version" }
$statut = git status --porcelain
if ($statut) { Echec "des modifications ne sont pas commitées : `n$statut" }

Write-Host ""
Write-Host "Version déclarée : $declaree (code $code)" -ForegroundColor Cyan

# ------------------------------------------------- construire, sans reliquat
Write-Host "Compilation propre (release signée)..." -ForegroundColor Cyan
& .\gradlew.bat clean assembleRelease -q
if ($LASTEXITCODE -ne 0) { Echec "la compilation a échoué" }
$apk = "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apk)) { Echec "aucun APK produit (release non signée → app-release-unsigned.apk ?)" }

# ------------------------------------------- ce que l'APK dit de lui-même
$outils = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Directory | Sort-Object Name | Select-Object -Last 1
$aapt2 = Join-Path $outils.FullName "aapt2.exe"
$apksigner = Join-Path $outils.FullName "apksigner.bat"

$badging = & $aapt2 dump badging $apk | Select-Object -First 1
if ($badging -notmatch "versionCode='(\d+)'") { Echec "versionCode illisible dans l'APK" }
$codeApk = [int]$Matches[1]
if ($badging -notmatch "versionName='([^']+)'") { Echec "versionName illisible dans l'APK" }
$nomApk = $Matches[1]
Write-Host "APK produit      : $nomApk (code $codeApk)" -ForegroundColor Cyan
if ($nomApk -ne $declaree -or $codeApk -ne $code) { Echec "l'APK annonce $nomApk/$codeApk alors que le projet déclare $declaree/$code" }

# --------------------------------------------------------------- signature
# L'empreinte du certificat (publique, pas un secret) est mémorisée dans
# signing/fingerprint.txt à la première publication ; toute version suivante
# signée par une autre clé est refusée : Android n'accepterait pas la mise à jour.
$certs = & $apksigner verify --print-certs -v $apk
$ligne = $certs | Select-String "Signer #1 certificate SHA-256 digest:"
if (-not $ligne) { Echec "APK non signé" }
$empreinte = $ligne.ToString().Split(":")[1].Trim()
$fichierEmpreinte = "signing\fingerprint.txt"
if (Test-Path $fichierEmpreinte) {
  $attendue = (Get-Content $fichierEmpreinte -Raw).Trim()
  if ($empreinte -ne $attendue) { Echec "signée par une autre clé ($empreinte) : la mise à jour en place serait refusée" }
  Write-Host "Signature        : identique aux versions précédentes" -ForegroundColor Green
} else {
  Set-Content $fichierEmpreinte $empreinte -Encoding ascii
  Executer "git" @("add", $fichierEmpreinte)
  Executer "git" @("commit", "-m", "Empreinte du certificat de signature")
  Write-Host "Signature        : première publication, empreinte mémorisée ($empreinte)" -ForegroundColor Green
}
if (-not ($certs | Select-String "v2 scheme \(APK Signature Scheme v2\): true")) { Echec "schéma de signature v2 absent" }

# ----------------------------------------------------------------- publier
$fichier = Join-Path $env:TEMP "DockerDashboard-$Version.apk"
Copy-Item $apk $fichier -Force
$etiquette = "v$Version"
Executer "git" @("tag", $etiquette)
Executer "git" @("push", "origin", "main")
Executer "git" @("push", "origin", $etiquette)

$titre = $Titre
if (-not $titre) { $titre = $Version }
if ($Notes -and (Test-Path $Notes)) {
  Executer "gh" @("release", "create", $etiquette, $fichier, "--title", $titre, "--notes-file", $Notes)
} else {
  Executer "gh" @("release", "create", $etiquette, $fichier, "--title", $titre, "--generate-notes")
}

Write-Host ""
Write-Host "Publié : $etiquette → $fichier" -ForegroundColor Green
