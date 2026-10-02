<#
.SYNOPSIS
  측정 세션의 외부 안전장치: 지정 시각에 scripts/tear-down.sh와 --audit-only를 실행하는 Windows 예약 작업.

.DESCRIPTION
  측정 세션을 돌리는 에이전트·셸이 멈추면(앱 종료, 네트워크 단절, 판단 불능) 정상 경로의 철거가 실행되지 않고
  클러스터가 계속 과금된다. 이 예약 작업은 그때를 위한 것이다. 정상 경로의 철거를 대신하지 않는다 —
  정상 경로로 철거와 감사(G4)를 끝냈으면 -Unregister로 해제한다.

  결과는 artifacts/loadtest/<session-id>/scheduled-teardown-<시각>.log에 남는다. 철거 성공은 종료 코드가 아니라
  그 안의 audit 출력(잔여 0)으로 판정한다(TS-038).

  한계:
  - PC가 켜져 있고 이 사용자로 로그온돼 있어야 실행된다. 절전 상태면 깨워서 실행한다(WakeToRun).
    전원이 꺼져 있으면 실행되지 않는다(켜진 뒤 StartWhenAvailable로 실행된다).
  - 이 사용자의 AWS 자격증명(SSO 캐시)을 그대로 쓴다. 실행 시각에 자격증명이 유효해야 한다.

.EXAMPLE
  # 등록(측정 세션 시작 + 4시간 30분)
  powershell -File scripts/loadtest/schedule-teardown.ps1 -Session 20261002-1400 -At (Get-Date).AddMinutes(270)
  # 등록 내용만 보기
  powershell -File scripts/loadtest/schedule-teardown.ps1 -Session 20261002-1400 -At (Get-Date).AddMinutes(270) -DryRun
  # 상태 / 해제
  powershell -File scripts/loadtest/schedule-teardown.ps1 -Session 20261002-1400 -Status
  powershell -File scripts/loadtest/schedule-teardown.ps1 -Session 20261002-1400 -Unregister
#>
param(
  [Parameter(Mandatory = $true)][ValidatePattern('^\d{8}-\d{4}$')][string]$Session,
  [datetime]$At,
  [switch]$Unregister,
  [switch]$Status,
  [switch]$DryRun
)
$ErrorActionPreference = 'Stop'

$TaskName = "FlowTicket-Teardown-$Session"
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

if ($Status) {
  $t = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
  if (-not $t) { "등록돼 있지 않다: $TaskName"; exit 0 }
  $i = Get-ScheduledTaskInfo -TaskName $TaskName
  [pscustomobject]@{ Task = $TaskName; State = $t.State; NextRun = $i.NextRunTime; LastRun = $i.LastRunTime; LastResult = $i.LastTaskResult }
  exit 0
}

if ($Unregister) {
  if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    "해제했다: $TaskName"
  } else {
    "등록돼 있지 않다(이미 해제됨): $TaskName"
  }
  exit 0
}

if (-not $At) { throw '-At(실행 시각)이 필요하다' }
if ($At -le (Get-Date)) { throw "실행 시각이 이미 지났다: $At" }

# Git for Windows의 bash를 쓴다. System32\bash.exe는 WSL이라 이 저장소의 AWS 자격증명·경로를 보지 못한다.
$Bash = @(
  (Join-Path $env:ProgramFiles 'Git\bin\bash.exe'),
  (Join-Path ${env:ProgramFiles(x86)} 'Git\bin\bash.exe')
) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $Bash) {
  $git = (Get-Command git -ErrorAction SilentlyContinue).Source
  if ($git) { $Bash = Join-Path (Split-Path (Split-Path $git)) 'bin\bash.exe' }
}
if (-not $Bash -or -not (Test-Path $Bash)) { throw 'Git for Windows의 bash.exe를 찾지 못했다' }

# 실행 내용은 bash 스크립트에 있다. 작업에는 인자만 넘겨 인용(quoting) 문제를 만들지 않는다.
$Script = 'scripts/loadtest/scheduled-teardown.sh'
$Arguments = "$Script $Session"
$Action = New-ScheduledTaskAction -Execute $Bash -Argument $Arguments -WorkingDirectory $Repo
$Trigger = New-ScheduledTaskTrigger -Once -At $At
# 절전이면 깨운다. 실행 시각에 PC가 꺼져 있었으면 켜진 뒤 실행한다. 철거가 길어질 수 있어 2시간까지 둔다.
$Settings = New-ScheduledTaskSettingsSet -WakeToRun -StartWhenAvailable -AllowStartIfOnBatteries `
  -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Hours 2)
# 암호를 저장하지 않는다(Interactive). 그래서 이 사용자가 로그온돼 있을 때만 실행된다.
$Principal = New-ScheduledTaskPrincipal -UserId ([System.Security.Principal.WindowsIdentity]::GetCurrent().Name) `
  -LogonType Interactive -RunLevel Limited

if ($DryRun) {
  [pscustomobject]@{
    Task = $TaskName; At = $At; Bash = $Bash; WorkingDirectory = $Repo; Arguments = $Arguments
    WakeToRun = $Settings.WakeToRun; StartWhenAvailable = $Settings.StartWhenAvailable
    ExecutionTimeLimit = $Settings.ExecutionTimeLimit; LogonType = $Principal.LogonType
  } | Format-List
  'DryRun: 등록하지 않았다'
  exit 0
}

Register-ScheduledTask -TaskName $TaskName -Action $Action -Trigger $Trigger -Settings $Settings `
  -Principal $Principal -Description "FlowTicket 측정 세션 $Session 강제 철거(외부 안전장치)" -Force | Out-Null
"등록했다: $TaskName @ $At"
