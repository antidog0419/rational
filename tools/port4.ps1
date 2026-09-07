# port4.ps1 - port remaining unit tests + fixtures into com.example.finance
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
$map = @{
  'ocr\ShoppingOcrFixtures.kt' = 'ocr'
  'ocr\SceneParserTest.kt' = 'ocr'
  'ocr\OcrReadingOrderTest.kt' = 'ocr'
  'ocr\SceneRefinementTest.kt' = 'ocr'
  'agent\PriceEvidenceAnalyzerTest.kt' = 'agent'
  'agent\PriceSearchTest.kt' = 'agent'
  'agent\ProvidersTest.kt' = 'agent'
}
foreach ($f in $map.Keys) {
    $content = Rewrite ([System.IO.File]::ReadAllText((Join-Path $src $f), $utf8))
    $dir = Join-Path $dstRoot $map[$f]
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $dir (Split-Path $f -Leaf)), $content, $utf8)
    Write-Host "ported test $f"
}
Write-Host 'DONE'
