import java.io.*
import java.time.*
import java.time.format.DateTimeFormatter

/**
 * Hugo博客管理工具
 *
 * @author konanok<konanok@outlook.com>
 */

val rootPath = System.getProperty("user.dir")!!  // 博客根路径，默认取脚本执行路径
val postsPath = "$rootPath/content/posts"        // 文章路径
val imagesPath = "$rootPath/images"              // 图片路径
val websitePath = "$rootPath/public"             // 博客网站路径

val draftsPath = "$postsPath/_draft"             // 草稿文件路径
val capturesPath = "$imagesPath/_capture"        // 截图文件路径


// 参数解析
when (args[0]) {
    "createPost", "new" -> createPost(args[1])
    "saveImage", "get" -> saveImage()
    "publish", "pub" -> publish(args.copyOfRange(1, args.size))
}


/**
 * 新建文章
 *
 * @param title 文章标题
 */
fun createPost(title: String) {
    val timestamp = System.currentTimeMillis()
    val zonedDateTime = Instant.ofEpochSecond(timestamp / 1000).atZone(ZoneId.systemDefault())

    val frontMatter = """
        ---
        title: "$title"
        date: ${zonedDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}
        draft: true
        slug: p$timestamp
        ---
    """.trimIndent()

    File("$draftsPath/${zonedDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE)}-$title.md").run {
        createNewFile()
        writeText(frontMatter)
        println("create post: $absolutePath")
    }
}

/**
 * 上传图片，并获取图片路径
 */
fun saveImage() {
    val captureName = File(capturesPath).list()?.first()!!
    val imageName = "i${System.currentTimeMillis()}.png"

    // images
    sh {
        // 移动截图文件至images仓库，并对其重命名
        + "mv $capturesPath/$captureName $imagesPath/$imageName"
        // 提交至Github
        + "cd $imagesPath"
        + "git add ."
        + "git commit -m \"add $imageName\" |& cat"
        + "git push"
        // 复制地址到剪切板
        + "echo \"![$imageName](https://raw.githubusercontent.com/konanok/images/main/$imageName)\" | pbcopy"
    }
}

/**
 * 发布博客网站
 *
 * @param message 构建信息
 */
fun publish(message: Array<String>) {
    // posts
    sh {
        + "cd $postsPath"
        + "git add ."
        + "git commit -m '${formatCommit(message)}' |& cat"
        + "git push"
    }
    // website
    sh {
        + "cd $rootPath"
        // 删除原先构建的文件
        + "rm -rf public/*"
        // 重新构建
        + "hugo"
        // 提交至Github
        + "cd $websitePath"
        + "git add ."
        + "git commit -m \$'重新构建：\n${formatCommit(message)}' |& cat"
        + "git push"
    }
    // blog
    sh {
        + "cd $rootPath"
        + "git add ."
        + "git commit -m \$'重新构建：\n${formatCommit(message)}' |& cat"
        + "git push"
    }
}


/**
 * 格式化commit信息
 */
fun formatCommit(message: Array<String>): String {
    return if (message.size == 1) {
        if (message[0].contains("；")) {
            formatCommit(message[0].split("；").toTypedArray())
        } else {
            message[0]
        }
    } else {
        buildString {
            message.forEachIndexed { i, s ->
                append("$i. $s；\n")
            }
        }
    }
}


/**
 * 虚拟Shell
 *
 * 用于在Shell环境下顺序执行一个或多个命令
 */
class VirtualShell(
    /**
     * 是否为debug模式，true：输出命令执行的详细日志，默认为false
     */
    private val isDebug: Boolean = false,
    /**
     * 系统真实执行命令的shell，默认为zsh
     */
    actualShell: String = "/bin/zsh"
) {

    // 系统shell进程
    private val shellProcess = Runtime.getRuntime().exec(actualShell)

    private val commandWriter = BufferedWriter(OutputStreamWriter(shellProcess.outputStream))

    private val stdoutListener = if (isDebug) StandardOutListener(shellProcess.inputStream) else null
    private val stderrListener = if (isDebug) StandardOutListener(shellProcess.errorStream) else null

    // 需要执行的命令
    private val commands = arrayListOf<String>()


    operator fun String.unaryPlus() {
        commands.add(this)
    }


    /**
     * 顺序执行所有输入的命令，只能调用一次
     */
    fun execute() {
        if (isDebug) startListening()

        for (cmd in commands) execute(cmd)
        execute("exit") // 最后执行exit命令来使shell正常退出，否则process会一直阻塞

        shellProcess.waitFor()
        stopListening()
        shellProcess.destroy()
    }

    private fun execute(cmd: String) {
        if (cmd != "exit") println("➜ $cmd")
        commandWriter.write(cmd)
        commandWriter.newLine()
        commandWriter.flush()
    }

    private fun startListening() {
        Thread(stdoutListener!!).start()
        Thread(stderrListener!!).start()
    }

    private fun stopListening() {
        stdoutListener?.shutdown()
        stderrListener?.shutdown()
    }


    /**
     * 标准输出流监听器
     */
    private class StandardOutListener(
        inputStream: InputStream
    ): Runnable {

        private val reader = BufferedReader(InputStreamReader(inputStream))

        private var isRunning = true

        override fun run() {
            while (isRunning) {
                var line: String?
                if (reader.readLine().also { line = it } != null) {
                    println(line!!)
                } else {
                    isRunning = false
                }
            }
            reader.close()
        }

        fun shutdown() {
            isRunning = false
        }

    }

}


/**
 * 在Shell环境下执行命令
 */
fun sh(init: VirtualShell.() -> Unit) {
    VirtualShell(true).apply(init).execute()
}