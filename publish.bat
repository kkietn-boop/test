@echo off
set /p REPO_URL="Enter your GitHub Repository URL (e.g., https://github.com/username/miruro-extension): "

echo Initializing local Git repository...
git init
git add .
git commit -m "Initial commit - Miruro Cloudstream Extension"

echo Updating build.gradle.kts with your repository URL...
powershell -Command "(gc build.gradle.kts) -replace 'https://github.com/user/repo', '%REPO_URL%' | Out-File -encoding UTF8 build.gradle.kts"

echo Rebuilding plugin and repository index...
call gradlew.bat clean make makePluginsJson

echo Setting up git branches and pushing...
git remote add origin %REPO_URL%
git branch -M main

:: Create a temporary builds directory to push to the builds branch
echo Creating builds branch and pushing plugin binary...
if exist temp_builds rmdir /s /q temp_builds
mkdir temp_builds
copy MiruroProvider\build\MiruroProvider.cs3 temp_builds\
cd temp_builds
git init
git checkout -b builds
git add .
git commit -m "Publish plugin build"
git remote add origin %REPO_URL%
git push -u origin builds --force
cd ..
rmdir /s /q temp_builds

echo Pushing main repository index...
git add build/plugins.json build.gradle.kts
git commit -m "Update repository index"
git push -u origin main --force

echo ==========================================================
echo Done! Your repository is hosted at:
echo %REPO_URL%/raw/main/build/plugins.json
echo.
echo Add the URL above into your Cloudstream Extensions settings:
echo Settings -> Extensions -> Add Repository
echo ==========================================================
pause
