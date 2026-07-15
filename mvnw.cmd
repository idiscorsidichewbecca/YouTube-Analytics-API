@echo off
setlocal
set BASE_DIR=%~dp0
set MAVEN_VERSION=3.9.11
set CACHE_DIR=%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MAVEN_VERSION%
set MAVEN_HOME=%CACHE_DIR%\apache-maven-%MAVEN_VERSION%
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  if not exist "%CACHE_DIR%" mkdir "%CACHE_DIR%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$u='https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip'; $z='%CACHE_DIR%\maven.zip'; Invoke-WebRequest -Uri $u -OutFile $z; Expand-Archive -Path $z -DestinationPath '%CACHE_DIR%' -Force"
)
call "%MAVEN_HOME%\bin\mvn.cmd" %*
endlocal
