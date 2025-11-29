#!/bin/bash

# ========================================
# Android App 完整自動化部署腳本
# ========================================

set -e

# 配置
PROJECT_DIR="/c/Users/User/AndroidStudioProjects/A3"
BUILD_GRADLE_PATH="$PROJECT_DIR/app/build.gradle.kts"
APK_OUTPUT_PATH="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
UPDATE_NOTE_PATH="$PROJECT_DIR/update-note.json"
DEPLOY_HISTORY_DIR="$PROJECT_DIR/deploy-history"

# 顏色
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# 狀態追蹤
DEPLOY_STATUS="failed"
FAIL_REASON=""
VERSION_UPDATED=false
CURRENT_VERSION_CODE=""
CURRENT_VERSION_NAME=""
NEW_VERSION_CODE=""
NEW_VERSION_NAME=""
GIT_COMMIT=""
GIT_BRANCH=""
APK_SIZE=""

# ========================================
# 清理函數（失敗時執行）
# ========================================
cleanup_on_failure() {
    if [ "$DEPLOY_STATUS" != "success" ]; then
        echo -e "\n${RED}========================================${NC}"
        echo -e "${RED}❌ 部署失敗：$FAIL_REASON${NC}"
        echo -e "${RED}========================================${NC}"

        # 如果版本號已更新，則 rollback
        if [ "$VERSION_UPDATED" = true ]; then
            echo -e "${YELLOW}⚠ 正在還原版本號...${NC}"
            if [ -f "$BUILD_GRADLE_PATH.bak" ]; then
                cp "$BUILD_GRADLE_PATH.bak" "$BUILD_GRADLE_PATH"
                rm -f "$BUILD_GRADLE_PATH.bak"
                echo -e "${GREEN}✓${NC} 版本號已還原"
            fi
        fi

        # 寫入失敗紀錄到 Firebase
        echo -e "${YELLOW}⚠ 正在記錄失敗紀錄...${NC}"
        if [ -f "$PROJECT_DIR/firebase-deploy.js" ]; then
            node "$PROJECT_DIR/firebase-deploy.js" \
                --status "failed" \
                --versionCode "${NEW_VERSION_CODE:-$CURRENT_VERSION_CODE}" \
                --versionName "${NEW_VERSION_NAME:-$CURRENT_VERSION_NAME}" \
                --failReason "$FAIL_REASON" \
                --gitCommit "$GIT_COMMIT" \
                --gitBranch "$GIT_BRANCH" \
                --updateNotePath "$UPDATE_NOTE_PATH" \
                2>/dev/null || echo -e "${YELLOW}⚠ 無法記錄失敗紀錄${NC}"
        fi
    fi
}

trap cleanup_on_failure EXIT

# ========================================
# 輔助函數
# ========================================
check_json_valid() {
    local file="$1"
    if ! node -e "JSON.parse(require('fs').readFileSync('$file', 'utf8'))" 2>/dev/null; then
        return 1
    fi
    return 0
}

get_json_value() {
    local file="$1"
    local key="$2"
    node -e "console.log(JSON.parse(require('fs').readFileSync('$file', 'utf8')).$key || '')"
}

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}🚀 Android App 自動化部署${NC}"
echo -e "${GREEN}========================================${NC}\n"

# 切換到專案目錄
cd "$PROJECT_DIR" || exit 1
echo -e "${BLUE}[1/7]${NC} 已切換到專案目錄"

# ========================================
# 檢查 update-note.json
# ========================================
echo -e "${BLUE}[2/7]${NC} 檢查 update-note.json..."

if [ ! -f "$UPDATE_NOTE_PATH" ]; then
    FAIL_REASON="找不到 update-note.json，請先建立更新說明檔案"
    echo -e "   ${RED}✗ $FAIL_REASON${NC}"
    exit 1
fi

if ! check_json_valid "$UPDATE_NOTE_PATH"; then
    FAIL_REASON="update-note.json 格式錯誤，請檢查 JSON 語法"
    echo -e "   ${RED}✗ $FAIL_REASON${NC}"
    exit 1
fi

UPDATE_TITLE=$(get_json_value "$UPDATE_NOTE_PATH" "title")

if [ -z "$UPDATE_TITLE" ]; then
    FAIL_REASON="update-note.json 缺少 title 欄位，請填寫更新標題"
    echo -e "   ${RED}✗ $FAIL_REASON${NC}"
    exit 1
fi

echo -e "   ${GREEN}✓${NC} 更新標題：$UPDATE_TITLE"

# ========================================
# 取得 Git 資訊
# ========================================
GIT_BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")
GIT_COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")
echo -e "   ${GREEN}✓${NC} Git: $GIT_BRANCH @ $GIT_COMMIT"

# ========================================
# 更新版本號
# ========================================
echo -e "${BLUE}[3/7]${NC} 更新版本號..."

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
VERSION_UPDATED=true

echo -e "   ${GREEN}✓${NC} 版本號已更新"

# ========================================
# 編譯 Release APK
# ========================================
echo -e "${BLUE}[4/7]${NC} 編譯 Release APK..."

BUILD_PATH="$PROJECT_DIR/app/build"

# 自動清理被鎖住的 build 目錄
if [ -d "$BUILD_PATH" ]; then
    echo -e "   ${YELLOW}⚠ 偵測到舊 build 資料夾，嘗試強制刪除...${NC}"
    rm -rf "$BUILD_PATH/intermediates/lint-cache" 2>/dev/null || rmdir /S /Q "$BUILD_PATH/intermediates/lint-cache" 2>/dev/null || true
    rm -rf "$BUILD_PATH" 2>/dev/null || rmdir /S /Q "$BUILD_PATH" 2>/dev/null || true
fi

# 編譯
if ! ./gradlew assembleRelease --no-daemon --stacktrace; then
    FAIL_REASON="APK 編譯失敗"
    exit 1
fi

if [ ! -f "$APK_OUTPUT_PATH" ]; then
    FAIL_REASON="編譯完成但找不到 APK 檔案"
    exit 1
fi

APK_SIZE=$(du -h "$APK_OUTPUT_PATH" | cut -f1)
echo -e "   ${GREEN}✓${NC} APK 編譯完成 ($APK_SIZE)"

# 編譯成功後刪除備份
rm -f "$BUILD_GRADLE_PATH.bak"
VERSION_UPDATED=false  # 不需要 rollback 了

# ========================================
# Git Commit & Push
# ========================================
echo -e "${BLUE}[5/7]${NC} Git Commit & Push..."

COMMIT_MSG="chore: 自動發布版本 v$NEW_VERSION_NAME (build $NEW_VERSION_CODE)"
git add "$BUILD_GRADLE_PATH"

if ! git commit -m "$COMMIT_MSG"; then
    FAIL_REASON="Git commit 失敗"
    exit 1
fi

if ! git push; then
    FAIL_REASON="Git push 失敗"
    exit 1
fi

# 更新 commit hash（push 後的最新）
GIT_COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")
echo -e "   ${GREEN}✓${NC} 已推送到遠端 ($GIT_COMMIT)"

# ========================================
# 上傳到 Firebase
# ========================================
echo -e "${BLUE}[6/7]${NC} 上傳到 Firebase Storage 並更新資料庫..."

if [ -f "$PROJECT_DIR/firebase-deploy.js" ]; then
    if ! node "$PROJECT_DIR/firebase-deploy.js" \
        --status "success" \
        --versionCode "$NEW_VERSION_CODE" \
        --versionName "$NEW_VERSION_NAME" \
        --apkPath "$APK_OUTPUT_PATH" \
        --apkSize "$APK_SIZE" \
        --gitCommit "$GIT_COMMIT" \
        --gitBranch "$GIT_BRANCH" \
        --updateNotePath "$UPDATE_NOTE_PATH"; then
        FAIL_REASON="Firebase 部署失敗"
        exit 1
    fi
    echo -e "   ${GREEN}✓${NC} Firebase 部署完成"
else
    FAIL_REASON="找不到 firebase-deploy.js"
    exit 1
fi

# ========================================
# 備份 update-note.json
# ========================================
echo -e "${BLUE}[7/7]${NC} 備份更新說明..."

# 建立備份資料夾
mkdir -p "$DEPLOY_HISTORY_DIR"

# 備份
BACKUP_FILENAME="update-note-$NEW_VERSION_NAME.json"
cp "$UPDATE_NOTE_PATH" "$DEPLOY_HISTORY_DIR/$BACKUP_FILENAME"

# 清空成範本
cat > "$UPDATE_NOTE_PATH" << 'EOF'
{
  "title": "",
  "items": []
}
EOF

echo -e "   ${GREEN}✓${NC} 已備份至 deploy-history/$BACKUP_FILENAME"

# ========================================
# 完成
# ========================================
DEPLOY_STATUS="success"

echo -e "\n${GREEN}========================================${NC}"
echo -e "${GREEN}🎉 部署完成！${NC}"
echo -e "${GREEN}========================================${NC}"
echo -e "版本: ${GREEN}v$NEW_VERSION_NAME${NC} (build $NEW_VERSION_CODE)"
echo -e "Git: $GIT_BRANCH @ $GIT_COMMIT"
echo -e "APK: $APK_OUTPUT_PATH ($APK_SIZE)"
echo -e "${GREEN}========================================${NC}\n"