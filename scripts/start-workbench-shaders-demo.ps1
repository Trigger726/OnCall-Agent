param([int]$Port=9900,[string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
$demoRepoRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$demoJarPath=Join-Path $demoRepoRoot 'target/opspilot-v1.8-workbench-shaders-final.jar'
if(-not (Test-Path -LiteralPath $demoJarPath)){throw 'Missing workbench UI demo JAR; see the UI acceptance report.'}
if(-not $JavaHome){throw 'Set JAVA_HOME to JDK 21 before starting the demo.'}
$demoJavaPath=Join-Path $JavaHome 'bin/java.exe'
if(-not (Test-Path -LiteralPath $demoJavaPath)){throw 'JAVA_HOME does not contain bin/java.exe.'}
if($Port -lt 1024 -or $Port -ge 65534){throw 'Use an unprivileged port below 65534.'}
foreach($demoCheckedPort in @($Port,($Port+1))){
    $demoListener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$demoCheckedPort)
    try{$demoListener.Start()}finally{$demoListener.Stop()}
}
$demoDataPath=Join-Path $demoRepoRoot 'target/workbench-shaders-demo-data'
New-Item -ItemType Directory -Path $demoDataPath -Force | Out-Null
$demoJdbc='jdbc:h2:file:'+$demoDataPath.Replace('\','/')+'/demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0'
Write-Host "Workbench preview: http://127.0.0.1:$Port (isolated demo database, existing data retained)"
& $demoJavaPath -jar $demoJarPath '--server.address=127.0.0.1' "--server.port=$Port" `
    '--management.server.address=127.0.0.1' "--management.server.port=$($Port+1)" `
    "--spring.datasource.url=$demoJdbc" '--spring.datasource.username=sa' '--spring.datasource.password=' '--spring.datasource.driver-class-name=org.h2.Driver' `
    '--spring.h2.console.enabled=false' '--opspilot.ai.enabled=false' '--opspilot.runbook.semantic.enabled=false' '--spring.ai.dashscope.embedding.enabled=false' `
    '--opspilot.agent.events.outbox-enabled=false' '--opspilot.agent.recovery.enabled=false' `
    '--opspilot.oncall.rotation.enabled=false' '--opspilot.oncall.escalation.enabled=false' `
    '--opspilot.oncall.open.notification.enabled=false' '--opspilot.oncall.swap.notification.enabled=false' `
    '--opspilot.observability.prometheus.enabled=false' '--opspilot.observability.loki.enabled=false'
exit $LASTEXITCODE
