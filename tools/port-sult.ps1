# port-sult.ps1 - copy sult_liban logic into com.example.finance with package rewrite
# Mapping: model->scene ; ocr/skill/agent/capture/config stay same-named under com.example.finance
$src = 'D:\Android\AndroidStudioProjects\Finance\external\_extracted\sult_liban-main\app\src\main\java\com\liban\android'
$dstRoot = 'D:\Android\AndroidStudioProjects\Finance\app\src\main\java\com\example\finance'
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Port-File([string]$rel, [string]$sub, [string]$newPkg) {
    $content = [System.IO.File]::ReadAllText((Join-Path $src $rel), $utf8)
    $content = $content.Replace('com.liban.android.model', 'com.example.finance.scene')
    $content = $content.Replace('com.liban.android.ocr', 'com.example.finance.ocr')
    $content = $content.Replace('com.liban.android.skill', 'com.example.finance.skill')
    $content = $content.Replace('com.liban.android.agent', 'com.example.finance.agent')
    $content = $content.Replace('com.liban.android.capture', 'com.example.finance.capture')
    $content = $content.Replace('com.liban.android.config', 'com.example.finance.config')
    $content = $content.Replace('com.liban.android.data', 'com.example.finance.data')
    $content = $content.Replace('com.liban.android.ui', 'com.example.finance.ui')
    # package statement of the ported file itself
    $content = $content.Replace("package com.example.finance.$newPkg", "package com.example.finance.$newPkg")
    $dir = Join-Path $dstRoot $sub
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $dir (Split-Path $rel -Leaf)), $content, $utf8)
    Write-Host "ported $rel"
}

# scene (was model)
Port-File 'model\Models.kt' 'scene' 'scene'
Port-File 'model\MoneyParsing.kt' 'scene' 'scene'
Port-File 'model\ProductText.kt' 'scene' 'scene'

# ocr / skill / agent / capture / config -> sub folder = package folder
Port-File 'ocr\OcrProvider.kt' 'ocr' 'ocr'
Port-File 'ocr\OcrReadingOrder.kt' 'ocr' 'ocr'
Port-File 'ocr\SceneParser.kt' 'ocr' 'ocr'
Port-File 'ocr\SceneRefinement.kt' 'ocr' 'ocr'
Port-File 'ocr\LlmSceneExtractor.kt' 'ocr' 'ocr'
Port-File 'skill\Skills.kt' 'skill' 'skill'
Port-File 'skill\SkillRouter.kt' 'skill' 'skill'
Port-File 'agent\DecisionEngine.kt' 'agent' 'agent'
Port-File 'agent\PriceEvidenceAnalyzer.kt' 'agent' 'agent'
Port-File 'agent\PriceSearch.kt' 'agent' 'agent'
Port-File 'agent\Providers.kt' 'agent' 'agent'
Port-File 'agent\AnalysisBus.kt' 'agent' 'agent'
Port-File 'agent\AgentOrchestrator.kt' 'agent' 'agent'
Port-File 'capture\FloatingCaptureService.kt' 'capture' 'capture'
Write-Host 'DONE'
