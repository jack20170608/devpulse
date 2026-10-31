$ErrorActionPreference = 'Stop'

$pom = Join-Path $PSScriptRoot 'pom.xml'
$jar = Join-Path $PSScriptRoot 'target\devpulse.jar'

& mvn --batch-mode --no-transfer-progress --quiet --file $pom package
if ($LASTEXITCODE -ne 0) {
  exit $LASTEXITCODE
}

& java -jar $jar @args
exit $LASTEXITCODE
