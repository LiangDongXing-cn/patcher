package com.bit.patcher;

/**
 * 集中承载散落在各处的魔法字符串与内部哨兵常量。
 *
 * @author Liang
 */
public final class PatcherConstants {

    private PatcherConstants() {
    }

    // ---------------------------------------------------------------------
    // 打包结构目录名
    // ---------------------------------------------------------------------

    /** Spring Boot 打包结构里的 class 根目录名。 */
    public static final String BOOT_INF = "BOOT-INF";

    /** 传统 Java EE 打包结构里的 class 根目录名。 */
    public static final String WEB_INF = "WEB-INF";

    /** Web 资源在源码里的目录片段（用于识别需要导出的 webapp 资源）。 */
    public static final String WEBAPP_SEGMENT = "/webapp/";

    /** 导出源码时追加到项目名后的目录后缀。 */
    public static final String SOURCES_SUFFIX = "-sources";

    // ---------------------------------------------------------------------
    // 依赖库名识别片段
    // ---------------------------------------------------------------------

    /** 依赖库名包含 "spring" 认为是 Spring 生态。 */
    public static final String LIB_SPRING = "spring";

    /** 依赖库名包含 "spring-boot" 认为是 Spring Boot。 */
    public static final String LIB_SPRING_BOOT = "spring-boot";

    // ---------------------------------------------------------------------
    // 文件扩展名
    // ---------------------------------------------------------------------

    /** Java 源码扩展名。 */
    public static final String JAVA_EXT = ".java";

    /** Java 编译产物扩展名。 */
    public static final String CLASS_EXT = ".class";

    // ---------------------------------------------------------------------
    // 默认路径
    // ---------------------------------------------------------------------

    /** 默认保存路径：用户主目录下的桌面目录名。 */
    public static final String DEFAULT_SAVE_PATH_SUFFIX = "Desktop";

    // ---------------------------------------------------------------------
    // 内部哨兵
    // ---------------------------------------------------------------------

    /**
     * moduleName 下拉框"多模块"选项的内部哨兵值。
     * 以 \u0000 前缀保证不会与任何真实模块名冲突；显示文本由 Renderer 通过 i18n 解析。
     */
    public static final String MULTI_MODULE_SENTINEL = "\u0000__multi_module__";
}
