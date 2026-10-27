@echo off
setlocal

rem Always run from the folder this script lives in, regardless of where it's double-clicked from.
cd /d "%~dp0"

set JAVA_HOME=C:\Program Files\Java\jdk-25.0.2
set PATH=%JAVA_HOME%\bin;%PATH%

echo Building SkyBlockItems...
echo (First run downloads Gradle + all dependencies - can take a few minutes.)
echo.

call gradlew.bat build

if errorlevel 1 (
	echo.
	echo Build FAILED. Scroll up for the error - see README.md for the couple of
	echo spots most likely to need a one-line fix on a first build.
	pause
	exit /b 1
)

echo.
echo Build succeeded. Copying jar into this folder...

set FOUND=0
for %%F in (build\libs\*.jar) do (
	echo %%~nF | findstr /i "sources dev" >nul
	if errorlevel 1 (
		copy /y "%%F" "%%~nxF" >nul
		echo   -^> %%~nxF
		set FOUND=1
	)
)

if "%FOUND%"=="0" (
	echo Could not find a built jar in build\libs\ - check the output above.
	pause
	exit /b 1
)

echo.
echo Done.
pause
