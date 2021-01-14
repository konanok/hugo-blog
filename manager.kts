import java.io.*
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * Hugo博客管理工具
 *
 * @author konanok<konanok@outlook.com>
 */

val name = "manager"   // 名称
val version = "v1.0.0" // 版本号
val synopsis =         // 帮助文本
"""
Usage: $name <options> <command> <arguments>

Options:
    -version, -v                        输出版本信息
    -help, -h                           输出帮助信息
    -quiet, -q                          静默执行命令

Command:
    createPost <title>                  创建文章，<title>：文章标题
    saveImage                           上传图片，获取截图文件并上传至Github
    deploy <message>                    部署博客，同时提交所有的子项目，<message>：Git提交的信息
"""


val rootPath = System.getProperty("user.dir")!! // 博客根路径
val postsPath = "$rootPath/content/posts"       // 文章路径
val imagesPath = "$rootPath/images"             // 图片路径
val websitePath = "$rootPath/public"            // 博客网站路径

val capturedImagesPath = "$imagesPath/capture"  // 截图存放文件路径
val capturedImageFinder: File.() -> String = {  // 查找截图文件的方法，选取截图目录下的第一个文件
    list()?.first()!!
}


var isQuiet = false                             // 是否静默执行命令

if (args.isEmpty()) {
    printHelp()
    exitProcess(0)
}

when (args[0]) {
    "-version", "-v" -> printVersion()
    "-help", "-h" -> printHelp()
    "createPost" -> createPost(args[1])
    "saveImage" -> saveImage()
    "deploy" -> deploy(args[1])
    "-quiet", "-q" -> {
        isQuiet = true
        when (args[1]) {
            "createPost" -> createPost(args[2])
            "saveImage" -> saveImage()
            "deploy" -> deploy(args[2])
        }
    }
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
        slug: p$timestamp
        isCJKLanguage: true
        showtoc: true
        tocopen: true
        ---
    """.trimIndent()

    File("$postsPath/${zonedDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE)}-$title.md").run {
        createNewFile()
        writeText(frontMatter)
        printInfo("create post: $absolutePath")
    }
}

/**
 * 上传图片
 */
fun saveImage() {
    val capturedImageName = File(capturedImagesPath).run(capturedImageFinder)
    val imageName = "i${System.currentTimeMillis()}.png"

    // images
    sh {
        // 移动截图文件至images仓库，并对其重命名
        + "mv $capturedImagesPath/$capturedImageName $imagesPath/$imageName"
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
 * 部署博客
 * 
 * @param message Git提交的信息
 */
fun deploy(message: String) {
    // posts
    sh {
        + "cd $postsPath"
        + "git add ."
        + "git commit -m \"$message\" |& cat"
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
        + "git commit -m \"$message；重新构建\" |& cat"
        + "git push"
    }
    // blog
    sh {
        + "cd $rootPath"
        + "git add ."
        + "git commit -m \"$message；重新构建\" |& cat"
        + "git push"
    }
}


/**
 * 输出帮助信息
 *
 * @param error 错误信息
 */
fun printHelp(error: String = "") {
    if (error.isNotBlank()) printError(error)
    printInfo(synopsis)
}

/**
 * 输出版本信息
 */
fun printVersion() {
    printInfo("$name $version")
}

/**
 * 输出info日志
 */
fun printInfo(info: String) {
    println(info)
}

/**
 * 输出error日志
 */
fun printError(error: String) {
    System.err.println(error)
}


/**
 * 标准输出流监听器
 */
class StandardOutListener(
    inputStream: InputStream
): Runnable {

    private val reader = BufferedReader(InputStreamReader(inputStream))

    private var isRunning = true

    override fun run() {
        while (isRunning) {
            var line: String?
            if (reader.readLine().also { line = it } != null) {
                printInfo(line!!)
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

/**
 * 虚拟Shell
 *
 * 用于在Shell环境下顺序执行一个或多个命令
 */
class VirtualShell(
    // 是否静默执行（忽略输出）
    private val isQuiet: Boolean = false
) {

    // 系统shell进程
    private val shellProcess = Runtime.getRuntime().exec("/bin/zsh")

    private val commandWriter = BufferedWriter(OutputStreamWriter(shellProcess.outputStream))

    private val stdoutListener = if (isQuiet) null else StandardOutListener(shellProcess.inputStream)
    private val stderrListener = if (isQuiet) null else StandardOutListener(shellProcess.errorStream)

    // 需要执行的命令
    private val commands = arrayListOf<String>()


    operator fun String.unaryPlus() {
        commands.add(this)
    }

    /**
     * 顺序执行所有输入的命令，只能调用一次
     */
    fun execute() {
        if (!isQuiet) startListening()

        for (cmd in commands) execute(cmd)
        execute("exit") // 最后执行exit命令来使shell正常退出，否则process会一直阻塞

        shellProcess.waitFor()
        stopListening()
        shellProcess.destroy()
    }

    private fun execute(cmd: String) {
        if (cmd != "exit") printInfo("➜ $cmd")
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
}

/**
 * 在Shell环境下执行命令
 */
fun sh(init: VirtualShell.() -> Unit) {
    VirtualShell(isQuiet).apply(init).execute()
}