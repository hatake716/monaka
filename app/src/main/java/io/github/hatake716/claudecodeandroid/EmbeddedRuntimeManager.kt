package io.github.hatake716.claudecodeandroid

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/** Owns the Linux runtime completely inside CCFA. */
object EmbeddedRuntimeManager {
    const val DEFAULT_CONTAINER = "ccfa-linux"
    const val UBUNTU_RELEASE = "24.04.4"

    private const val UBUNTU_BASE_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
    private const val STATE_FILE = "active-container"
    private const val INSTALL_MARKER = ".ccfa-rootfs"
    private const val LEGACY_INSTALL_MARKER = ".claude-code-android-rootfs"

    data class LaunchSpec(
        val executable: String,
        val cwd: String,
        val args: Array<String>,
        val env: Array<String>,
        val title: String
    )

    data class InstallProgress(
        val phase: String,
        val message: String,
        val percent: Int? = null
    )

    enum class LaunchMode { SHELL, COMMAND }

    fun runtimeDir(context: Context) = File(context.filesDir, "embedded-runtime")
    fun cacheDir(context: Context) = File(runtimeDir(context), "cache")
    fun tempDir(context: Context) = File(runtimeDir(context), "tmp")
    fun containersDir(context: Context) = File(runtimeDir(context), "containers")
    fun workspaceDir(context: Context) = File(context.filesDir, "workspace")
    private fun activeFile(context: Context) = File(runtimeDir(context), STATE_FILE)
    fun containerDir(context: Context, name: String) = File(containersDir(context), name)
    fun rootfsDir(context: Context, name: String) = File(containerDir(context, name), "rootfs")

    /**
     * PRoot へ渡すホストパスの正規形。
     *
     * 現代の Android では Context.filesDir が "/data/user/0/<pkg>/..." を返す一方、
     * カーネル/bionic の realpath はアプリのマウント名前空間で同じ場所を
     * "/data/data/<pkg>/..." と名付けることがある(端末・バージョン依存)。
     * PRoot は --rootfs を起動時に realpath で正規化するが、PROOT_L2S_DIR は
     * 環境変数の文字列をそのまま使う。両者の綴りが食い違うと、link2symlink が
     * 生成する疑似ハードリンク(絶対パスのシンボリックリンク)を PRoot 自身が
     * ホストパスと認識できず、ゲストパスとして誤翻訳して ENOENT になる。
     * 具体的には初回セットアップの dpkg が perl-base のハードリンク展開
     * (link → fchownat)で「error setting ownership ... No such file or
     * directory」で必ず失敗する(Pixel 10a / Android 17 実機で確認)。
     *
     * 対策: PRoot に渡すすべてのホストパスを canonicalPath(= PRoot 内部の
     * realpath と同じ答え)に揃え、プレフィックスの不一致を構造的に防ぐ。
     */
    private fun File.canonical(): String =
        runCatching { canonicalPath }.getOrDefault(absolutePath)

    private fun nativeDir(context: Context) = File(context.applicationInfo.nativeLibraryDir)
    private fun prootBinary(context: Context) = File(nativeDir(context), "libproot.so")
    private fun prootLoader(context: Context) = File(nativeDir(context), "libproot-loader.so")
    private fun shmemLibrary(context: Context) = File(nativeDir(context), "libandroid-shmem.so")
    private fun tallocLibrary(context: Context) = File(nativeDir(context), "libtalloc.so")

    fun isSupportedAbi(): Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    fun ensureHostRuntime(context: Context): Result<File> = runCatching {
        check(isSupportedAbi()) { "現在の内蔵LinuxランタイムはARM64端末のみ対応しています。" }
        runtimeDir(context).mkdirs()
        cacheDir(context).mkdirs()
        tempDir(context).mkdirs()
        containersDir(context).mkdirs()
        workspaceDir(context).mkdirs()

        val required = listOf(
            prootBinary(context) to 50_000L,
            prootLoader(context) to 1_000L,
            shmemLibrary(context) to 1_000L,
            tallocLibrary(context) to 10_000L
        )
        required.forEach { (file, minimum) ->
            check(file.isFile && file.length() > minimum) {
                "Android向けPRootランタイム ${file.name} がAPK内にありません。裏CCFAを再インストールしてください。"
            }
        }
        prootBinary(context)
    }

    fun listContainers(context: Context): List<String> =
        containersDir(context).listFiles()
            ?.filter { dir ->
                dir.isDirectory && (
                    File(dir, "rootfs/$INSTALL_MARKER").isFile ||
                        File(dir, "rootfs/$LEGACY_INSTALL_MARKER").isFile
                    )
            }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    fun activeContainer(context: Context): String? {
        val containers = listContainers(context)
        if (containers.isEmpty()) return null
        val saved = activeFile(context).readTextSafely().trim()
        if (saved in containers) return saved
        return containers.first().also { setActiveContainer(context, it) }
    }

    fun setActiveContainer(context: Context, name: String): Result<Unit> = runCatching {
        require(name in listContainers(context)) { "コンテナ '$name' は存在しません。" }
        runtimeDir(context).mkdirs()
        activeFile(context).writeText("$name\n")
    }

    fun deleteContainer(context: Context, name: String): Result<Unit> = runCatching {
        require(isValidContainerName(name)) { "無効なコンテナ名です。" }
        val target = containerDir(context, name)
        require(target.isDirectory) { "コンテナ '$name' は存在しません。" }
        check(target.deleteRecursively()) { "コンテナ '$name' を削除できませんでした。" }
        if (activeFile(context).readTextSafely().trim() == name) {
            val next = listContainers(context).firstOrNull()
            if (next == null) activeFile(context).delete()
            else activeFile(context).writeText("$next\n")
        }
        Unit
    }

    fun installUbuntuContainer(
        context: Context,
        name: String,
        onProgress: (InstallProgress) -> Unit,
        onComplete: (Result<Unit>) -> Unit
    ) {
        val appContext = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        fun progress(value: InstallProgress) = main.post { onProgress(value) }

        Thread({
            val result = runCatching {
                require(isValidContainerName(name)) {
                    "コンテナ名は英数字で開始し、英数字・.・_・- のみ使用できます。"
                }

                progress(InstallProgress("runtime", "Android/Bionic PRootランタイムを確認しています…", 2))
                ensureHostRuntime(appContext).getOrThrow()
                val hostVersion = hostProotSelfTest(appContext).getOrThrow()
                progress(InstallProgress("runtime", "PRoot本体テスト成功: $hostVersion", 4))
                check(name !in listContainers(appContext)) { "同名のコンテナがすでにあります。" }

                val archive = File(cacheDir(appContext), "ubuntu-base-$UBUNTU_RELEASE-arm64.tar.gz")
                if (!archive.isFile || archive.length() < 20_000_000L) {
                    progress(InstallProgress("download", "Linux Baseのダウンロードを開始します…", 5))
                    downloadWithRetry(
                        UBUNTU_BASE_URL,
                        archive,
                        onAttempt = { attempt ->
                            if (attempt > 1) {
                                progress(InstallProgress("download", "Linux Baseを再試行しています ($attempt/3)…"))
                            }
                        },
                        onBytes = { current, total ->
                            val mib = current / (1024.0 * 1024.0)
                            if (total > 0) {
                                val raw = (current * 100L / total).toInt().coerceIn(0, 100)
                                val overall = 5 + raw * 40 / 100
                                val totalMib = total / (1024.0 * 1024.0)
                                progress(
                                    InstallProgress(
                                        "download",
                                        "Linux Baseをダウンロード中… %.1f / %.1f MiB (%d%%)".format(
                                            mib,
                                            totalMib,
                                            raw
                                        ),
                                        overall
                                    )
                                )
                            } else {
                                progress(
                                    InstallProgress(
                                        "download",
                                        "Linux Baseをダウンロード中… %.1f MiB".format(mib)
                                    )
                                )
                            }
                        }
                    )
                } else {
                    progress(InstallProgress("download", "キャッシュ済みLinux Baseを使用します。", 45))
                }

                val rootfs = rootfsDir(appContext, name)
                if (rootfs.exists()) rootfs.deleteRecursively()
                check(rootfs.mkdirs() || rootfs.isDirectory) {
                    "Linux rootfs用ディレクトリを作成できません。"
                }

                progress(InstallProgress("extract", "Linux rootfsを展開しています…"))
                extractRootfs(archive, rootfs) { count ->
                    if (count % 500 == 0) {
                        progress(InstallProgress("extract", "Linux rootfsを展開中… $count files"))
                    }
                }

                progress(InstallProgress("configure", "Linux環境の基本設定を作成しています…", 82))
                configureRootfs(rootfs)

                progress(InstallProgress("self-test", "Android版PRootで /bin/sh を起動しています…", 88))
                val testOutput = selfTestContainer(appContext, name).getOrThrow()
                progress(InstallProgress("self-test", "Linux起動テスト成功: $testOutput", 96))

                File(rootfs, INSTALL_MARKER).writeText(
                    "base=ubuntu-$UBUNTU_RELEASE\n" +
                        "installed=${System.currentTimeMillis()}\n" +
                        "self_test=ok\n" +
                        "proot=termux-android\n"
                )
                setActiveContainer(appContext, name).getOrThrow()
                progress(InstallProgress("complete", "コンテナ '$name' の作成が完了しました。", 100))
                Unit
            }.onFailure {
                File(rootfsDir(appContext, name), INSTALL_MARKER).delete()
            }
            main.post { onComplete(result) }
        }, "CCFALinuxInstaller").start()
    }

    private fun hostProotSelfTest(context: Context): Result<String> = runCatching {
        val proot = ensureHostRuntime(context).getOrThrow()
        val result = runHostProcess(
            context = context,
            command = listOf(proot.absolutePath, "--version"),
            rootfs = null,
            timeoutSeconds = 10,
            verbose = false
        )
        check(result.exitCode == 0) {
            "Android版PRoot本体を実行できません (exit=${result.exitCode}): ${result.output.ifBlank { "no output" }}"
        }
        result.output.lineSequence().firstOrNull().orEmpty().ifBlank { "proot executable OK" }
    }

    fun selfTestContainer(context: Context, name: String): Result<String> = runCatching {
        ensureHostRuntime(context).getOrThrow()
        val rootfs = rootfsDir(context, name)
        check(File(rootfs, "bin/sh").isFile) {
            "Linux rootfsに /bin/sh がありません。展開に失敗しています。"
        }

        val result = runHostProcess(
            context = context,
            command = baseProotArgs(context, rootfs) + listOf(
                "/bin/sh", "-c", "printf 'embedded-runtime-ok'"
            ),
            rootfs = rootfs,
            timeoutSeconds = 25,
            verbose = true
        )
        check(result.exitCode == 0) {
            "PRootセルフテスト失敗 (exit=${result.exitCode}): ${result.output.ifBlank { "no output" }}"
        }
        check(result.output.contains("embedded-runtime-ok")) {
            "Linuxセルフテストの応答が不正です: ${result.output.ifBlank { "no output" }}"
        }
        "embedded-runtime-ok"
    }

    fun buildLaunchSpec(
        context: Context,
        container: String,
        mode: LaunchMode,
        command: String? = null
    ): Result<LaunchSpec> = runCatching {
        val d = '$'
        val proot = ensureHostRuntime(context).getOrThrow()
        require(container in listContainers(context)) { "コンテナ '$container' がありません。" }
        val rootfs = rootfsDir(context, container)

        val guestCommand = when (mode) {
            LaunchMode.SHELL -> "exec /bin/bash -l"
            LaunchMode.COMMAND -> {
                require(!command.isNullOrBlank()) { "コマンドが空です。" }
                "$command; rc=${d}?; printf '\\n\\n[exit: %s] Enterでシェルへ戻ります...' \"${d}rc\"; read _; exec /bin/bash -l"
            }
        }

        val args = baseProotArgs(context, rootfs).toMutableList()
        // 裏CCFA: 共有ストレージは baseProotArgs 内で /sdcard・/storage として直接
        // bind 済み（全ファイルアクセス許可時）。SAF コピー同期は行わない。
        args += listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/root/.local/bin",
            "/bin/bash", "-lc", guestCommand
        )

        LaunchSpec(
            executable = proot.absolutePath,
            cwd = runtimeDir(context).absolutePath,
            args = args.toTypedArray(),
            env = hostEnvironment(context, rootfs, verbose = false)
                .map { "${it.key}=${it.value}" }
                .toTypedArray(),
            title = when (mode) {
                LaunchMode.SHELL -> "$container — Linux"
                LaunchMode.COMMAND -> "$container — Task"
            }
        )
    }

    private fun baseProotArgs(context: Context, rootfs: File): List<String> {
        workspaceDir(context).mkdirs()
        File(rootfs, ".l2s").mkdirs()
        File(rootfs, "phone").mkdirs()
        val args = mutableListOf(
            prootBinary(context).canonical(),
            "--kill-on-exit",
            "--link2symlink",
            "-L",
            "--change-id=0:0",
            // canonicalPath 必須: PROOT_L2S_DIR と同じ綴りでなければならない(上記 canonical() 参照)
            "--rootfs=${rootfs.canonical()}",
            "--cwd=/workspace",
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=${workspaceDir(context).canonical()}:/workspace"
        )
        // 裏CCFA: 全ファイルアクセス（MANAGE_EXTERNAL_STORAGE）が許可されている場合、
        // 共有ストレージ全体を Linux 側へ直接 bind mount する。SAF のようなコピーでは
        // ないため、Android 側 / Linux 側どちらの書き込みも即座に相手へ反映される
        // （＝実ファイルを共有する真のリアルタイム同期）。
        args += sharedStorageBindArgs(context)
        return args
    }

    /**
     * 全ファイルアクセスが許可されているときに追加する共有ストレージの bind 引数。
     *
     * - /storage/emulated/0 → /sdcard（内部ストレージ。最も使う）
     * - /storage           → /storage（SD カード等のセカンダリボリュームも到達可能に）
     *
     * 権限が無い場合は空リスト（PRoot はアプリ専用領域のみを見る＝CCFA 相当の安全動作）。
     * bind 先ディレクトリ（/sdcard, /storage）が rootfs 内に無い端末向けに、事前作成する。
     */
    private fun sharedStorageBindArgs(context: Context): List<String> {
        if (!AllFilesAccessManager.hasAccess(context)) return emptyList()
        val binds = mutableListOf<String>()
        val active = activeContainer(context)
        val rootfs = active?.let { rootfsDir(context, it) }

        val primary = File(AllFilesAccessManager.primaryStorageRoot())
        if (primary.isDirectory) {
            rootfs?.let { File(it, "sdcard").mkdirs() }
            binds += "--bind=${primary.canonical()}:${AllFilesAccessManager.GUEST_MOUNT}"
        }
        // /storage 配下（emulated 以外の物理 SD カード等）もまとめて見せる。
        val storageRoot = File("/storage")
        if (storageRoot.isDirectory) {
            rootfs?.let { File(it, "storage").mkdirs() }
            binds += "--bind=/storage:/storage"
        }
        return binds
    }

    /** 全ファイルアクセス権限が付与されているか（裏CCFA では bind mount の可否を表す）。 */
    fun hasSharedStorageAccess(context: Context): Boolean =
        AllFilesAccessManager.hasAccess(context)

    fun isValidContainerName(name: String): Boolean =
        name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))

    private fun configureRootfs(rootfs: File) {
        val etc = File(rootfs, "etc").apply { mkdirs() }
        File(etc, "resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        File(etc, "hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(rootfs, "root").mkdirs()
        File(rootfs, "workspace").mkdirs()
        File(rootfs, "phone").mkdirs()
        // 裏CCFA: 共有ストレージの bind mount 先を用意する（全ファイルアクセス許可時に
        // /storage/emulated/0 → /sdcard、/storage → /storage を bind する）。
        File(rootfs, "sdcard").mkdirs()
        File(rootfs, "storage").mkdirs()
        File(rootfs, ".l2s").mkdirs()
        File(rootfs, "tmp").apply {
            mkdirs()
            runCatching { Os.chmod(absolutePath, 0b111_111_111) }
        }
    }

    private fun downloadWithRetry(
        url: String,
        destination: File,
        onAttempt: (Int) -> Unit,
        onBytes: (Long, Long) -> Unit
    ) {
        var lastError: Throwable? = null
        for (attempt in 1..3) {
            onAttempt(attempt)
            try {
                downloadTo(url, destination, onBytes)
                return
            } catch (t: Throwable) {
                lastError = t
                File(destination.parentFile, destination.name + ".part").delete()
                if (attempt < 3) Thread.sleep(1500L * attempt)
            }
        }
        throw IllegalStateException(
            "Linux Baseを3回試行しましたがダウンロードできませんでした: ${lastError?.message ?: "unknown error"}",
            lastError
        )
    }

    private fun downloadTo(
        url: String,
        destination: File,
        progress: (Long, Long) -> Unit
    ) {
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, destination.name + ".part")
        temp.delete()
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "CCFA/0.9.0")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.connect()
            check(connection.responseCode in 200..299) {
                "HTTP ${connection.responseCode} ${connection.responseMessage}"
            }
            val total = connection.contentLengthLong
            var written = 0L
            var lastReport = 0L
            progress(0, total)
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written - lastReport >= 256 * 1024) {
                            lastReport = written
                            progress(written, total)
                        }
                    }
                    output.fd.sync()
                }
            }
            progress(written, total)
            check(written > 20_000_000L) {
                "Linux Baseのダウンロードサイズが不正です (${written} bytes)。"
            }
            Files.move(
                temp.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun extractRootfs(archive: File, rootfs: File, progress: (Int) -> Unit) {
        val rootCanonical = rootfs.canonicalFile
        val pendingHardLinks = mutableListOf<Pair<File, String>>()
        var count = 0
        GzipCompressorInputStream(BufferedInputStream(archive.inputStream())).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val output = safeTarget(rootCanonical, entry.name)
                    progress(++count)
                    when {
                        entry.isDirectory -> output.mkdirs()
                        entry.isSymbolicLink -> {
                            output.parentFile?.mkdirs()
                            Files.deleteIfExists(output.toPath())
                            Files.createSymbolicLink(output.toPath(), File(entry.linkName).toPath())
                        }
                        entry.isLink -> pendingHardLinks += output to entry.linkName
                        entry.isFile -> {
                            output.parentFile?.mkdirs()
                            FileOutputStream(output).use { tar.copyTo(it) }
                            runCatching { Os.chmod(output.absolutePath, entry.mode and 0x1ff) }
                        }
                    }
                }
            }
        }
        pendingHardLinks.forEach { (output, linkName) ->
            val source = safeTarget(rootCanonical, linkName)
            if (source.exists()) {
                output.parentFile?.mkdirs()
                Files.deleteIfExists(output.toPath())
                Files.copy(
                    source.toPath(),
                    output.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        }
    }

    private data class ProcessResult(val exitCode: Int, val output: String)

    private fun runHostProcess(
        context: Context,
        command: List<String>,
        rootfs: File?,
        timeoutSeconds: Long,
        verbose: Boolean
    ): ProcessResult {
        val builder = ProcessBuilder(command)
            .directory(runtimeDir(context))
            .redirectErrorStream(true)
        builder.environment().apply {
            clear()
            putAll(hostEnvironment(context, rootfs, verbose))
        }
        val process = builder.start()
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            error("PRootテストが${timeoutSeconds}秒以内に完了しませんでした。")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        return ProcessResult(process.exitValue(), output)
    }

    private fun hostEnvironment(
        context: Context,
        rootfs: File?,
        verbose: Boolean
    ): MutableMap<String, String> = linkedMapOf<String, String>().apply {
        put("HOME", context.filesDir.canonical())
        put("TMPDIR", tempDir(context).canonical())
        put("PROOT_TMP_DIR", tempDir(context).canonical())
        put("PROOT_LOADER", prootLoader(context).canonical())
        put("PROOT_NO_SECCOMP", "1")
        put("LD_LIBRARY_PATH", nativeDir(context).canonical())
        put("PATH", "/system/bin:/system/xbin")
        put("ANDROID_DATA", "/data")
        put("ANDROID_ROOT", "/system")
        put("TERM", "xterm-256color")
        if (rootfs != null) {
            val l2s = File(rootfs, ".l2s").apply { mkdirs() }
            // canonicalPath 必須: PRoot は --rootfs を realpath で正規化する一方、
            // PROOT_L2S_DIR は文字列をそのまま使うため、綴りを揃えないと
            // link2symlink の疑似ハードリンクが解決不能になる(canonical() の説明参照)。
            put("PROOT_L2S_DIR", l2s.canonical())
        }
        if (verbose) put("PROOT_VERBOSE", "9")
    }

    private fun safeTarget(root: File, name: String): File {
        val cleaned = name.removePrefix("./").removePrefix("/")
        val output = File(root, cleaned).canonicalFile
        check(output.path == root.path || output.path.startsWith(root.path + File.separator)) {
            "Unsafe archive path: $name"
        }
        return output
    }

    private fun File.readTextSafely(): String =
        runCatching { if (isFile) readText() else "" }.getOrDefault("")
}
