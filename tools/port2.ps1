# port2.ps1 - port data + config packages
$src = 'D:\Android\AndroidStudioProjects\Finance\external\_extracted\sult_liban-main\app\src\main\java\com\liban\android'
$dstRoot = 'D:\Android\AndroidStudioProjects\Finance\app\src\main\java\com\example\finance'
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
function Copy-From([string]$rel, [string]$folder) {
    $content = Rewrite ([System.IO.File]::ReadAllText((Join-Path $src $rel), $utf8))
    $dir = Join-Path $dstRoot $folder
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $dir (Split-Path $rel -Leaf)), $content, $utf8)
    Write-Host "ported $rel"
}
Copy-From 'data\Entities.kt' 'data'
Copy-From 'data\AppDao.kt' 'data'
Copy-From 'data\AppDatabase.kt' 'data'
Copy-From 'data\AppRepository.kt' 'data'
Copy-From 'config\ConfigRepository.kt' 'config'
Write-Host 'DONE'
