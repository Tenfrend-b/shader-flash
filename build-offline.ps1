# 离线构建脚本（不需要网络、不需要 Gradle）。
#
# 为什么不用 Gradle：本机在受限沙箱里跑 loom 时会被钉在 zipfs / toRealPath 上，
# 而且依赖都在本地缓存里。这里直接 javac + tiny-remapper 手工走一遍：
#
#   1) 用 named（Yarn）类路径编译源码
#   2) 把编译结果从 named 重映射到 intermediary（Fabric 运行时用的命名空间）
#   3) 打包成可放进 mods/ 的 jar
#
# 前提：build.ps1 里那几个路径存在（同一目录下的 build/ 工作区、Iris/Sodium 的 named jar）。
#
# 用法： powershell -ExecutionPolicy Bypass -File build-offline.ps1 [-Version 0.1]

param(
    [string]$Version = "0.1"
)

$ErrorActionPreference = "Stop"

$sourceRoot = $PSScriptRoot
# source/ -> shader-flash/ -> outputs/ -> 任务根目录
$taskRoot   = Split-Path (Split-Path (Split-Path $sourceRoot -Parent) -Parent) -Parent
$build      = Join-Path $taskRoot "work\build"
$outputs    = Join-Path $taskRoot "outputs\shader-flash"

$java  = "C:\Program Files\Java\jdk-17\bin\java.exe"
$javac = "C:\Program Files\Java\jdk-17\bin\javac.exe"
$mappings = Join-Path $env:USERPROFILE "Documents\Codex\2026-09-10\zai\work\gradle-home\caches\fabric-loom\1.20.4\net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2\mappings.tiny"

$classes = Join-Path $build "shaderflash-classes"
$tools   = Join-Path $build "tools\classes"

if (-not (Test-Path $mappings)) { throw "找不到 yarn 映射文件：$mappings" }
if (-not (Test-Path (Join-Path $build "named\iris-named.jar"))) { throw "缺少 iris-named.jar" }

Write-Host "[1/4] 准备类路径"
$compileJars = @()
$compileJars += (Get-ChildItem (Join-Path $build "libs-named\*.jar") | ForEach-Object { $_.FullName })
$compileJars += (Get-ChildItem (Join-Path $build "libs-tools\asm-*.jar") | ForEach-Object { $_.FullName })
$compileJars += (Join-Path $build "named\iris-named.jar")
$compileJars += (Join-Path $build "named\sodium-named.jar")
$compileClasspath = $compileJars -join ';'

$sources = Get-ChildItem (Join-Path $sourceRoot "src\main\java") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName -replace '\\', '/' }
Write-Host "    源码文件数：$($sources.Count)"

if (Test-Path $classes) {
    Get-ChildItem $classes -Recurse -File | Remove-Item -Force
} else {
    New-Item -ItemType Directory -Force -Path $classes | Out-Null
}

$argFile = Join-Path $build "shaderflash-javac-args.txt"
[System.IO.File]::WriteAllText($argFile, ($sources -join "`n"), (New-Object System.Text.UTF8Encoding($false)))

# 沙箱禁止 toRealPath，javac 在收尾关闭 jar 时必定抛一次异常并打印崩溃报告，
# 退出码因此不可靠 —— 以“有没有产出 class”为准。注意这里要临时放开错误策略，
# 否则 PowerShell 会把那段无害的 stderr 当成致命错误。
Write-Host "[2/4] 编译（javac 收尾时会打印一次沙箱导致的 zipfs 异常，属正常现象）"
$javacLog = Join-Path $build "shaderflash-javac.log"
$previousPolicy = $ErrorActionPreference
$ErrorActionPreference = "Continue"
# 让 javac 用英文报错，方便下面用 "error:" 判断编译是否真的成功
$env:JAVA_TOOL_OPTIONS = "-Duser.language=en -Duser.country=US"
& $javac -proc:none --release 17 -encoding UTF-8 -nowarn -cp $compileClasspath -d $classes "@$argFile" 2>&1 |
    Out-File -Encoding utf8 $javacLog
$ErrorActionPreference = $previousPolicy
$compileErrors = Select-String -Path $javacLog -Pattern 'error:' -ErrorAction SilentlyContinue
if ($compileErrors) {
    $compileErrors | Select-Object -First 15 | ForEach-Object { Write-Host "    $($_.Line.Trim())" }
    throw "javac 报错，构建终止（完整日志：$javacLog）"
}
Get-Content -Encoding utf8 $javacLog |
    Where-Object { $_ -notmatch 'toRealPath|ZipFileSystem|JavacFileManager|AccessDeniedException|^\s+at ' } |
    Where-Object { $_ -notmatch 'HotSpot|bugreport|Bug Database|请提交|谢谢|Picked up|printing javac' } |
    Where-Object { $_.Trim() -ne '' } |
    Select-Object -First 20 |
    ForEach-Object { Write-Host "    $_" }

$produced = (Get-ChildItem $classes -Recurse -Filter *.class).Count
if ($produced -eq 0) { throw "编译没有产出任何 class" }
Write-Host "    产出 $produced 个 class"

Write-Host "[3/4] 复制资源"
Copy-Item -Recurse -Force (Join-Path $sourceRoot "src\main\resources\*") $classes
$modJson = Join-Path $classes "fabric.mod.json"
$text = [System.IO.File]::ReadAllText($modJson)
[System.IO.File]::WriteAllText($modJson, $text.Replace('${version}', $Version),
    (New-Object System.Text.UTF8Encoding($false)))

Write-Host "[4/4] 重映射到 intermediary 并打包"
$finalJar = Join-Path $outputs "shader-flash-$Version.jar"
New-Item -ItemType Directory -Force -Path $outputs | Out-Null
if (Test-Path $finalJar) { Remove-Item $finalJar -Force }

$toolRun = "$($build)\libs-tools\*;$tools"
& $java -cp $toolRun tools.RemapTool $classes $finalJar $mappings named intermediary (Join-Path $build "cp-named")
if (-not (Test-Path $finalJar)) { throw "重映射失败" }

Get-Item $finalJar | Select-Object FullName, Length
Write-Host "DONE -> $finalJar"
