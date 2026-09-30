@echo off
cd /d "%~dp0"
copy /b "NetflixPro-Cinematic-TV-Mobile-4K.mp4.part1"+"NetflixPro-Cinematic-TV-Mobile-4K.mp4.part2" "NetflixPro-Cinematic-TV-Mobile-4K.mp4"
if errorlevel 1 (
  echo Could not join the video. Extract all files into the same folder first.
  pause
  exit /b 1
)
echo Video ready: NetflixPro-Cinematic-TV-Mobile-4K.mp4
pause
