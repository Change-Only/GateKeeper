<#
=============================================================================
 GateKeeper 镜像构建 / 打标签 / 推送 / 导出（Windows PowerShell 版）
=============================================================================
 用法（在仓库任意位置执行）：
     powershell -ExecutionPolicy Bypass -File docker\build-and-push.ps1
     powershell -ExecutionPolicy Bypass -File docker\build-and-push.ps1 -Push
     powershell -ExecutionPolicy Bypass -File docker\build-and-push.ps1 -Push -Save
     powershell -ExecutionPolicy Bypass -File docker\build-and-push.ps1 -Latest

 可覆盖的环境变量：
     GK_DOCKER_NAMESPACE   Docker Hub 命名空间，默认 changeonly
     GK_VERSION            版本号，默认从 src/backend/pom.xml 读取

 标签命名（单仓库多 tag）：
     <namespace>/gatekeeper:backend-<version>
     <namespace>/gatekeeper:frontend-<version>
=============================================================================
#>
[CmdletBinding()]
param(
    [switch]$Push,
    [switch]$Save,
    [switch]$Latest
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

$Namespace = $env:GK_DOCKER_NAMESPACE
if ([string]::IsNullOrWhiteSpace($Namespace)) { $Namespace = 'changeonly' }

# ---- 版本号：优先环境变量，否则从后端 pom.xml 读 ----
$Version = $env:GK_VERSION
if ([string]::IsNullOrWhiteSpace($Version)) {
    $pom = Join-Path $RepoRoot 'src\backend\pom.xml'
    $lines = Get-Content -LiteralPath $pom -Encoding UTF8
    for ($i = 0; $i -lt $lines.Count - 1; $i++) {
        if ($lines[$i] -match '<artifactId>gatekeeper</artifactId>') {
            if ($lines[$i + 1] -match '<version>([^<]+)</version>') {
                $Version = $Matches[1]
            }
            break
        }
    }
}
if ([string]::IsNullOrWhiteSpace($Version)) {
    Write-Error 'FATAL: 无法确定版本号，请显式设置 $env:GK_VERSION = "x.y.z"'
    exit 1
}

# ---- 前端 package.json 版本必须与后端一致 ----
$pkg = Join-Path $RepoRoot 'src\frontend\package.json'
$feVer = (Get-Content -LiteralPath $pkg -Encoding UTF8 | Select-String -Pattern '"version"\s*:\s*"([^"]+)"' |
          Select-Object -First 1).Matches.Groups[1].Value
if ($feVer -ne $Version) {
    Write-Error "FATAL: 版本号不一致 —— 后端 pom.xml=$Version，前端 package.json=$feVer"
    exit 1
}

$BeTag = "$Namespace/gatekeeper:backend-$Version"
$FeTag = "$Namespace/gatekeeper:frontend-$Version"

Write-Host '=============================================================='
Write-Host " 仓库根目录 : $RepoRoot"
Write-Host " 命名空间   : $Namespace"
Write-Host " 版本号     : $Version"
Write-Host " 后端标签   : $BeTag"
Write-Host " 前端标签   : $FeTag"
Write-Host '=============================================================='

# ---- 前置安全校验 ----
# src/main/resources/application.yml 未入库但本地存在，含真实密钥与内网库地址。
# .gitignore 管不住 docker build 的上下文；.dockerignore 一旦漏排，
# 密钥就会被烤进镜像并推到公开仓库。
$beIgnore = Join-Path $RepoRoot 'src\backend\.dockerignore'
$ignoreText = Get-Content -LiteralPath $beIgnore -Raw -Encoding UTF8
if ($ignoreText -notmatch '(?m)^src/main/resources/application\.yml\s*$') {
    Write-Error 'FATAL: src/backend/.dockerignore 未显式排除 src/main/resources/application.yml，继续构建会把真实密钥打进镜像。已中止。'
    exit 1
}
$beDockerfile = Get-Content -LiteralPath (Join-Path $RepoRoot 'src\backend\Dockerfile') -Raw -Encoding UTF8
if ($beDockerfile -notmatch 'cp\s+src/main/resources/application\.example\.yml') {
    Write-Error 'FATAL: src/backend/Dockerfile 未用模板顶替运行配置，镜像将缺少 server/dataSource 配置。'
    exit 1
}
Write-Host '[preflight] .dockerignore 与 Dockerfile 的安全前置条件均满足 OK' -ForegroundColor Green

# ---- 构建 ----
docker build --build-arg "VERSION=$Version" -t $BeTag -f (Join-Path $RepoRoot 'src\backend\Dockerfile') (Join-Path $RepoRoot 'src\backend')
if ($LASTEXITCODE -ne 0) { Write-Error '后端镜像构建失败'; exit 1 }

docker build --build-arg "VERSION=$Version" -t $FeTag -f (Join-Path $RepoRoot 'src\frontend\Dockerfile') (Join-Path $RepoRoot 'src\frontend')
if ($LASTEXITCODE -ne 0) { Write-Error '前端镜像构建失败'; exit 1 }

if ($Latest) {
    docker tag $BeTag "$Namespace/gatekeeper:backend-latest"
    docker tag $FeTag "$Namespace/gatekeeper:frontend-latest"
    Write-Host '[tag] 已附加 backend-latest / frontend-latest'
}

Write-Host "[build] 完成。镜像内 OCI 标签 org.opencontainers.image.version=$Version"
docker image inspect $BeTag --format '  {{.RepoTags}} size={{.Size}}'
docker image inspect $FeTag --format '  {{.RepoTags}} size={{.Size}}'

# ---- 推送 ----
if ($Push) {
    Write-Host '--------------------------------------------------------------'
    Write-Host ' 推送到 Docker Hub ...'
    docker push $BeTag
    if ($LASTEXITCODE -ne 0) { Write-Error '后端镜像推送失败（是否已 docker login？）'; exit 1 }
    docker push $FeTag
    if ($LASTEXITCODE -ne 0) { Write-Error '前端镜像推送失败'; exit 1 }
    if ($Latest) {
        docker push "$Namespace/gatekeeper:backend-latest"
        docker push "$Namespace/gatekeeper:frontend-latest"
    }
    Write-Host '[push] 完成，digest 如下：'
    docker image inspect $BeTag --format '  {{index .RepoDigests 0}}'
    docker image inspect $FeTag --format '  {{index .RepoDigests 0}}'
}

# ---- 导出 ----
if ($Save) {
    $OutDir = Join-Path $RepoRoot 'dist\docker'
    if (-not (Test-Path -LiteralPath $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
    $beTar = Join-Path $OutDir "gatekeeper-backend-$Version.tar"
    $feTar = Join-Path $OutDir "gatekeeper-frontend-$Version.tar"
    docker save -o $beTar $BeTag
    docker save -o $feTar $FeTag
    $sums = @()
    foreach ($f in @($beTar, $feTar)) {
        $h = (Get-FileHash -LiteralPath $f -Algorithm SHA256).Hash.ToLower()
        $sums += "$h  $(Split-Path -Leaf $f)"
    }
    $sums | Out-File -Encoding ascii (Join-Path $OutDir "gatekeeper-docker-$Version-SHA256SUMS.txt")
    Write-Host "[save] 已导出到 $OutDir"
    Get-ChildItem -LiteralPath $OutDir | Select-Object Name, Length
}

Write-Host '=============================================================='
Write-Host ' 全部完成。运行示例（需先准备 MySQL / Redis，或直接 docker compose up -d）：'
Write-Host "   docker run -d --name gk-backend -p 8080:8080 -e GATEKEEPER_DB_HOST=<db> -e GATEKEEPER_DB_PASSWORD=<pwd> -e GATEKEEPER_REDIS_HOST=<redis> -e GATEKEEPER_REDIS_PASSWORD=<pwd> -e GATEKEEPER_JWT_SECRET=<32+位随机串> -e GATEKEEPER_AES_KEY=<32+位随机串> $BeTag"
Write-Host "   docker run -d --name gk-frontend -p 8081:80 $FeTag"
Write-Host '=============================================================='
