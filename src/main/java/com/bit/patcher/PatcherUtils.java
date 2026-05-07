package com.bit.patcher;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextBrowseFolderListener;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.SimpleListCellRenderer;

import javax.swing.plaf.basic.BasicComboBoxEditor;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具类：ToolWindow 组件初始化与导出文件解析。
 *
 * @author Liang
 */
public class PatcherUtils {

    /** 对应 plugin.xml 中的 &lt;toolWindow id&gt; 与 &lt;notificationGroup id&gt;。 */
    public static final String PATCHER_ID = "Patcher";

    private PatcherUtils() {
    }

    /**
     * 把模块添加到模块的下拉框
     *
     * @param project            项目
     * @param moduleNameComboBox 模块的下拉框
     */
    public static void setModuleNameComboBox(Project project, ComboBox<String> moduleNameComboBox) {
        // 显示渲染：对内部哨兵渲染为本地化的“多模块”文案（下拉列表项）
        moduleNameComboBox.setRenderer(SimpleListCellRenderer.create("",
                item -> PatcherConstants.MULTI_MODULE_SENTINEL.equals(item)
                        ? PatcherBundle.message("patcher.value.multi.module")
                        : item));
        // 可编辑 ComboBox 的选中框由 Editor 控制，不走 Renderer，
        // 这里自定义 Editor 把哨兵值双向映射为本地化文案。
        moduleNameComboBox.setEditor(new BasicComboBoxEditor() {
            @Override
            public void setItem(Object anObject) {
                if (PatcherConstants.MULTI_MODULE_SENTINEL.equals(anObject)) {
                    editor.setText(PatcherBundle.message("patcher.value.multi.module"));
                } else {
                    super.setItem(anObject);
                }
            }

            @Override
            public Object getItem() {
                String text = editor.getText();
                if (PatcherBundle.message("patcher.value.multi.module").equals(text)) {
                    return PatcherConstants.MULTI_MODULE_SENTINEL;
                }
                return text;
            }
        });
        // 根据项目获取模块
        Module[] modules = ModuleManager.getInstance(project).getModules();
        if (modules.length > 1) {
            moduleNameComboBox.addItem(PatcherConstants.MULTI_MODULE_SENTINEL);
        }
        for (Module module : modules) {
            moduleNameComboBox.addItem(module.getName());
        }
    }

    /**
     * 根据依赖库添加项目类型
     *
     * @param project            项目
     * @param moduleTypeComboBox 项目类型的下拉框
     */
    public static void setLibraryComboBox(Project project, ComboBox<PatcherModuleType> moduleTypeComboBox) {
        moduleTypeComboBox.addItem(PatcherModuleType.builder()
                .name(PatcherBundle.message("patcher.module.type.spring.framework"))
                .type(PatcherConstants.BOOT_INF).build());
        moduleTypeComboBox.addItem(PatcherModuleType.builder()
                .name(PatcherBundle.message("patcher.module.type.java.ee"))
                .type(PatcherConstants.WEB_INF).build());
        moduleTypeComboBox.addItem(PatcherModuleType.builder()
                .name(PatcherBundle.message("patcher.module.type.java.se"))
                .type("").build());
        // 根据项目获取依赖库：一次遍历同时识别 Spring Boot 与 Spring，以 Spring Boot 优先
        Library[] libraries = LibraryTablesRegistrar.getInstance().getLibraryTable(project).getLibraries();
        boolean hasSpringBoot = false;
        boolean hasSpring = false;
        for (Library library : libraries) {
            String name = library.getName();
            if (name == null) {
                continue;
            }
            if (name.contains(PatcherConstants.LIB_SPRING_BOOT)) {
                hasSpringBoot = true;
                break;
            }
            if (name.contains(PatcherConstants.LIB_SPRING)) {
                hasSpring = true;
            }
        }
        if (hasSpringBoot) {
            moduleTypeComboBox.setSelectedIndex(0);
        } else if (hasSpring) {
            moduleTypeComboBox.setSelectedIndex(1);
        } else {
            moduleTypeComboBox.setSelectedIndex(2);
        }
    }

    /**
     * 设置保存路径默认值
     *
     * @param textFieldWithBrowseButton 保存路径的文本框
     */
    public static void setDefaultSavePath(TextFieldWithBrowseButton textFieldWithBrowseButton) {
        // 获取桌面路径
        String desktopPath = System.getProperty("user.home") + File.separator
                + PatcherConstants.DEFAULT_SAVE_PATH_SUFFIX;
        // 设置文本框的默认值
        textFieldWithBrowseButton.setText(desktopPath);
    }

    /**
     * 设置补丁保存路径监听
     *
     * @param project                   项目
     * @param textFieldWithBrowseButton 保存路径的文本框
     */
    public static void setBrowseFolderListener(Project project, TextFieldWithBrowseButton textFieldWithBrowseButton) {
        // withTitle 替代弃用的 setTitle；addBrowseFolderListener(TextBrowseFolderListener)
        // 替代弃用的 (Project, FileChooserDescriptor) 重载。
        FileChooserDescriptor descriptor = new FileChooserDescriptor(false, true, false, false, false, false)
                .withTitle(PatcherBundle.message("patcher.value.select.save.path"));
        textFieldWithBrowseButton.addBrowseFolderListener(new TextBrowseFolderListener(descriptor, project));
    }

    /**
     * 解析指定 VirtualFile（含子树）为 PatcherVirtualFile 列表。
     * 工具类只做纯解析，不再直接写 Service 状态，由调用方按需消费。
     * <p>
     * 实现采用显式栈迭代，避免深度目录递归导致栈溢出。
     * 同时自动关联生成源码目录中与选中文件相关的文件（如 MapStruct 生成的 Mapper）。
     */
    public static List<PatcherVirtualFile> getExportFile(Project project, VirtualFile virtualFile) {
        List<PatcherVirtualFile> result = new ArrayList<>();
        Deque<VirtualFile> stack = new ArrayDeque<>();
        stack.push(virtualFile);
        while (!stack.isEmpty()) {
            VirtualFile current = stack.pop();
            if (current.isDirectory()) {
                for (VirtualFile child : current.getChildren()) {
                    stack.push(child);
                }
                continue;
            }
            Module moduleForFile = ModuleUtil.findModuleForFile(current, project);
            if (moduleForFile == null) {
                continue;
            }
            result.add(PatcherVirtualFile.builder()
                    .virtualFile(current)
                    .module(moduleForFile)
                    .build());
        }
        // 自动关联生成源码中的相关文件
        collectRelatedGeneratedSources(project, result);
        return result;
    }

    /**
     * 扫描模块的生成源码目录，查找与已选文件类名相关的生成文件。
     * 例如：选中 TenantListVo.java 后，自动关联 TenantListVoToXxxMapper.java 等。
     */
    private static void collectRelatedGeneratedSources(Project project, List<PatcherVirtualFile> result) {
        if (result.isEmpty()) {
            return;
        }
        // 收集所有已选文件的类名前缀（不含 .java 后缀）
        Set<String> classNames = new HashSet<>();
        Set<String> existingPaths = new HashSet<>();
        Set<Module> modules = new HashSet<>();
        for (PatcherVirtualFile pvf : result) {
            existingPaths.add(pvf.getPath());
            String name = pvf.getName();
            if (name.endsWith(PatcherConstants.JAVA_EXT)) {
                classNames.add(name.substring(0, name.length() - PatcherConstants.JAVA_EXT.length()));
            }
            if (pvf.getModule() != null) {
                modules.add(pvf.getModule());
            }
        }
        if (classNames.isEmpty()) {
            return;
        }
        // 遍历相关模块的所有生成源码目录
        for (Module module : modules) {
            VirtualFile[] sourceRoots = ModuleRootManager.getInstance(module).getSourceRoots();
            for (VirtualFile sourceRoot : sourceRoots) {
                if (!ProjectRootManager.getInstance(project).getFileIndex().isInGeneratedSources(sourceRoot)) {
                    continue;
                }
                // 在生成源码目录中搜索关联文件
                Deque<VirtualFile> scanStack = new ArrayDeque<>();
                scanStack.push(sourceRoot);
                while (!scanStack.isEmpty()) {
                    VirtualFile current = scanStack.pop();
                    if (current.isDirectory()) {
                        for (VirtualFile child : current.getChildren()) {
                            scanStack.push(child);
                        }
                        continue;
                    }
                    // 跳过已存在的文件
                    if (existingPaths.contains(current.getPath())) {
                        continue;
                    }
                    String fileName = current.getName();
                    if (!fileName.endsWith(PatcherConstants.JAVA_EXT)) {
                        continue;
                    }
                    // 检查文件名是否以某个已选类名开头（关联生成文件）
                    for (String className : classNames) {
                        if (fileName.startsWith(className) && fileName.length() > className.length() + PatcherConstants.JAVA_EXT.length()) {
                            result.add(PatcherVirtualFile.builder()
                                    .virtualFile(current)
                                    .module(module)
                                    .build());
                            existingPaths.add(current.getPath());
                            break;
                        }
                    }
                }
            }
        }
    }
}
