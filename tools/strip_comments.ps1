function CleanJunk([string]$s) {
  if ($s.IndexOf([char]0xFFFD) -lt 0 -and $s.IndexOf('?') -lt 0) { return $s }
  $chars = $s.ToCharArray()
  $keep = New-Object System.Collections.Generic.List[char]
  for ($i = 0; $i -lt $chars.Count; $i++) {
    $c = $chars[$i]
    if ($c -eq [char]0xFFFD) { continue }
    if ($c -eq '?') {
      # 前一个或后一个是 FFFD 或已被删除的 ? 标记 -> 也是损坏残留
      $prevBad = $i -gt 0 -and ($chars[$i-1] -eq [char]0xFFFD -or $chars[$i-1] -eq '?')
      $nextBad = $i + 1 -lt $chars.Count -and $chars[$i+1] -eq [char]0xFFFD
      if ($prevBad -or $nextBad) { continue }
    }
    $keep.Add($c)
  }
  return (-join $keep) -replace ' {2,}', ' '
}

$f = 'app\src\main\java\com\example\finance\service\FinanceAccessibilityService.kt'
$lines = [System.IO.File]::ReadAllLines($f, [System.Text.Encoding]::UTF8)
$changed = 0
for ($i = 0; $i -lt $lines.Count; $i++) {
  $orig = $lines[$i]
  if ($orig.IndexOf([char]0xFFFD) -lt 0) { continue }
  $trim = $orig.TrimStart()
  $pureComment = $trim.StartsWith('//') -or $trim.StartsWith('*') -or $trim.StartsWith('/*') -or $trim.StartsWith('*/')
  $new = $orig
  if ($pureComment) {
    $new = CleanJunk($orig)
  } else {
    # 代码行:只处理 // 之后的部分(尾部注释)
    $ci = $orig.LastIndexOf('//')
    if ($ci -ge 0 -and $orig.IndexOf([char]0xFFFD) -gt $ci) {
      $head = $orig.Substring(0, $ci)
      $tail = CleanJunk($orig.Substring($ci))
      $new = $head + $tail
    }
  }
  if ($new -ne $orig) { $lines[$i] = $new; $changed++ }
}
[System.IO.File]::WriteAllLines($f, $lines, (New-Object System.Text.UTF8Encoding($false)))
Write-Output ("cleaned lines: " + $changed)
$left = 0
foreach ($l in $lines) { if ($l.IndexOf([char]0xFFFD) -ge 0) { $left++ } }
Write-Output ("remaining damaged lines: " + $left)
