<#
.SYNOPSIS
    Bumps the project version in every file that declares it.

.DESCRIPTION
    One command instead of hunting for version strings:

        powershell -ExecutionPolicy Bypass -File scripts/set-version.ps1 0.2.0

    It rewrites the authoritative declarations, refuses to guess (a rule that no
    longer matches is an error, not a silent skip), and prints what changed:

        gradle/libs.versions.toml                      project = "0.2.0"     (Gradle)
        web/package.json + web/package-lock.json       via `npm version`
        server/app/.../Main.kt                         FALLBACK_VERSION
        server/engine-api/.../DownloadEngine.kt        engine fallback literal
        README.md / README_en.md                       the version line

    Everything else derives from those: the fat jar name, the image tag, the API
    `/version` response, the UI's About page and the release workflow.

.PARAMETER Version
    Semantic version, with or without a leading `v` (e.g. 0.2.0 or v0.2.0).

.PARAMETER DryRun
    Show what would change without writing anything.

.PARAMETER Commit
    Stage exactly the touched files and commit them.

.PARAMETER Tag
    Also create the annotated tag v<version> locally (requires -Commit).

.PARAMETER Push
    Push the current branch and the tag to origin (requires -Tag).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/set-version.ps1 0.2.0 -DryRun

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/set-version.ps1 v0.2.0 -Commit -Tag

.NOTES
    Releasing afterwards: GitHub -> Actions -> Release (manual) -> Run workflow.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Version,

    [switch]$DryRun,
    [switch]$Commit,
    [switch]$Tag,
    [switch]$Push
)

$ErrorActionPreference = 'Stop'

if ($Tag -and -not $Commit) { throw '-Tag needs -Commit, so the tag points at a commit that carries the version.' }
if ($Push -and -not $Tag) { throw '-Push needs -Tag.' }

# ------------------------------------------------------------------ version input
$clean = $Version.Trim()
if ($clean.StartsWith('v') -or $clean.StartsWith('V')) { $clean = $clean.Substring(1) }
if ($clean -notmatch '^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$') {
    throw "'$Version' is not a semantic version (expected something like 0.2.0 or 0.2.0-rc.1)."
}

$root = Split-Path -Parent $PSScriptRoot

# NOTE: keep this script ASCII-only. Windows PowerShell 5.1 decodes .ps1 files as
# ANSI unless they carry a BOM, so any non-ASCII literal here (e.g. a Chinese README
# heading used as a pattern) would be corrupted before the regex ever runs. Patterns
# therefore match ASCII anchors and use capture groups to preserve the original text.

Push-Location $root
try {
    Write-Host ''
    Write-Host "abdm-server: version -> $clean" -ForegroundColor Cyan
    if ($DryRun) { Write-Host '(dry run: nothing will be written)' -ForegroundColor Yellow }
    Write-Host ''

    # -------------------------------------------------------------------- rules
    # Every rule must match exactly once: if a declaration moves or disappears the
    # script fails loudly instead of silently skipping it.
    $rules = @(
        @{
            File        = 'gradle/libs.versions.toml'
            Description = 'Gradle version catalog (the build reads this)'
            Pattern     = '(?m)^project[ \t]*=[ \t]*"[^"]*"'
            Replace     = "project = `"$clean`""
        },
        @{
            File        = 'server/app/src/main/kotlin/dev/abdm/server/app/Main.kt'
            Description = 'Server version fallback (a release jar uses its manifest)'
            Pattern     = '(?m)^private const val FALLBACK_VERSION = "[^"]*"'
            Replace     = "private const val FALLBACK_VERSION = `"$clean`""
        },
        @{
            File        = 'server/engine-api/src/main/kotlin/dev/abdm/server/engine/api/DownloadEngine.kt'
            Description = 'Engine descriptor fallback version'
            Pattern     = '(?m)^        \?: "[^"]*"$'
            Replace     = "        ?: `"$clean`""
        },
        @{
            File        = 'README.md'
            Description = 'README version line (zh) - pattern stays ASCII on purpose'
            Pattern     = '(?m)^(-[^\r\n]*`)\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?([^\r\n]*)$'
            Replace     = '${1}' + $clean + '${2}'
        },
        @{
            File        = 'README_en.md'
            Description = 'README version line (en)'
            Pattern     = '(?m)^(-[^\r\n]*`)\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?([^\r\n]*)$'
            Replace     = '${1}' + $clean + '${2}'
        }
    )

    $plan = @()
    foreach ($rule in $rules) {
        $path = Join-Path $root $rule.File
        if (-not (Test-Path $path)) { throw "missing file: $($rule.File)" }
        $text = [IO.File]::ReadAllText($path)
        $matches = [regex]::Matches($text, $rule.Pattern)
        if ($matches.Count -ne 1) {
            throw "$($rule.File): the version declaration matched $($matches.Count) time(s), expected exactly 1 - update the rule in this script."
        }
        $plan += [pscustomobject]@{
            File        = $rule.File
            Description = $rule.Description
            Old         = $matches[0].Value.Trim()
            # Show the resulting text, not the replacement template (${1}/${2} form).
            New         = [regex]::Replace($matches[0].Value, $rule.Pattern, $rule.Replace).Trim()
            Pattern     = $rule.Pattern
            Path        = $path
            Text        = [regex]::Replace($text, $rule.Pattern, $rule.Replace)
        }
    }

    # The frontend pair goes through npm, so package.json and package-lock.json stay
    # consistent and keep npm's own formatting. The old values are read with node:
    # Windows PowerShell 5.1's ConvertFrom-Json cannot parse this lockfile.
    $pkgVersion = (& node -e "process.stdout.write(require('./web/package.json').version)")
    if ($LASTEXITCODE -ne 0) { throw 'could not read web/package.json' }
    $lockVersions = (& node -e "const l=require('./web/package-lock.json');process.stdout.write(l.version + ' / ' + l.packages[''].version)")
    if ($LASTEXITCODE -ne 0) { throw 'could not read web/package-lock.json' }
    $plan += [pscustomobject]@{
        File        = 'web/package.json + web/package-lock.json'
        Description = 'Frontend version (rewritten by npm version)'
        Old         = $pkgVersion
        New         = $clean
        Pattern     = $null
        Path        = $null
        Text        = $null
    }

    # ------------------------------------------------------------- apply / report
    $changed = 0
    $touched = @()
    foreach ($item in $plan) {
        $isChange = $item.Old -ne $item.New
        if ($isChange) { $changed++ }
        Write-Host ("  [{0,-9}] {1}" -f ($(if ($isChange) { 'update' } else { 'unchanged' }), $item.File)) -ForegroundColor $(if ($isChange) { 'Green' } else { 'DarkGray' })
        Write-Host ("              {0}" -f $item.Description) -ForegroundColor DarkGray
        Write-Host ("              {0}  ->  {1}" -f $item.Old, $item.New) -ForegroundColor DarkGray

        if ($DryRun -or -not $isChange) { continue }
        if ($item.Path) {
            [IO.File]::WriteAllText($item.Path, $item.Text)   # UTF-8 without BOM, LF kept
            # Guard against a broken replacement template: the declaration must still
            # be found exactly once, and it must now carry the new version.
            $written = [IO.File]::ReadAllText($item.Path)
            if ([regex]::Matches($written, $item.Pattern).Count -ne 1) {
                throw "$($item.File): the declaration no longer matches after writing - check the rule."
            }
            if (-not $written.Contains($clean)) {
                throw "$($item.File): the new version is not in the file after writing - check the replacement."
            }
            $touched += $item.File
        }
    }

    if (-not $DryRun -and $changed -gt 0) {
        $touched += 'web/package.json'
        $touched += 'web/package-lock.json'
        Write-Host ''
        Write-Host '  running: npm --prefix web version (updates package.json + lock)' -ForegroundColor DarkGray
        & npm --prefix web version $clean --no-git-tag-version --allow-same-version --silent
        if ($LASTEXITCODE -ne 0) { throw 'npm version failed' }
    }

    Write-Host ''
    if ($DryRun) {
        Write-Host "dry run complete: $changed group(s) would change" -ForegroundColor Yellow
        return
    }
    Write-Host "version is now $clean ($changed group(s) changed)" -ForegroundColor Cyan

    $leftovers = & git grep -n -E '0\.1\.0' -- . ':(exclude)web/package-lock.json' 2>$null
    if ($leftovers) {
        Write-Host ''
        Write-Host 'these lines still mention the old version (examples in docs, not declarations):' -ForegroundColor DarkYellow
        $leftovers | Select-Object -First 8 | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
    }

    # ---------------------------------------------------------------- next steps
    Write-Host ''
    Write-Host 'next steps:' -ForegroundColor Cyan
    if ($Commit) {
        & git add -- $touched
        if ($LASTEXITCODE -ne 0) { throw 'git add failed' }
        & git commit -q -m "chore: bump version to $clean"
        if ($LASTEXITCODE -ne 0) { throw 'git commit failed' }
        Write-Host "  committed: chore: bump version to $clean" -ForegroundColor Green

        if ($Tag) {
            $tagName = "v$clean"
            if (& git tag --list $tagName) { throw "tag $tagName already exists" }
            & git tag -a $tagName -m "abdm-server $tagName"
            if ($LASTEXITCODE -ne 0) { throw 'git tag failed' }
            Write-Host "  tagged: $tagName" -ForegroundColor Green

            if ($Push) {
                & git push origin HEAD
                if ($LASTEXITCODE -ne 0) { throw 'git push (branch) failed' }
                & git push origin $tagName
                if ($LASTEXITCODE -ne 0) { throw "git push $tagName failed" }
                Write-Host "  pushed: branch + $tagName" -ForegroundColor Green
            } else {
                Write-Host "  push with: git push origin HEAD; git push origin $tagName"
            }
        } else {
            Write-Host "  tag with:  git tag -a v$clean -m `"abdm-server v$clean`""
        }
    } else {
        Write-Host '  review:  git diff'
        Write-Host "  commit:  git commit -am `"chore: bump version to $clean`""
    }
    Write-Host "  verify:  ./gradlew :server:app:fatJar; java -jar server/app/build/libs/abdm-server-$clean-all.jar --print-version"
    Write-Host "  release: GitHub -> Actions -> Release (manual) -> Run workflow, version $clean"
    Write-Host ''
} finally {
    Pop-Location
}
