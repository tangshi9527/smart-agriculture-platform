@echo off
cd /d "%~dp0..\frontend"
call npm install
call npm run dev
