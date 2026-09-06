$f = 'app\src\main\java\com\example\finance\service\FinanceAccessibilityService.kt'
$lines = [System.IO.File]::ReadAllLines($f, [System.Text.Encoding]::UTF8)
foreach ($ln in @(2037, 2085)) {
  $cur = $lines[$ln-1]
  # 修 日日? -> 日?
  $dup = [string][char]0x65E5 + [string][char]0x65E5 + '?'
  if ($cur.Contains($dup)) {
    $lines[$ln-1] = $cur.Replace($dup, [string][char]0x65E5 + '?')
    Write-Output "L$ln dedup fixed"
  } else {
    Write-Output "L$ln no double-day -> $cur"
  }
}
[System.IO.File]::WriteAllLines($f, $lines, (New-Object System.Text.UTF8Encoding($false)))
