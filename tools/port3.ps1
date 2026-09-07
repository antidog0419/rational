# port3.ps1 - port a subset of unit tests into com.example.finance
$src = 'D:\Android\AndroidStudioProjects\Finance\external\_extracted\sult_liban-main\app\src\test\java\com\liban\android'
$dstRoot = 'D:\Android\AndroidStudioProjects\Finance\app\src\test\java\com\example\finance'
$utf8 = New-Object System.Text.UTF8Encoding($false)
function Rewrite([string]$content) {
    $content = $content.Replace('com.liban.android.model', 'com.example.finance.scene')
    $content = $content.Replace('com.liban.android.ocr', 'com.example.finance.ocr')
    $content = $content.Replace('com.liban.android.skill', 'com.example.finance.skill')
    $content = $content.Replace('com.liban.android.agent', 'com.example.finance.agent')
    $content = $content.Replace('com.liban.android.capture', 'com.example.finance.capture')
    $content = $content.Replace('com.liban.android.config', 'com.example.finance.config')
    $content = $content.Replace('com.liban.android.data', 'com.example.finance.data')
    return $content
}
$files = @(
  'model\MoneyParsingTest.kt',
  'skill\SkillsTest.kt',
  'skill\SkillRouterTest.kt',
  'agent\DecisionEngineTest.kt'
)
foreach ($f in $files) {
    $content = Rewrite ([System.IO.File]::ReadAllText((Join-Path $src $f), $utf8))
    $rel = $f.Replace('model\', 'scene\').Replace('skill\', 'skill\').Replace('agent\', 'agent\')
    $sub = Split-Path $rel
    $dir = Join-Path $dstRoot $sub
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $dir (Split-Path $f -Leaf)), $content, $utf8)
    Write-Host "ported test $f"
}
Write-Host 'DONE'
