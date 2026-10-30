@echo off
REM One-click runner for the Phase-0 runtime spike.
REM Uses DBeaver's bundled JRE and the SWT jar from your DBeaver install.
REM No JDK install required -- the classes are pre-compiled in .\out.

setlocal

set "DBEAVER=%LOCALAPPDATA%\dbeaver"
set "JAVA=%DBEAVER%\jre\bin\java.exe"

REM Resolve the SWT jar (version may change across DBeaver updates).
set "SWT="
for %%J in ("%DBEAVER%\plugins\org.eclipse.swt.win32.win32.x86_64_*.jar") do set "SWT=%%J"

if not exist "%JAVA%" (
  echo [spike] Could not find DBeaver's bundled Java at: %JAVA%
  echo         Edit DBEAVER in this script to point at your DBeaver install.
  exit /b 2
)
if not defined SWT (
  echo [spike] Could not find the SWT jar under %DBEAVER%\plugins
  exit /b 2
)

REM SWT's native .dll files ship loose in the DBeaver root (not in the jar).
REM Point java.library.path at them so SWT doesn't look in the empty ~\.swt dir.
set "SWTLIB=%DBEAVER%"

echo [spike] Java    = %JAVA%
echo [spike] SWT jar = %SWT%
echo [spike] SWT lib = %SWTLIB%
echo.

"%JAVA%" "-Djava.library.path=%SWTLIB%" -cp "%~dp0out;%SWT%" org.jkiss.dbeaver.ext.graph3d.spike.SwtBrowserSpike "%~dp0probe.html"

endlocal
