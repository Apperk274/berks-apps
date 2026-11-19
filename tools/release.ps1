<#
.SYNOPSIS
Builds signed release APKs, checks them, and stages them with an updated app catalog in dist/.

.DESCRIPTION
For each selected app whose version is not released yet: runs assembleRelease, checks
that the APK is the expected package, has the version from app/build.gradle.kts and is
signed with the certificate pinned in the catalog, and copies it to
dist/<name>-<version>.apk. The catalog with the new version, download URL and SHA-256
is written to dist/catalog.json. The catalog in the working tree is not changed, because
it must only be committed after the GitHub releases exist.

An app is skipped when the catalog already lists its current versionCode with a
download and its release tag exists on GitHub.

Without -Publish the script prints the commands that publish. With -Publish it runs
them: create the releases, then copy dist/catalog.json over the catalog, commit, push.

.EXAMPLE
powershell -ExecutionPolicy Bypass -File tools\release.ps1
powershell -ExecutionPolicy Bypass -File tools\release.ps1 -App autocaster
powershell -ExecutionPolicy Bypass -File tools\release.ps1 -App berksapps -Publish
#>
[CmdletBinding()]
param(
    [ValidateSet('autocaster', 'berksapps', 'all')]
    [string[]]$App = @('all'),
    [string]$AutoCasterRepo,
    [string]$GitHubRepo = 'Apperk274/berks-apps',
    [switch]$Publish
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2

$root = Split-Path -Parent $PSScriptRoot
if (-not $AutoCasterRepo) {
    $AutoCasterRepo = Join-Path (Split-Path -Parent $root) 'android-auto-caster'
}
$catalogRelative = 'app/src/main/assets/catalog.json'
$catalogPath = Join-Path $root 'app\src\main\assets\catalog.json'
$dist = Join-Path $root 'dist'
$stagedCatalog = Join-Path $dist 'catalog.json'
$utf8 = New-Object System.Text.UTF8Encoding $false

$apps = [ordered]@{
    autocaster = @{
        Repo = $AutoCasterRepo; Package = 'dev.personal.autocaster'
        File = 'autocaster'; Tag = 'autocaster-v'; Title = 'Auto Caster'
    }
    berksapps  = @{
        Repo = $root; Package = 'dev.personal.berksapps'
        File = 'berksapps'; Tag = 'berksapps-v'; Title = "Berk's Apps"
    }
}

if ($App -contains 'all') { $selected = @($apps.Keys) } else { $selected = @($apps.Keys | Where-Object { $App -contains $_ }) }

function Get-BuildTools {
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $dir = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $dir) { throw "No Android build-tools found under $sdk" }
    return $dir.FullName
}

function ConvertTo-JsonString([string]$Value) {
    $escaped = $Value.Replace('\', '\\').Replace('"', '\"').Replace("`r", '').Replace("`n", '\n').Replace("`t", '\t')
    return '"' + $escaped + '"'
}

function Format-JsonValue($Value) {
    if ($null -eq $Value) { return 'null' }
    if ($Value -is [string]) { return ConvertTo-JsonString $Value }
    return [string]$Value
}

function Read-Catalog {
    return (Get-Content -Raw -Encoding UTF8 $catalogPath) | ConvertFrom-Json
}

function Write-Catalog($Catalog, [string]$Path) {
    $fields = 'name', 'package', 'description', 'version', 'versionCode', 'apkUrl', 'apkSha256', 'signingCertSha256'
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('{')
    $lines.Add('  "schemaVersion": ' + $Catalog.schemaVersion + ',')
    $lines.Add('  "apps": [')
    $entries = @($Catalog.apps)
    for ($i = 0; $i -lt $entries.Count; $i++) {
        $lines.Add('    {')
        for ($f = 0; $f -lt $fields.Count; $f++) {
            $name = $fields[$f]
            $value = $null
            if ($entries[$i].PSObject.Properties.Name -contains $name) { $value = $entries[$i].$name }
            $comma = ','
            if ($f -eq $fields.Count - 1) { $comma = '' }
            $lines.Add('      "' + $name + '": ' + (Format-JsonValue $value) + $comma)
        }
        $close = '    },'
        if ($i -eq $entries.Count - 1) { $close = '    }' }
        $lines.Add($close)
    }
    $lines.Add('  ]')
    $lines.Add('}')
    $text = ($lines -join "`n") + "`n"
    [System.IO.File]::WriteAllText($Path, $text, $utf8)
}

function Get-Entry($Catalog, [string]$Package) {
    $entry = @($Catalog.apps | Where-Object { $_.package -eq $Package })
    if ($entry.Count -ne 1) { throw "The catalog needs exactly one entry for $Package" }
    return $entry[0]
}

function Get-SourceVersion([string]$Repo) {
    $gradle = Join-Path $Repo 'app\build.gradle.kts'
    if (-not (Test-Path $gradle)) { throw "No Gradle project at $Repo" }
    $text = Get-Content -Raw -Encoding UTF8 $gradle
    if ($text -notmatch '(?m)^val appVersionCode = ([0-9]+)\s*$') { throw "No appVersionCode in $gradle" }
    $code = [int]$Matches[1]
    if ($text -notmatch '(?m)^val appVersionName = "([^"]+)"\s*$') { throw "No appVersionName in $gradle" }
    return @{ Code = $code; Name = $Matches[1] }
}

function Test-ReleaseExists([string]$Tag) {
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        throw "The GitHub CLI (gh) is needed to check whether release $Tag exists."
    }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = (& gh release view $Tag --repo $GitHubRepo --json tagName 2>&1 | Out-String)
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($code -eq 0) { return $true }
    if ($output -match 'release not found|Could not resolve to a Repository|HTTP 404') { return $false }
    throw "Could not check release $Tag on ${GitHubRepo}: $($output.Trim())"
}

function Invoke-ReleaseBuild([string]$Repo) {
    Push-Location $Repo
    try {
        & mise exec -- .\gradlew.bat assembleRelease --no-daemon -q | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "assembleRelease failed in $Repo" }
    } finally {
        Pop-Location
    }
    $apk = Join-Path $Repo 'app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path $apk)) { throw "No release APK at $apk" }
    return $apk
}

function Get-ApkInfo([string]$Apk, [string]$BuildTools) {
    $badging = & (Join-Path $BuildTools 'aapt2.exe') dump badging $Apk
    if ($LASTEXITCODE -ne 0) { throw "aapt2 could not read $Apk" }
    $line = @($badging | Where-Object { $_ -like 'package:*' })[0]
    if ($line -notmatch "name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'") {
        throw "Could not read package and version from $Apk"
    }
    $info = @{ Package = $Matches[1]; VersionCode = [int]$Matches[2]; VersionName = $Matches[3] }
    Push-Location $root
    try {
        $certs = & mise exec -- cmd /c (Join-Path $BuildTools 'apksigner.bat') verify --print-certs $Apk
        if ($LASTEXITCODE -ne 0) { throw "apksigner could not verify $Apk" }
    } finally {
        Pop-Location
    }
    $digests = @($certs | Where-Object { $_ -match 'certificate SHA-256 digest: ([0-9a-f]{64})' } | ForEach-Object { $Matches[1] })
    if ($digests.Count -ne 1) { throw "$Apk must have exactly one signer, found $($digests.Count)" }
    $info.Cert = $digests[0]
    return $info
}

function Invoke-Step($Command) {
    $exe = $Command[0]
    $rest = @($Command[1..($Command.Count - 1)])
    Write-Host ('> ' + ($Command -join ' '))
    & $exe @rest
    if ($LASTEXITCODE -ne 0) { throw "$exe failed with exit code $LASTEXITCODE" }
}

if ($Publish) {
    $status = @(& git -C $root status --porcelain)
    if ($status.Count -gt 0) {
        throw "Commit and push your changes first. Uncommitted: $($status -join '; ')"
    }
}

$buildTools = Get-BuildTools
New-Item -ItemType Directory -Force $dist | Out-Null
$released = New-Object System.Collections.Generic.List[object]
$catalogBefore = [System.IO.File]::ReadAllText($catalogPath)
$final = Read-Catalog

foreach ($key in $selected) {
    $spec = $apps[$key]
    Write-Host "== $($spec.Title) =="
    $source = Get-SourceVersion $spec.Repo
    $entry = Get-Entry $final $spec.Package
    $tag = "$($spec.Tag)$($source.Name)"
    $listed = $entry.apkUrl -and ([int]$entry.versionCode -eq $source.Code)

    if ($entry.apkUrl -and ([int]$entry.versionCode -gt $source.Code)) {
        throw "$($spec.Title): the catalog already lists versionCode $($entry.versionCode), the source has $($source.Code)."
    }
    if ($listed -or $Publish) {
        $exists = Test-ReleaseExists $tag
        if ($listed -and $exists) {
            Write-Host "  skipped: $($source.Name) (versionCode $($source.Code)) is already released as $tag"
            continue
        }
        if ($exists) {
            throw "$($spec.Title): release $tag already exists but the catalog does not list it. Bump the version, or finish publishing the catalog."
        }
    }

    if ($key -eq 'berksapps') {
        $keptUrl = $entry.apkUrl
        $keptHash = $entry.apkSha256
        $entry.apkUrl = $null
        $entry.apkSha256 = $null
        Write-Catalog $final $catalogPath
        $entry.apkUrl = $keptUrl
        $entry.apkSha256 = $keptHash
    }
    try {
        $apk = Invoke-ReleaseBuild $spec.Repo
    } finally {
        [System.IO.File]::WriteAllText($catalogPath, $catalogBefore, $utf8)
    }

    $info = Get-ApkInfo $apk $buildTools
    if ($info.Package -ne $spec.Package) { throw "Built $($info.Package), expected $($spec.Package)" }
    if ($info.VersionCode -ne $source.Code -or $info.VersionName -ne $source.Name) {
        throw "$($spec.Title): the APK says $($info.VersionName) ($($info.VersionCode)), the source says $($source.Name) ($($source.Code))."
    }
    $pinned = ([string]$entry.signingCertSha256).Replace(':', '').ToLowerInvariant()
    if ($info.Cert -ne $pinned) {
        throw "$($spec.Title) is signed with $($info.Cert) but the catalog pins $pinned. Refusing to release."
    }

    $fileName = "$($spec.File)-$($info.VersionName).apk"
    $target = Join-Path $dist $fileName
    Copy-Item $apk $target -Force
    $hash = (Get-FileHash -Algorithm SHA256 $target).Hash.ToLowerInvariant()

    $entry.version = $info.VersionName
    $entry.versionCode = $info.VersionCode
    $entry.apkUrl = "https://github.com/$GitHubRepo/releases/download/$tag/$fileName"
    $entry.apkSha256 = $hash

    $released.Add([pscustomobject]@{
            Title = $spec.Title; Tag = $tag; File = $target; FileName = $fileName
            Version = $info.VersionName; VersionCode = $info.VersionCode; Sha256 = $hash; Cert = $info.Cert
        })
    Write-Host "  $fileName  versionCode $($info.VersionCode)"
    Write-Host "  SHA-256  $hash"
    Write-Host "  signer   $($info.Cert)"
}

Write-Host ''
if ($released.Count -eq 0) {
    Write-Host 'Nothing to release: every selected app is already released at its current version.'
    return
}

Write-Catalog $final $stagedCatalog

$summary = ($released | ForEach-Object { "$($_.Title) $($_.Version)" }) -join ', '
$steps = New-Object System.Collections.Generic.List[object]
foreach ($r in $released) {
    $notes = "$($r.Title) $($r.Version) (versionCode $($r.VersionCode)). SHA-256: $($r.Sha256)"
    $steps.Add(@('gh', 'release', 'create', $r.Tag, $r.File, '--repo', $GitHubRepo, '--target', 'main', '--title', "$($r.Title) $($r.Version)", '--notes', $notes))
}
$commitSteps = @(
    @('git', '-C', $root, 'add', $catalogRelative),
    @('git', '-C', $root, 'commit', '-m', "Release $summary"),
    @('git', '-C', $root, 'push')
)

if (-not $Publish) {
    Write-Host "APKs and the new catalog are staged in dist\. Nothing was published and the catalog in the working tree was not changed."
    Write-Host 'To publish, in this order:'
    Write-Host '  1. Commit and push the source (the release tags are created on the pushed main branch).'
    Write-Host '  2. Create the releases:'
    foreach ($c in $steps) {
        $quoted = $c | ForEach-Object { if ($_ -match '[\s'']') { '"' + $_ + '"' } else { $_ } }
        Write-Host ('       ' + ($quoted -join ' '))
    }
    Write-Host '  3. Only then publish the catalog:'
    Write-Host ('       Copy-Item -Force "' + $stagedCatalog + '" "' + $catalogPath + '"')
    foreach ($c in $commitSteps) {
        $quoted = $c | ForEach-Object { if ($_ -match '[\s'']') { '"' + $_ + '"' } else { $_ } }
        Write-Host ('       ' + ($quoted -join ' '))
    }
    Write-Host 'Or, after step 1, run this script again with -Publish.'
    return
}

foreach ($c in $steps) { Invoke-Step $c }
Copy-Item -Force $stagedCatalog $catalogPath
Invoke-Step $commitSteps[0]
& git -C $root diff --cached --quiet -- $catalogRelative
if ($LASTEXITCODE -eq 0) {
    Write-Host 'The catalog is already committed with these values.'
} else {
    Invoke-Step $commitSteps[1]
}
Invoke-Step $commitSteps[2]
Write-Host "Published: $summary"
