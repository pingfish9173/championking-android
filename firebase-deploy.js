const admin = require('firebase-admin');
const fs = require('fs');
const path = require('path');

// ========================================
// 配置區
// ========================================
const CONFIG = {
  serviceAccountPath: './serviceAccountKey.json',
  storageBucket: 'sca3-69342.firebasestorage.app',
  firestoreCollection: 'app_versions',
  firestoreDocument: 'current',
  rtdbPath: 'deploy_history'
};

// 顏色
const GREEN = '\x1b[32m';
const RED = '\x1b[31m';
const YELLOW = '\x1b[33m';
const NC = '\x1b[0m';

// ========================================
// 解析命令列參數
// ========================================
function parseArgs() {
  const args = process.argv.slice(2);
  const params = {};

  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg.startsWith('--')) {
      const key = arg.substring(2);
      const value = args[i + 1];
      if (value && !value.startsWith('--')) {
        params[key] = value;
        i++;
      }
    }
  }

  return params;
}

// ========================================
// 讀取 update-note.json
// ========================================
function readUpdateNote(filePath) {
  try {
    if (!fs.existsSync(filePath)) {
      return { title: '', items: [] };
    }
    const content = fs.readFileSync(filePath, 'utf8');
    const data = JSON.parse(content);
    return {
      title: data.title || '',
      items: Array.isArray(data.items) ? data.items : []
    };
  } catch (error) {
    console.error(`${YELLOW}⚠️ 讀取 update-note.json 失敗:${NC}`, error.message);
    return { title: '', items: [] };
  }
}

// ========================================
// 初始化 Firebase Admin
// ========================================
function initFirebase() {
  try {
    const serviceAccount = require(CONFIG.serviceAccountPath);

    admin.initializeApp({
      credential: admin.credential.cert(serviceAccount),
      storageBucket: CONFIG.storageBucket,
      databaseURL: `https://${serviceAccount.project_id}-default-rtdb.asia-southeast1.firebasedatabase.app`
    });

    console.log(`${GREEN}✅ Firebase Admin 初始化成功${NC}`);
    return true;
  } catch (error) {
    console.error(`${RED}❌ Firebase Admin 初始化失敗:${NC}`, error.message);
    return false;
  }
}

// ========================================
// 上傳 APK 到 Firebase Storage
// ========================================
async function uploadToStorage(localFilePath, versionName) {
  console.log('\n📤 上傳 APK 到 Firebase Storage...');

  try {
    const bucket = admin.storage().bucket();
    const storageFilePath = `championking-app-release-${versionName}.apk`;

    await bucket.upload(localFilePath, {
      destination: storageFilePath,
      metadata: {
        contentType: 'application/vnd.android.package-archive',
        cacheControl: 'public, max-age=0'
      }
    });

    console.log(`${GREEN}✅ APK 上傳成功${NC}`);

    const file = bucket.file(storageFilePath);
    await file.makePublic();

    console.log(`${GREEN}✅ 已設置為公開讀取${NC}`);

    const publicUrl = `https://storage.googleapis.com/${CONFIG.storageBucket}/${storageFilePath}`;

    const [metadata] = await file.getMetadata();
    const downloadToken = metadata.metadata?.firebaseStorageDownloadTokens;

    let downloadUrl;
    if (downloadToken) {
      downloadUrl = `https://firebasestorage.googleapis.com/v0/b/${CONFIG.storageBucket}/o/${encodeURIComponent(storageFilePath)}?alt=media&token=${downloadToken}`;
    } else {
      downloadUrl = publicUrl;
    }

    console.log('📥 下載 URL:', downloadUrl);

    return downloadUrl;
  } catch (error) {
    console.error(`${RED}❌ 上傳失敗:${NC}`, error.message);
    throw error;
  }
}

// ========================================
// 更新 Firestore（APP 讀取用）
// ========================================
async function updateFirestore(downloadUrl, versionCode, versionName, updateInfo) {
  console.log('\n📝 更新 Firestore...');

  try {
    const db = admin.firestore();

    const updateData = {
      downloadUrl,
      versionCode: parseInt(versionCode),
      versionName,
      updateType: "optional",
      updateInfo: {
        title: updateInfo.title,
        items: updateInfo.items
      },
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    };

    await db.collection(CONFIG.firestoreCollection)
            .doc(CONFIG.firestoreDocument)
            .set(updateData, { merge: true });

    console.log(`${GREEN}✅ Firestore 更新成功${NC}`);
    console.log('   - versionCode:', versionCode);
    console.log('   - versionName:', versionName);
    console.log('   - updateInfo.title:', updateInfo.title);

    return true;
  } catch (error) {
    console.error(`${RED}❌ Firestore 更新失敗:${NC}`, error.message);
    throw error;
  }
}

// ========================================
// 寫入部署紀錄到 Realtime Database
// ========================================
async function saveDeployHistory(params, updateInfo, downloadUrl) {
  console.log('\n📜 寫入部署紀錄到 Realtime Database...');

  try {
    const db = admin.database();
    const ref = db.ref(CONFIG.rtdbPath);

    const historyData = {
      versionCode: parseInt(params.versionCode),
      versionName: params.versionName,
      updateInfo: {
        title: updateInfo.title,
        items: updateInfo.items
      },
      deployedAt: admin.database.ServerValue.TIMESTAMP,
      apkSize: params.apkSize,
      downloadUrl: downloadUrl,
      gitCommit: params.gitCommit,
      gitBranch: params.gitBranch
    };

    // 使用 push() 自動產生 pushId
    const newRef = await ref.push(historyData);

    console.log(`${GREEN}✅ 部署紀錄已寫入${NC}`);
    console.log('   - Record ID:', newRef.key);

    return newRef.key;
  } catch (error) {
    console.error(`${RED}❌ 寫入部署紀錄失敗:${NC}`, error.message);
    throw error;
  }
}

// ========================================
// 主函數
// ========================================
async function main() {
  console.log('\n========================================');
  console.log('🚀 Firebase 部署腳本');
  console.log('========================================\n');

  // 解析參數
  const params = parseArgs();

  console.log('📋 參數:');
  console.log('   - versionCode:', params.versionCode);
  console.log('   - versionName:', params.versionName);
  console.log('   - apkPath:', params.apkPath);
  console.log('   - apkSize:', params.apkSize);
  console.log('   - gitCommit:', params.gitCommit);
  console.log('   - gitBranch:', params.gitBranch);

  // 驗證必要參數
  if (!params.versionCode || !params.versionName || !params.apkPath) {
    console.error(`${RED}❌ 缺少必要參數${NC}`);
    console.log('\n使用方式:');
    console.log('  node firebase-deploy.js --versionCode 5 --versionName 1.0.4 --apkPath ./app.apk --apkSize "12MB" --gitCommit abc123 --gitBranch main --updateNotePath ./update-note.json');
    process.exit(1);
  }

  // 檢查 APK 是否存在
  if (!fs.existsSync(params.apkPath)) {
    console.error(`${RED}❌ 找不到 APK: ${params.apkPath}${NC}`);
    process.exit(1);
  }

  // 讀取更新說明
  const updateNotePath = params.updateNotePath || './update-note.json';
  const updateInfo = readUpdateNote(updateNotePath);
  console.log('   - updateInfo.title:', updateInfo.title || '(空)');
  console.log('   - updateInfo.items:', updateInfo.items.length, '項');

  // 初始化 Firebase
  if (!initFirebase()) {
    process.exit(1);
  }

  try {
    const fileSize = (fs.statSync(params.apkPath).size / 1024 / 1024).toFixed(2);
    console.log(`   - APK Size: ${fileSize} MB`);

    // 上傳到 Storage
    const downloadUrl = await uploadToStorage(params.apkPath, params.versionName);

    // 更新 Firestore
    await updateFirestore(downloadUrl, params.versionCode, params.versionName, updateInfo);

    // 寫入部署紀錄到 Realtime Database
    await saveDeployHistory(params, updateInfo, downloadUrl);

    console.log(`\n${GREEN}========================================${NC}`);
    console.log(`${GREEN}🎉 部署完成！${NC}`);
    console.log(`${GREEN}========================================${NC}\n`);

    // 關閉 Firebase 連接並正常退出
    await admin.app().delete();
    process.exit(0);

  } catch (error) {
    console.error(`\n${RED}❌ 部署失敗:${NC}`, error.message);
    process.exit(1);
  }
}

// 執行
main();