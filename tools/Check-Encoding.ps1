# Check-Encoding.ps1 - UTF-8 integrity guard for source files.
#
# Purpose: prevent a recurrence of the 2026-09-06 incident where a file was
# rewritten as GBK and most Chinese characters in app sources were destroyed
# (850+ U+FFFD / broken strings, recovered only via .class string dictionaries).
#
# The guard flags any text file that is:
#   1) not valid UTF-8 (strict decode), or
#   2) valid UTF-8 but contains U+FFFD (REPLACEMENT CHARACTER), the signature
#      of an already-corrupted write.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/Check-Encoding.ps1 -ScanPath app\src
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/Check-Encoding.ps1 -ScanPath . -Include @('*.kt')
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/Check-Encoding.ps1 -ListFile <tmpfile>   # git pre-commit
#
# Note: tools/accident-2026-09-06 deliberately holds the damaged 2026-09-06
# backups as evidence; pass -SkipSubtree 'accident-2026-09-06' when scanning a
# tree that contains it.
#
# Exit code: 0 = clean, 1 = violations found.

param(
    [string]$ScanPath = '',
    [string]$ListFile = '',
    [string]$SkipSubtree = '',
    [string[]]$Include = @('*.kt', '*.java', '*.kts', '*.xml', '*.properties', '*.toml', '*.md', '*.json')
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($ScanPath) -and [string]::IsNullOrWhiteSpace($ListFile)) {
    Write-Host 'Check-Encoding: specify -ScanPath <dir> or -ListFile <file-with-paths>' -ForegroundColor Yellow
    exit 2
}

# Strict UTF-8 decoder: any invalid byte sequence throws.
$enc = New-Object System.Text.UTF8Encoding($false, $true)  # no BOM, throwOnInvalidBytes
$replacementChar = [char]0xFFFD
$bad = New-Object System.Collections.Generic.List[string]

function Test-OneFile {
    param([string]$path)
    if (-not (Test-Path -LiteralPath $path)) { return }
    try {
        $text = [System.IO.File]::ReadAllText($path, $enc)
        if ($text.IndexOf($replacementChar) -ge 0) {
            $bad.Add("$path  -> valid UTF-8 but contains U+FFFD (GBK-corruption signature)")
        }
    }
    catch [System.Text.DecoderFallbackException] {
        $bad.Add("$path  -> NOT valid UTF-8: $($_.Exception.Message)")
    }
    catch {
        $bad.Add("$path  -> read error: $($_.Exception.Message)")
    }
}

# Extension allow-list (works reliably with -LiteralPath; -Include does not).
$IncludeExts = @($Include | ForEach-Object { $_.TrimStart('*').ToLowerInvariant() })

$count = 0
if (-not [string]::IsNullOrWhiteSpace($ListFile)) {
    # Git pre-commit mode: check newline-separated paths from a temp list file.
    Get-Content -LiteralPath $ListFile | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object {
        Test-OneFile $_
        $count++
    }
}
else {
    Get-ChildItem -LiteralPath $ScanPath -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object {
            ($IncludeExts -contains $_.Extension.ToLowerInvariant()) -and
            ([string]::IsNullOrWhiteSpace($SkipSubtree) -or $_.FullName -notlike "*$SkipSubtree*")
        } |
        ForEach-Object {
            Test-OneFile $_.FullName
            $count++
        }
}

if ($bad.Count -gt 0) {
    Write-Host "Check-Encoding FAILED: $($bad.Count) of $count file(s) have encoding damage:" -ForegroundColor Red
    $bad | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    Write-Host 'Fix them (rewrite as UTF-8, no mojibake) before committing/building.' -ForegroundColor Red
    exit 1
}
Write-Host "Check-Encoding OK: $count file(s) are clean UTF-8."
exit 0
