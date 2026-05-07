package com.bit.patcher;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextBrowseFolderListener;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.SimpleListCellRenderer;

import javax.swing.plaf.basic.BasicComboBoxEditor;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

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
        return result;
    }
}
