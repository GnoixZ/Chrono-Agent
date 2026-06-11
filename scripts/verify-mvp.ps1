$ErrorActionPreference = "Stop"

$preferredJdk = "E:\OpenSources\jdk23"
if (Test-Path $preferredJdk) {
    $env:JAVA_HOME = $preferredJdk
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
}

Write-Host "Checking PostgreSQL container..."
docker compose up -d postgres

Write-Host "Running backend tests..."
Push-Location backend
.\mvnw test
Pop-Location

Write-Host "Running model service tests..."
Push-Location model-service
.\.venv\Scripts\python -m pytest
Pop-Location

Write-Host "MVP verification completed."
