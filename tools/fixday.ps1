$f = 'app\src\main\java\com\example\finance\service\FinanceAccessibilityService.kt'
$lines = [System.IO.File]::ReadAllLines($f, [System.Text.Encoding]::UTF8)
$pair = @(
  ,@(2037, '月](\d{1,2})日(?:\s*(\d{1,2}):(\d{2}))?$', '月](\d{1,2})日?(?:\s*(\d{1,2}):(\d{2}))?$')
  ,@(2085, '\d{1,2})日(?:\s*\d{1,2}:\d{2})?|"""', '\d{1,2})日?(?:\s*\d{1,2}:\d{2})?|"""')
)
foreach ($p in $pair) {
  $ln = $p[0]
  $old = $p[1]
  $new = $p[2]
  $cur = $lines[$ln-1]
  if ($cur.Contains($old)) {
    $lines[$ln-1] = $cur.Replace($old, $new)
    Write-Output "L$ln fixed"
  } else {
    Write-Output "L$ln NO MATCH -> $cur"
  }
}
[System.IO.File]::WriteAllLines($f, $lines, (New-Object System.Text.UTF8Encoding($false)))
