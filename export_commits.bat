@echo off
:: 設定編碼為 UTF-8 (避免中文亂碼)
chcp 65001 > nul

:: 定義桌面路徑與檔名
set "OUTPUT_PATH=%USERPROFILE%\Desktop\full_commits_log.txt"

echo 正在產出完整的 Commit Message (精確至分)...

:: 使用 --date=format 自定義格式，移除秒數與時區
:: %Y-%m-%d %H:%M 為 年-月-日 時:分
git log --format="%%ad | %%s" --date=format:"%%Y-%%m-%%d %%H:%%M" > "%OUTPUT_PATH%"

echo.
echo ------------------------------------------
echo 產出成功！已匯出所有紀錄。
echo 檔案路徑：%OUTPUT_PATH%
echo ------------------------------------------
pause