package com.billy.android.register

import org.gradle.api.Project

/**
 * aop的配置信息
 * @author billy.qi
 * @since 17/3/28 11:48
 */
class AutoRegisterConfig {

    // 一下两项是Auto-register的配置项
    public ArrayList<Map<String, Object>> registerInfo = []

    ArrayList<RegisterInfo> list = new ArrayList<>()

    Project project

    AutoRegisterConfig() {}

    void convertConfig() {
        registerInfo.each { map ->
            RegisterInfo info = new RegisterInfo()

            // 读取需要扫描的接口和父类，可以使用数据指定多个需要扫描父类
            info.interfaceName = map.get('scanInterface')
            def superClasses = map.get('scanSuperClasses')
            if (!superClasses) {
                superClasses = new ArrayList<String>()
            } else if (superClasses instanceof String) {
                ArrayList<String> superList = new ArrayList<>()
                superList.add(superClasses)
                superClasses = superList
            }
            info.superClassNames = superClasses

            //代码注入的类
            info.initClassName = map.get('codeInsertToClassName')
            //代码注入的方法（默认为static块）,static代码块可以在程序启动的时候就初始化并完成代码注入。
            info.initMethodName = map.get('codeInsertToMethodName')
            //生成的代码所调用的方法，一般推荐使用静态方法
            info.registerMethodName = map.get('registerMethodName')
            //注册方法所在的类
            info.registerClassName = map.get('registerClassName')
            info.include = map.get('include')
            info.exclude = map.get('exclude')
            info.init()
            if (info.validate())
                list.add(info)
            else {
                project.logger.error('auto register config error: scanInterface, codeInsertToClassName and registerMethodName should not be null\n' + info.toString())
            }
        }
        checkRegisterInfo()
    }

    /**
     * 检查配置信息是否有改动，如果有改动，就删除缓存文件，并把新的配置信息写入缓存文件。
     * 同时删除已经扫描到的jar包的缓存信息文件。
     */
    private void checkRegisterInfo() {
        def registerInfoCacheFile = AutoRegisterHelper.getRegisterInfoCacheFile(project)
        def listInfo = list.toString()
        def sameInfo = false

        if (!registerInfoCacheFile.exists()) {
            registerInfoCacheFile.createNewFile()
        } else if(registerInfoCacheFile.canRead()) {
            def info = registerInfoCacheFile.text
            sameInfo = info == listInfo
            if (!sameInfo) {
                project.logger.error("auto-register registerInfo has been changed since project(':$project.name') last build")
            }
        } else {
            project.logger.error('auto-register read registerInfo error--------')
        }
        if (!sameInfo) {
            deleteFile(AutoRegisterHelper.getRegisterCacheFile(project))
        }
        if (registerInfoCacheFile.canWrite()) {
            registerInfoCacheFile.write(listInfo)
        } else {
            project.logger.error('auto-register write registerInfo error--------')
        }
    }

    private void deleteFile(File file) {
        if (file.exists()) {
            //registerInfo 配置有改动就删除緩存文件
            file.delete()
        }
    }

    void reset() {
        list.each { info ->
            info.reset()
        }
    }

    @Override
    String toString() {
        StringBuilder sb = new StringBuilder(RegisterPlugin.EXT_NAME).append(' = {')
                .append('\n  cacheEnabled = ').append(cacheEnabled)
                .append('\n  registerInfo = [\n')
        def size = list.size()
        for (int i = 0; i < size; i++) {
            sb.append('\t' + list.get(i).toString().replaceAll('\n', '\n\t'))
            if (i < size - 1)
                sb.append(',\n')
        }
        sb.append('\n  ]\n}')
        return sb.toString()
    }
}