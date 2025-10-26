#!/bin/bash

# ========================================
# Android App 完整自動化部署腳本（簡化版）
# ========================================

set -e

# 配置
PROJECT_DIR="/c/Users/User/AndroidStudioProjects/A3"
BUILD_GRADLE_PATH="$PROJECT_DIR/app/build.gradle.kts"
APK_OUTPUT_PATH="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"

# 顏色
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}🚀 Android App 自動化部署${NC}"
echo -e "${GREEN}========================================${NC}\n"

# 切換到專案目錄
cd "$PROJECT_DIR" || exit 1
echo -e "${BLUE}[1/6]${NC} 已切換到專案目錄"

# ========================================
# 更新版本號
# ========================================
echo -e "${BLUE}[2/6]${NC} 更新版本號..."

# 讀取當前版本
CURRENT_VERSION_CODE=$(grep -oP 'versionCode\s*=\s*\K\d+' "$BUILD_GRADLE_PATH")
CURRENT_VERSION_NAME=$(grep -oP 'versionName\s*=\s*"\K[^"]+' "$BUILD_GRADLE_PATH")

echo "   當前版本: v$CURRENT_VERSION_NAME (build $CURRENT_VERSION_CODE)"

# 計算新版本
NEW_VERSION_CODE=$((CURRENT_VERSION_CODE + 1))
VERSION_PARTS=(${CURRENT_VERSION_NAME//./ })
NEW_VERSION_PATCH=$((${VERSION_PARTS[2]} + 1))
NEW_VERSION_NAME="${VERSION_PARTS[0]}.${VERSION_PARTS[1]}.$NEW_VERSION_PATCH"

echo "   新版本: v$NEW_VERSION_NAME (build $NEW_VERSION_CODE)"

# 備份並更新
cp "$BUILD_GRADLE_PATH" "$BUILD_GRADLE_PATH.bak"
sed -i "s/versionCode = $CURRENT_VERSION_CODE/versionCode = $NEW_VERSION_CODE/" "$BUILD_GRADLE_PATH"
sed -i "s/versionName = \"$CURRENT_VERSION_NAME\"/versionName = \"$NEW_VERSION_NAME\"/" "$BUILD_GRADLE_PATH"

echo -e "   ${GREEN}✓${NC} 版本號已更新"

# ========================================
# 編譯 Release APK
# ========================================
echo -e "${BLUE}[3/6]${NC} 編譯 Release APK..."

BUILD_PATH="$PROJECT_DIR/app/build"

# 🔹 自動清理被鎖住的 build 目錄
if [ -d "$BUILD_PATH" ]; then
    echo -e "   ${YELLOW}⚠ 偵測到舊 build 資料夾，嘗試強制刪除...${NC}"
    # 先嘗試移除 lint-cache（這是最常被鎖住的）
    rm -rf "$BUILD_PATH/intermediates/lint-cache" 2>/dev/null || rmdir /S /Q "$BUILD_PATH/intermediates/lint-cache" 2>/dev/null
    # 若仍存在，強制移除整個 build 目錄
    rm -rf "$BUILD_PATH" 2>/dev/null || rmdir /S /Q "$BUILD_PATH" 2>/dev/null
fi

# 🔹 不再執行 gradlew clean，直接編譯
./gradlew assembleRelease --no-daemon --stacktrace


if [ ! -f "$APK_OUTPUT_PATH" ]; then
    echo -e "${RED}✗ 編譯失敗${NC}"
    exit 1
fi

APK_SIZE=$(du -h "$APK_OUTPUT_PATH" | cut -f1)
echo -e "   ${GREEN}✓${NC} APK 編譯完成 ($APK_SIZE)"

# ========================================
# Git Commit & Push
# ========================================
COMMIT_MSG="chore: 自動發布版本 v$NEW_VERSION_NAME (build $NEW_VERSION_CODE)"
git add "$BUILD_GRADLE_PATH"
git commit -m "$COMMIT_MSG"
git push

echo -e "   ${GREEN}✓${NC} 已推送到遠端"

# ========================================
# 上傳到 Firebase
# ========================================
echo -e "${BLUE}[5/6]${NC} 上傳到 Firebase Storage 並更新 Firestore..."

# 檢查是否有 Node.js 腳本
if [ -f "$PROJECT_DIR/firebase-deploy.js" ]; then
    UPDATE_MESSAGE="例行版本更新與效能優化"
	
	node "$PROJECT_DIR/firebase-deploy.js" "$NEW_VERSION_CODE" "$NEW_VERSION_NAME" "$APK_OUTPUT_PATH" "$UPDATE_MESSAGE"

    echo -e "   ${GREEN}✓${NC} Firebase 部署完成"
else
    echo -e "   ${YELLOW}⚠${NC} 找不到 firebase-deploy.js，請手動上傳"
    echo -e "   APK 位置: $APK_OUTPUT_PATH"
fi

# ========================================
# 完成
# ========================================
echo -e "\n${GREEN}========================================${NC}"
echo -e "${GREEN}🎉 部署完成！${NC}"
echo -e "${GREEN}========================================${NC}"
echo -e "版本: ${GREEN}v$NEW_VERSION_NAME${NC} (build $NEW_VERSION_CODE)"
echo -e "APK: $APK_OUTPUT_PATH"
echo -e "${GREEN}========================================${NC}\n"
