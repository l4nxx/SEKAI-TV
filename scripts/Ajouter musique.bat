@echo off
rem Ouvre l'app « Musique TV » : une fenetre a part (Chromium en mode application) pour ajouter des
rem morceaux a l'app Musique de la TV. Rien a installer : le Python de tools/venv et le navigateur
rem deja presents suffisent, et tout reste en local.
cd /d "%~dp0.."
if exist "tools\venv\Scripts\pythonw.exe" (
    start "" "tools\venv\Scripts\pythonw.exe" "work\music-pc\app.py"
) else (
    start "" pythonw "work\music-pc\app.py"
)
