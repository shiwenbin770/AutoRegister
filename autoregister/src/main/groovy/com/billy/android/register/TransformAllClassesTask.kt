package com.billy.android.register

import com.android.utils.FileUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.apache.commons.codec.digest.DigestUtils
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File


class TransformAllClassesTask : DefaultTask() {

    @get:InputFiles
    lateinit var allDirectories: ListProperty<Directory>

    @get:InputFiles
    lateinit var allJars: ListProperty<RegularFile>

    @get:OutputFile
    lateinit var output: RegularFileProperty

    @get:Input
    lateinit var config: AutoRegisterConfig

    @TaskAction
    fun transform() {
        project.logger.warn("start auto-register transform...")
        config.reset()
        project.logger.warn(config.toString())

        // clean build cache
        output.asFile.get().delete()

        val time: Long = System.currentTimeMillis()
        val leftSlash = (File.separator == "/")

        val gson: Gson = Gson()
        val cacheFile: File = AutoRegisterHelper.getRegisterCacheFile(project)
        if (cacheFile.exists()) {
            cacheFile.delete()
        }
        val cacheMap: MutableMap<String, ScanJarHarvest> =
            AutoRegisterHelper.readToMap(cacheFile, object : TypeToken<HashMap<String?, ScanJarHarvest?>?>() {}.type) as MutableMap<String, ScanJarHarvest>

        val scanProcessor: CodeScanProcessor = CodeScanProcessor(config.list, cacheMap)

        /**
         * 遍历所有的输入文件，此时仅仅是完成扫描和复制工作，并没有完成注入操作。
         */
        // 遍历jar
        allJars.get().filterNotNull().forEach { jar: RegularFile ->
            cacheMap.remove(jar.asFile.absolutePath)
            scanJar(jar, output, scanProcessor)
        }
        // 遍历目录
        allDirectories.get().filterNotNull().forEach { directory: Directory ->
            val dirTime: Long = System.currentTimeMillis()
            // 获得产物的目录
            val dest = output.getContentLocation(directory.asFile.name, Format.DIRECTORY)
            var root: String = directory.asFile.absolutePath
            if (!root.endsWith(File.separator))
                root += File.separator
            //遍历目录下的每个文件
            directory.files().forEach { file: File ->
                val path: String = file.absolutePath.replace(root, "")
                if (file.isFile) {
                    var entryName = path
                    if (!leftSlash) {
                        entryName = entryName.replace("\\\\", "/")
                    }
                    scanProcessor.checkInitClass(entryName, File(dest.absolutePath + File.separator + path))
                    if (scanProcessor.shouldProcessClass(entryName)) {
                        scanProcessor.scanClass(file)
                    }
                }
            }
            val scanTime: Long = System.currentTimeMillis()
            // 处理完后拷到目标文件
            FileUtils.copyDirectory(directory.asFile, dest)
            println("auto-register cost time: ${System.currentTimeMillis() - dirTime}, scan time: ${scanTime - dirTime}. path=${root}")
        }

        // 缓存所有的扫描结果
        if (cacheMap != null && cacheFile != null && gson != null) {
            val json = gson.toJson(cacheMap)
            AutoRegisterHelper.cacheRegisterHarvest(cacheFile, json)
        }

        val scanFinishTime = System.currentTimeMillis()
        project.logger.error("register scan all class cost time: " + (scanFinishTime - time) + " ms")

        /**
         * 对每一个配置项进行便利，真正执行代码注入操作
         */
        config.list.forEach { ext ->
            if (ext.fileContainsInitClass != null && ext.fileContainsInitClass.exists()) {
                println("")
                println("insert register code to file:" + ext.fileContainsInitClass.absolutePath)
                if (ext.classList.isEmpty()) {
                    project.logger.error("No class implements found for interface:" + ext.interfaceName)
                } else {
                    ext.classList.forEach {
                        println(it)
                    }
                    CodeInsertProcessor.insertInitCodeTo(ext)
                }
            } else {
                project.logger.error("The specified register class not found:" + ext.registerClassName)
            }
        }
        val finishTime: Long = System.currentTimeMillis()
        project.logger.error("register insert code cost time: " + (finishTime - scanFinishTime) + " ms")
        project.logger.error("register cost time: " + (finishTime - time) + " ms")
    }

    private fun scanJar(jarInput: RegularFile, outputProvider: RegularFileProperty, scanProcessor: CodeScanProcessor) {
        // 获得输入文件
        val src: File = jarInput.asFile
        // 遍历jar的字节码类文件，找到需要自动注册的类
        val dest: File = getDestFile(jarInput, outputProvider)
        val time: Long = System.currentTimeMillis();
        if (!scanProcessor.scanJar(src, dest) //直接读取了缓存，没有执行实际的扫描
            //此jar文件中不需要被注入代码
            //为了避免增量编译时代码注入重复，被注入代码的jar包每次都重新复制
            && !scanProcessor.isCachedJarContainsInitClass(src.absolutePath)
        ) {
            //不需要执行文件复制，直接返回
            return
        }
        println("auto-register cost time: " + (System.currentTimeMillis() - time) + " ms to scan jar file:" + dest.absolutePath)
        //复制jar文件到transform目录：build/transforms/auto-register/
        FileUtils.copyFile(src, dest)
    }

    private fun getDestFile(jarInput: RegularFile, outputProvider: RegularFileProperty): File {
        var destName = jarInput.asFile.name
        // 重名名输出文件,因为可能同名,会覆盖
        val hexName = DigestUtils.md5Hex(jarInput.asFile.absolutePath)
        // 如果是以jar结尾，则去掉后四位(.jar)
        if (destName.endsWith(".jar")) {
            destName = destName.substring(0, destName.length - 4)
        }
        // 获得输出文件
        return outputProvider.getContentLocation(destName + "_" + hexName, Format.JAR)
    }

    sealed interface Format {
        val postfix: String
        object JAR : Format {
            override val postfix = ".jar"
        }
        object DIRECTORY : Format {
            override val postfix = ""
        }
    }
    private fun RegularFileProperty.getContentLocation(name: String, format: Format) : File {
        val filePath = get().asFile.absolutePath
        return File(filePath + File.separator + name + format.postfix)
    }
}