<#
.SYNOPSIS
    Génère le keystore de signature release, hors du dépôt.

.DESCRIPTION
    Rien de ce que ce script produit n'entre dans le dépôt :

      - le keystore est écrit dans -OutDir, par défaut ~/.android-keys,
      - keystore.properties est écrit à la racine du projet mais est déjà
        couvert par .gitignore,
      - les mots de passe sont saisis au clavier, jamais affichés, jamais
        passés en argument de ligne de commande (ils seraient visibles dans la
        liste des processus) : keytool les lit dans des variables
        d'environnement du processus, effacées ensuite.

    Le script refuse d'écraser un keystore existant. Perdre la clé qui a signé
    une version déjà installée oblige à désinstaller l'application pour poser
    la suivante : Android refuse une mise à jour signée par une autre clé.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File signing\new-keystore.ps1
#>

[CmdletBinding()]
param(
    [string]$OutDir = (Join-Path $env:USERPROFILE '.android-keys'),
    [string]$FileName = 'docker-dashboard.jks',
    [string]$Alias = 'docker-dashboard',
    # ~27 ans. Google Play exige une validité au-delà de 2033 ; hors Play, une
    # clé expirée empêche simplement de signer de nouvelles versions.
    [int]$ValidityDays = 10000,
    [string]$DistinguishedName = 'CN=MKDev, O=MKDevTests, C=FR'
)

$ErrorActionPreference = 'Stop'

function Read-Secret {
    param([string]$Prompt)

    while ($true) {
        $first = Read-Host -Prompt $Prompt -AsSecureString
        $again = Read-Host -Prompt "$Prompt (confirmation)" -AsSecureString

        $a = [Runtime.InteropServices.Marshal]::PtrToStringUni(
            [Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($first))
        $b = [Runtime.InteropServices.Marshal]::PtrToStringUni(
            [Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($again))

        if ($a -ne $b) {
            Write-Host 'Les deux saisies diffèrent. On recommence.' -ForegroundColor Yellow
            continue
        }
        if ($a.Length -lt 12) {
            Write-Host 'Trop court : 12 caractères au minimum.' -ForegroundColor Yellow
            continue
        }
        return $a
    }
}

# --- keytool ---------------------------------------------------------------

$keytool = $null
foreach ($candidate in @(
        (Get-Command keytool -ErrorAction SilentlyContinue).Source,
        (Join-Path $env:JAVA_HOME 'bin\keytool.exe'),
        'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe')) {
    if ($candidate -and (Test-Path $candidate)) { $keytool = $candidate; break }
}
if (-not $keytool) {
    throw 'keytool introuvable. Installe un JDK ou renseigne JAVA_HOME.'
}

# --- Emplacement -----------------------------------------------------------

if (-not (Test-Path $OutDir)) {
    New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
}
$storePath = Join-Path $OutDir $FileName

if (Test-Path $storePath) {
    throw "Un keystore existe déjà ici : $storePath`n" +
          "Le script ne l'écrase pas. Supprime-le toi-même si tu es certain " +
          "qu'aucune version publiée n'a été signée avec."
}

$projectRoot = Split-Path $PSScriptRoot -Parent
$propsPath = Join-Path $projectRoot 'keystore.properties'

Write-Host ''
Write-Host "Keystore      : $storePath"
Write-Host "Alias         : $Alias"
Write-Host "Validité      : $ValidityDays jours"
Write-Host "Identité      : $DistinguishedName"
Write-Host "Configuration : $propsPath  (ignoré par git)"
Write-Host ''

$storePw = Read-Secret 'Mot de passe du keystore'
$keyPw = Read-Secret 'Mot de passe de la clé'

# --- Génération ------------------------------------------------------------

try {
    # Passer par l'environnement : un mot de passe en argument apparaîtrait
    # dans la liste des processus de la machine.
    $env:PR_STORE_PW = $storePw
    $env:PR_KEY_PW = $keyPw

    & $keytool -genkeypair `
        -alias $Alias `
        -keyalg RSA -keysize 4096 `
        -validity $ValidityDays `
        -keystore $storePath `
        -storetype PKCS12 `
        -dname $DistinguishedName `
        '-storepass:env' PR_STORE_PW `
        '-keypass:env' PR_KEY_PW

    if ($LASTEXITCODE -ne 0) { throw "keytool a échoué (code $LASTEXITCODE)." }

    # PKCS12 n'a qu'un mot de passe : keytool aligne silencieusement celui de la
    # clé sur celui du keystore. Autant que le fichier de configuration dise la
    # vérité plutôt qu'un mot de passe que Gradle n'utilisera jamais.
    $effectiveKeyPw = $storePw

    $properties = @(
        '# Genere par signing\new-keystore.ps1. Ignore par git : ne jamais versionner.',
        "storeFile=$($storePath -replace '\\', '/')",
        "storePassword=$storePw",
        "keyAlias=$Alias",
        "keyPassword=$effectiveKeyPw"
    )
    # Surtout pas Set-Content -Encoding utf8 : PowerShell 5.1 y ajoute un BOM,
    # et java.util.Properties.load lit en ISO-8859-1 sans le reconnaitre. Le
    # BOM se collerait devant la premiere cle. Latin-1 est justement l'encodage
    # que Properties attend, accents des mots de passe compris.
    [IO.File]::WriteAllLines($propsPath, $properties,
        [Text.Encoding]::GetEncoding('ISO-8859-1'))
}
finally {
    Remove-Item Env:\PR_STORE_PW -ErrorAction SilentlyContinue
    Remove-Item Env:\PR_KEY_PW -ErrorAction SilentlyContinue
    $storePw = $null
    $keyPw = $null
    [GC]::Collect()
}

# --- Vérifications ---------------------------------------------------------

Write-Host ''
Write-Host 'Fait.' -ForegroundColor Green
Write-Host ''

$tracked = & git -C $projectRoot check-ignore keystore.properties 2>$null
if (-not $tracked) {
    Write-Host 'ATTENTION : keystore.properties n''est PAS ignore par git.' -ForegroundColor Red
    Write-Host 'Ne commite rien avant d''avoir corrige .gitignore.' -ForegroundColor Red
} else {
    Write-Host 'keystore.properties est bien ignore par git.'
}

Write-Host ''
Write-Host 'A faire maintenant, toi seul :' -ForegroundColor Cyan
Write-Host "  1. Copier $storePath sur un support hors de cette machine."
Write-Host '  2. Noter le mot de passe dans ton gestionnaire de mots de passe.'
Write-Host ''
Write-Host 'Sans ces deux copies, une machine perdue signifie une application'
Write-Host 'qui ne peut plus etre mise a jour : Android refuse une mise a jour'
Write-Host 'signee par une autre cle, il faut desinstaller et tout reconfigurer.'
