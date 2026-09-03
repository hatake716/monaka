import java.util.Properties

plugins {
    id("com.android.application")
}

// -----------------------------------------------------------------------------
// Google Play release signing (google-play branch)
//
// アップロード鍵は「keystore.properties」ファイル、または環境変数から読み込む。
// どちらも存在しない場合は release 署名構成を作らず、ビルドは従来どおり
// assembleDebug（debug 署名）でローカル検証できる状態を保つ。
//
// keystore.properties（リポジトリにはコミットしない。keystore.properties.example を参照）:
//   storeFile=/absolute/path/to/ccfa-upload.jks
//   storePassword=...
//   keyAlias=ccfa-upload
//   keyPassword=...
//
// CI では代わりに環境変数を使う:
//   CCFA_UPLOAD_STORE_FILE / CCFA_UPLOAD_STORE_PASSWORD /
//   CCFA_UPLOAD_KEY_ALIAS / CCFA_UPLOAD_KEY_PASSWORD
// -----------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

fun signingValue(propKey: String, envKey: String): String? =
    (keystoreProps.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }

val uploadStoreFilePath = signingValue("storeFile", "CCFA_UPLOAD_STORE_FILE")
val uploadStorePassword = signingValue("storePassword", "CCFA_UPLOAD_STORE_PASSWORD")
val uploadKeyAlias = signingValue("keyAlias", "CCFA_UPLOAD_KEY_ALIAS")
val uploadKeyPassword = signingValue("keyPassword", "CCFA_UPLOAD_KEY_PASSWORD")
val hasReleaseSigning =
    uploadStoreFilePath != null &&
        uploadStorePassword != null &&
        uploadKeyAlias != null &&
        uploadKeyPassword != null

val runtimeDir = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")
val embeddedProotLibrary = runtimeDir.file("libproot.so")
val embeddedProotLoader = runtimeDir.file("libproot-loader.so")
val embeddedShmem = runtimeDir.file("libandroid-shmem.so")
val embeddedTalloc = runtimeDir.file("libtalloc.so")
val legalDir = layout.projectDirectory.dir("src/main/assets/legal")

val verifyEmbeddedRuntime by tasks.registering {
    group = "verification"
    description = "Verify the Android/Bionic PRoot runtime bundle prepared by scripts/prepare-termux-android-proot.sh"
    inputs.files(embeddedProotLibrary, embeddedProotLoader, embeddedShmem, embeddedTalloc)

    doLast {
        val required = listOf(
            embeddedProotLibrary.asFile to 100_000L,
            embeddedProotLoader.asFile to 1_000L,
            embeddedShmem.asFile to 1_000L,
            embeddedTalloc.asFile to 10_000L
        )
        required.forEach { (file, minimum) ->
            check(file.isFile && file.length() > minimum) {
                "Missing embedded runtime component ${file.name}. Run scripts/prepare-termux-android-proot.sh before building."
            }
        }
    }
}

val verifyDistributionLegal by tasks.registering {
    group = "verification"
    description = "Require licenses, attribution notices and corresponding source in every distribution APK"
    val required = listOf(
        "NOTICE.txt",
        "licenses/APACHE-2.0.txt",
        "licenses/GPL-2.0.txt",
        "licenses/GPL-3.0.txt",
        "licenses/LGPL-3.0.txt",
        "licenses/BSD-3-Clause-libandroid-shmem.txt",
        "licenses/TERMUX-TERMINAL-LICENSE.md",
        "licenses/COMMONS-COMPRESS-NOTICE.txt",
        "licenses/COMMONS-CODEC-NOTICE.txt",
        "licenses/COMMONS-IO-NOTICE.txt",
        "licenses/COMMONS-LANG3-NOTICE.txt",
        "sources/proot-v5.1.107.92.zip",
        "sources/libandroid-shmem-v0.7.tar.gz.source",
        "sources/talloc-2.4.3.tar.gz.source",
        "sources/ccfa-prepare-termux-android-proot.sh",
        "sources/termux-terminal-emulator-v0.118.0-termux.c",
        "sources/ccfa-build-terminal-emulator-16k.sh",
        "SOURCE-AND-LICENSE-MANIFEST.sha256"
    )
    inputs.files(required.map { legalDir.file(it) })
    doLast {
        required.forEach { relative ->
            val file = legalDir.file(relative).asFile
            check(file.isFile && file.length() > 0L) {
                "Missing distribution legal/source asset: $relative. Run scripts/prepare-distribution-legal.sh before building."
            }
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(verifyEmbeddedRuntime, verifyDistributionLegal)
}

android {
    namespace = "io.github.hatake716.claudecodeandroid"
    compileSdk = 36

    defaultConfig {
        // monaka（sideload 専用 / Google Play 非公開）の独立アプリケーションID。
        // CCFA(io.github.hatake716.ccfa) と別 ID にして同一端末に共存できるようにする。
        applicationId = "io.github.hatake716.monaka"
        minSdk = 26
        // 裏CCFA は Google Play に公開しないため、Play の最新 targetSdk 要件に縛られない。
        // targetSdk 29 に据え置くことで:
        //   - Android 10 では requestLegacyExternalStorage=true が有効になり、
        //     READ/WRITE_EXTERNAL_STORAGE で共有ストレージ全体に生アクセスできる。
        //   - Android 11+ では MANAGE_EXTERNAL_STORAGE（すべてのファイルへのアクセス）で
        //     共有ストレージ全体に生アクセスでき、PRoot が /storage/emulated/0 を
        //     直接 bind mount できる（scoped storage による open 拒否を受けない）。
        //   - targetSdk 29 は W^X 制約(targetSdk 29 で導入)手前のため、PRoot を
        //     nativeLibraryDir から実行する既存構成もそのまま動作する。
        targetSdk = 29
        versionCode = 5
        versionName = "1.2.1"

        // arm64-v8a 専用ランタイム。AAB の ABI 分割で余計な split を作らないよう明示。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(uploadStoreFilePath!!)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
                // v1/v2/v3 署名スキームを有効化（Play App Signing のアップロード鍵用）。
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Play 版は必ず debuggable=false（debug ビルドは Play が受理しない）。
            // release の既定は false だが、意図を明示する。
            isDebuggable = false
            isMinifyEnabled = false
            // minify=false のとき isShrinkResources=true は AGP がビルドを失敗させるため false 必須。
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 署名情報がある時だけ release 署名を割り当てる。無い場合は署名なしの
            // bundleRelease/assembleRelease となり、ローカルで構成確認だけできる。
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // AAB 設定: この APK は arm64-v8a 専用・言語リソースも最小のため、
    // 不要な split を抑制して 1 デバイス 1 構成で配信されるようにする。
    bundle {
        language {
            enableSplit = false
        }
        density {
            enableSplit = true
        }
        abi {
            enableSplit = true
        }
    }

    packaging {
        jniLibs {
            // PRoot ランタイムはネイティブ ELF を「実行」するため、APK 内に圧縮せず
            // 展開された状態で置く必要がある（extractNativeLibs=true と対応）。
            useLegacyPackaging = true
            keepDebugSymbols += "**/libproot.so"
            keepDebugSymbols += "**/libproot-loader.so"
            keepDebugSymbols += "**/libandroid-shmem.so"
            keepDebugSymbols += "**/libtalloc.so"
        }
    }
}

dependencies {
    // terminal-view は Maven の terminal-emulator を引き込むが、その arm64-v8a libtermux.so は
    // PT_LOAD p_align=4K で Google Play の 16KiB page alignment チェックに落ちるため除外し、
    // 同じ v0.118.0 ソースを -z max-page-size=16384 で再ビルドした AAR を同梱する。
    // （再生成: scripts/build-terminal-emulator-16k.sh）
    implementation("com.termux.termux-app:terminal-view:0.118.0") {
        exclude(group = "com.termux.termux-app", module = "terminal-emulator")
    }
    implementation(files("libs/terminal-emulator-0.118.0-16k.aar"))
    implementation("org.apache.commons:commons-compress:1.27.1")
    // ターミナルの履歴サイドペイン(左端スワイプで開くドロワー)に使用。
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    // monaka は SAF コピー同期を廃止し全ファイルアクセス + 直接 bind mount にしたため、
    // DocumentFile（androidx.documentfile）依存は不要になった。
}
