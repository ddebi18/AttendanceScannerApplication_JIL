@echo off
echo Starting Attendance Scanner Backend...
echo.
echo Make sure you have set GEMINI_API_KEY in the .env file
echo Get a free key at: https://aistudio.google.com/apikey
echo.
cd /d "%~dp0"
python app.py
pause
