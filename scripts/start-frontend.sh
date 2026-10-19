#!/usr/bin/env bash
# 启动前端大屏（开发模式）
set -e
cd "$(dirname "$0")/../frontend"
npm install
npm run dev
