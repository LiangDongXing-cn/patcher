package com.bit.patcher;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.roots.CompilerModuleExtension;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBCheckBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 导出服务：项目级 Service，负责补丁文件导出的完整流水线。
 *
 * @author Liang
 */
@Service(Service.Level.PROJECT)
public final class ExportService implements Disposable {

    private static final Logger LOG = Logger.getInstance(ExportService.class);

    private final Project project;

    public ExportService(Project project) {
        this.project = project;
    }

    public static ExportService getInstance(Project project) {
        return project.getService(ExportService.class);
    }

    /**
     * 注册导出按钮的点击行为。重复调用会清理已注册的 ActionListener，确保单次触发。
     */
    public void exportFile(TextFieldWithBrowseButton savePathTextFieldWithBrowseButton,
            ComboBox<String> moduleNameComboBox,
            ComboBox<PatcherModuleType> moduleTypeComboBox,
            JBCheckBox exportTheSourceCodeJbCheckBox,
            JBCheckBox deleteOldPatcherFilesJbCheckBox,
            JBCheckBox deleteToTrashJbCheckBox,
            JButton exportButton) {
        // 防止重复注册
        for (ActionListener l : exportButton.getActionListeners()) {
            exportButton.removeActionListener(l);
        }
        exportButton.addActionListener(e -> onExportClick(
                savePathTextFieldWithBrowseButton,
                moduleNameComboBox,
                moduleTypeComboBox,
                exportTheSourceCodeJbCheckBox,
                deleteOldPatcherFilesJbCheckBox,
                deleteToTrashJbCheckBox,
                exportButton));
    }

    private void onExportClick(TextFieldWithBrowseButton savePathTextFieldWithBrowseButton,
            ComboBox<String> moduleNameComboBox,
            ComboBox<PatcherModuleType> moduleTypeComboBox,
            JBCheckBox exportTheSourceCodeJbCheckBox,
            JBCheckBox deleteOldPatcherFilesJbCheckBox,
            JBCheckBox deleteToTrashJbCheckBox,
            JButton exportButton) {
        Map<String, List<PatcherVirtualFile>> virtualFilesMap = PatcherProjectService.getInstance(project)
                .getVirtualFilesMap();
        if (virtualFilesMap.isEmpty()) {
            PatcherNotificationUtils.warningNotification(project,
                    PatcherBundle.message("patcher.notification.empty.file.list"));
            return;
        }
        final String savePath = savePathTextFieldWithBrowseButton.getText().trim();

        // EDT 侧仅做廉价校验：是否为空 / 是否存在。canWrite 涉及 IO，放到后台任务里
        if (StringUtil.isEmptyOrSpaces(savePath)) {
            PatcherNotificationUtils.warningNotification(project,
                    PatcherBundle.message("patcher.notification.invalid.save.path"));
            return;
        }

        // 持久化保存路径，重启 IDE 后自动恢复
        PatcherSettings.getInstance(project).setSavePath(savePath);

        exportButton.setEnabled(false);
        final Runnable reenable = () -> ApplicationManager.getApplication().invokeLater(
                () -> exportButton.setEnabled(true));

        final boolean deleteOld = deleteOldPatcherFilesJbCheckBox.isSelected();
        final boolean deleteToTrash = deleteToTrashJbCheckBox.isSelected();
        final boolean exportSources = exportTheSourceCodeJbCheckBox.isSelected();

        Task.Backgroundable preTask = new Task.Backgroundable(project, PatcherBundle.message("patcher.name"), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                // 删除旧文件的文件数不确定，先走 indeterminate；源码导出阶段再切回确定模式。
                indicator.setIndeterminate(true);

                // 挪到后台线程：避免远程盘 stat 阻塞 EDT
                File saveDir = new File(savePath);
                if (!saveDir.exists()) {
                    PatcherNotificationUtils.warningNotification(project,
                            PatcherBundle.message("patcher.notification.save.path.not.exist", savePath));
                    return;
                }
                if (!saveDir.canWrite()) {
                    PatcherNotificationUtils.warningNotification(project,
                            PatcherBundle.message("patcher.notification.save.path.not.writable", savePath));
                    return;
                }

                if (deleteOld) {
                    indicator.setText(PatcherBundle.message("patcher.progress.delete.old"));
                    exportDeleteFile(savePath, deleteToTrash, indicator);
                }
                indicator.checkCanceled();

                if (exportSources) {
                    indicator.setIndeterminate(false);
                    indicator.setText(PatcherBundle.message("patcher.progress.export.sources"));
                    exportSourcesFile(savePath, indicator);
                }
                indicator.setFraction(1.0);
            }

            @Override
            public void onSuccess() {
                scheduleClassExport(savePath, moduleNameComboBox, moduleTypeComboBox, reenable);
            }

            @Override
            public void onCancel() {
                PatcherNotificationUtils.warningNotification(project,
                        PatcherBundle.message("patcher.notification.export.cancelled"));
                reenable.run();
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                super.onThrowable(error);
                reenable.run();
            }
        };
        preTask.queue();
    }

    /**
     * 删除旧的补丁文件（只删除 {@code <保存路径>/<项目名>} 与 {@code <保存路径>/<项目名>-sources} 两个子树）。
     */
    private void exportDeleteFile(String savePath, boolean deleteToTrash, @NotNull ProgressIndicator indicator) {
        Path baseDir = Paths.get(savePath);
        Path[] targets = new Path[] {
                baseDir.resolve(project.getName()),
                baseDir.resolve(project.getName() + PatcherConstants.SOURCES_SUFFIX)
        };
        for (Path target : targets) {
            indicator.checkCanceled();
            if (!Files.exists(target)) {
                continue;
            }
            if (deleteToTrash) {
                // 移动到回收站
                if (!java.awt.Desktop.isDesktopSupported() || !java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH)) {
                    LOG.warn("Desktop MOVE_TO_TRASH not supported, falling back to direct delete");
                    deleteDirectly(target, indicator);
                } else {
                    boolean moved = java.awt.Desktop.getDesktop().moveToTrash(target.toFile());
                    if (!moved) {
                        LOG.warn(String.format("Failed to move to trash, falling back to direct delete: %s", target));
                        deleteDirectly(target, indicator);
                    }
                }
            } else {
                deleteDirectly(target, indicator);
            }
        }
    }

    /**
     * 直接删除目录树（不经过回收站）。
     */
    private void deleteDirectly(Path target, @NotNull ProgressIndicator indicator) {
        try {
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    indicator.checkCanceled();
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) {
                        throw exc;
                    }
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            LOG.error(String.format("Failed to delete old patch files: %s", target), ex);
            PatcherNotificationUtils.errorNotification(project,
                    PatcherBundle.message("patcher.error.delete.old.files", target, ex.getMessage()));
        }
    }

    /**
     * 导出源码文件。
     * <p>
     * 进度文案由调用方 preTask 统一设置，此处只更新 fraction。
     */
    private void exportSourcesFile(String savePath, @NotNull ProgressIndicator indicator) {
        Map<String, List<PatcherVirtualFile>> virtualFilesMap = PatcherProjectService.getInstance(project)
                .getVirtualFilesMap();
        final int total = Math.max(1, virtualFilesMap.values().stream().mapToInt(List::size).sum());
        int processed = 0;
        for (Map.Entry<String, List<PatcherVirtualFile>> entry : virtualFilesMap.entrySet()) {
            for (PatcherVirtualFile virtualFile : entry.getValue()) {
                indicator.checkCanceled();
                try {
                    exportOneSourceFile(virtualFile, savePath);
                } finally {
                    processed++;
                    indicator.setFraction((double) processed / total);
                }
            }
        }
    }

    private void exportOneSourceFile(PatcherVirtualFile virtualFile, String savePath) {
        Module module = virtualFile.getModule();
        if (module == null) {
            return;
        }
        VirtualFile vf = virtualFile.getVirtualFile();
        if (vf == null) {
            return;
        }
        // 在一个 ReadAction 内完成所有 VFS / 项目索引的快照访问以及文件内容读取，
        // 后续 IO 写入不再持有读锁，避免长时间阻碗 EDT/后台读锁。
        SourceSnapshot snapshot = ApplicationManager.getApplication().runReadAction(
                (Computable<SourceSnapshot>) () -> buildSourceSnapshot(module, vf));
        if (snapshot == null) {
            return;
        }
        Path pm = Paths.get(savePath, project.getName() + PatcherConstants.SOURCES_SUFFIX,
                snapshot.saveModulePath);
        Path targetFile = pm.resolve(snapshot.relativePath);
        try {
            Files.createDirectories(targetFile.getParent());
            Files.write(targetFile, snapshot.bytes);
        } catch (IOException ex) {
            LOG.error(String.format("Failed to export source file: %s", targetFile), ex);
            PatcherNotificationUtils.errorNotification(project,
                    PatcherBundle.message("patcher.error.export.source", targetFile, ex.getMessage()));
        }
    }

    /**
     * 在 ReadAction 内计算源码导出所需的快照（模块目录、相对路径、文件内容字节）。
     * 所有选中的文件都应导出，优先用 content root 作为基准计算相对路径。
     * 返回 null 表示应跳过（不在 content root / 读取失败）。
     */
    @Nullable
    private SourceSnapshot buildSourceSnapshot(@NotNull Module module, @NotNull VirtualFile vf) {
        // 跳过生成源码目录中的文件（如 MapStruct 生成的 Mapper），它们只需导出 class，不需要导出源码
        if (ProjectRootManager.getInstance(project).getFileIndex().isInGeneratedSources(vf)) {
            return null;
        }
        ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
        // 用模块的 content root 作为基准计算相对路径，保留 src/main/java、src/main/resources 等中间目录。
        VirtualFile moduleRoot = null;
        for (VirtualFile contentRoot : rootManager.getContentRoots()) {
            if (VfsUtilCore.isAncestor(contentRoot, vf, false)) {
                moduleRoot = contentRoot;
                break;
            }
        }
        if (moduleRoot == null) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = vf.contentsToByteArray();
        } catch (IOException ex) {
            LOG.error(String.format("Failed to read source file content: %s", vf.getPath()), ex);
            return null;
        }
        // 如果模块名字和项目名字相同，则不需要保存模块名字
        String saveModulePath = project.getName().equals(module.getName()) ? "" : module.getName();
        Path rel = Paths.get(moduleRoot.getPath()).relativize(Paths.get(vf.getPath()));
        return new SourceSnapshot(saveModulePath, rel.toString(), bytes);
    }

    /** 源码导出所需的快照，由 ReadAction 内构造。 */
    private record SourceSnapshot(String saveModulePath, String relativePath, byte[] bytes) {
    }

    /**
     * 导出 class 文件（含同名内部类与 Web 资源）。
     * <p>
     * 按 Module 聚合：同一 module 下的 {@code modelName / pm} 只在 ReadAction 内计算一次，
     * 然后复用给该 module 下的所有文件；每个文件再在独立 ReadAction 内构造自己的 plan。
     */
    private void exportClassFile(String savePath,
            ComboBox<String> moduleNameComboBox,
            ComboBox<PatcherModuleType> moduleTypeComboBox,
            @NotNull ProgressIndicator indicator) {
        final String selectedModuleName = moduleNameComboBox.getItem();
        if (selectedModuleName == null) {
            LOG.warn("No module selected, skip export");
            PatcherNotificationUtils.errorNotification(project,
                    PatcherBundle.message("patcher.error.no.module.selected"));
            return;
        }
        final PatcherModuleType selectedModuleType = moduleTypeComboBox.getItem();
        if (selectedModuleType == null) {
            return;
        }
        final String typeKey = selectedModuleType.getType();

        Map<String, List<PatcherVirtualFile>> virtualFilesMap = PatcherProjectService.getInstance(project)
                .getVirtualFilesMap();
        final int total = Math.max(1, virtualFilesMap.values().stream().mapToInt(List::size).sum());
        int processed = 0;
        indicator.setText(PatcherBundle.message("patcher.progress.export.classes"));
        for (Map.Entry<String, List<PatcherVirtualFile>> entry : virtualFilesMap.entrySet()) {
            List<PatcherVirtualFile> files = entry.getValue();
            if (files.isEmpty()) {
                continue;
            }
            // 同一 module 下的 pm 总是相同，一次 ReadAction 预算，避免逐文件重复计算。
            Module module = files.get(0).getModule();
            Path pm = module == null ? null
                    : ApplicationManager.getApplication().runReadAction(
                            (Computable<Path>) () -> resolveModulePm(module, savePath, typeKey, selectedModuleName));
            for (PatcherVirtualFile virtualFile : files) {
                indicator.checkCanceled();
                try {
                    exportOneClassFile(virtualFile, pm, typeKey);
                } finally {
                    processed++;
                    indicator.setFraction((double) processed / total);
                }
            }
        }
    }

    /**
     * ReadAction 内预算一个 module 对应的
     * {@code pm = savePath/<project>/<modelName>/<typeKey>}。
     */
    @NotNull
    private Path resolveModulePm(@NotNull Module module,
            @NotNull String savePath,
            @Nullable String typeKey,
            @Nullable String selectedModuleName) {
        String safeTypeKey = typeKey == null ? "" : typeKey;
        String modelName = resolveModelName(module, safeTypeKey, selectedModuleName);
        return Paths.get(savePath, project.getName(), modelName, safeTypeKey);
    }

    /**
     * 导出单个 VirtualFile 对应的 class 产物（含同名内部类）或 Web 资源。
     * <p>
     * 实现方式：先在 ReadAction 内统一构造 {@link ClassExportPlan}，将所有
     * VFS / Module 访问集中在读锁下完成；随后在普通后台线程里做纯 IO，避免
     * 长时间持有读锁。
     */
    private void exportOneClassFile(PatcherVirtualFile virtualFile,
            @Nullable Path pm,
            @Nullable String typeKey) {
        if (pm == null) {
            return;
        }
        Module module = virtualFile.getModule();
        if (module == null) {
            return;
        }
        VirtualFile vf = virtualFile.getVirtualFile();
        if (vf == null) {
            return;
        }
        ClassExportPlan plan = ApplicationManager.getApplication().runReadAction(
                (Computable<ClassExportPlan>) () -> buildClassExportPlan(module, vf, pm, typeKey));
        if (plan == null) {
            return;
        }
        switch (plan.mode()) {
            case SOURCE_ROOT -> copyMatchingClassFiles(plan);
            case WEBAPP -> handleWebResource(plan);
            case NONE -> {
                // 命中 source root 但无法构造路径，已忽略；也不再 fallback 到 webapp。
            }
        }
    }

    /**
     * 在 ReadAction 内构造补丁导出所需的路径快照。
     * <p>
     * 补丁导出原则：统一从编译输出（target）目录查找文件。
     * <ul>
     *   <li>Java 文件 → 查找对应的 .class 文件（含内部类）</li>
     *   <li>非 Java 文件 → 查找同名文件（资源文件会被编译器复制到输出目录）</li>
     *   <li>webapp 资源 → 按 webapp 相对路径直接复制源文件</li>
     * </ul>
     * 如果文件在编译输出目录中不存在则跳过（不导出）。
     */
    @Nullable
    private ClassExportPlan buildClassExportPlan(@NotNull Module module,
            @NotNull VirtualFile vf,
            @NotNull Path pm,
            @Nullable String typeKey) {
        String safeTypeKey = typeKey == null ? "" : typeKey;
        String fileName = vf.getName();
        boolean isJavaFile = fileName.endsWith(PatcherConstants.JAVA_EXT);

        CompilerModuleExtension compilerExt = CompilerModuleExtension.getInstance(module);
        VirtualFile compilerOutputPath = compilerExt == null ? null : compilerExt.getCompilerOutputPath();

        // 尝试在 source root 下查找，从编译输出目录导出
        if (compilerOutputPath != null) {
            for (VirtualFile sourceRoot : ModuleRootManager.getInstance(module).getSourceRoots()) {
                if (!VfsUtilCore.isAncestor(sourceRoot, vf, false)) {
                    continue;
                }
                VirtualFile parentVf = vf.getParent();
                if (parentVf == null || (!VfsUtilCore.isAncestor(sourceRoot, parentVf, false)
                        && !sourceRoot.equals(parentVf))) {
                    continue;
                }
                Path sourceRootPath = Paths.get(sourceRoot.getPath());
                Path compilerOutputDir = Paths.get(compilerOutputPath.getPath());
                // 文件父目录相对于 source root 的路径
                Path relParent = sourceRoot.equals(parentVf) ? Paths.get("")
                        : sourceRootPath.relativize(Paths.get(parentVf.getPath()));
                Path searchPath = relParent.toString().isEmpty() ? compilerOutputDir
                        : compilerOutputDir.resolve(relParent);

                // 目标目录：相对于 classpath 的路径映射到 pm 下
                Path classpath = safeTypeKey.isEmpty() ? compilerOutputDir : compilerOutputDir.getParent();
                if (classpath == null) {
                    continue;
                }
                Path targetDir = classpath.equals(searchPath) ? pm
                        : pm.resolve(classpath.relativize(searchPath).toString());

                if (isJavaFile) {
                    // Java 文件：匹配 Foo.class 及内部类 Foo$Inner.class
                    String stem = fileName.substring(0, fileName.length() - PatcherConstants.JAVA_EXT.length());
                    String exactClass = stem + PatcherConstants.CLASS_EXT;
                    String innerPrefix = stem + "$";
                    return ClassExportPlan.source(targetDir, searchPath, exactClass, innerPrefix);
                } else {
                    // 非 Java 文件：在编译输出中按原文件名查找
                    return ClassExportPlan.source(targetDir, searchPath, fileName, null);
                }
            }
        }

        // 不在 source root 下的文件：尝试 webapp 资源路径
        String lowerPath = StringUtil.toLowerCase(vf.getPath());
        int webappIdx = lowerPath.indexOf(PatcherConstants.WEBAPP_SEGMENT);
        if (webappIdx >= 0) {
            String relative = vf.getPath().substring(webappIdx + PatcherConstants.WEBAPP_SEGMENT.length());
            Path parent = pm.getParent();
            if (parent == null) {
                return null;
            }
            Path webTargetFile = parent.resolve(relative);
            byte[] webBytes;
            try {
                webBytes = vf.contentsToByteArray();
            } catch (IOException ex) {
                LOG.error(String.format("Failed to read web resource content: %s", vf.getPath()), ex);
                return null;
            }
            return ClassExportPlan.webapp(webTargetFile, webBytes);
        }

        // 不在 source root 也不在 webapp 下，编译输出中无法找到对应文件，跳过
        return null;
    }

    /**
     * 纯 IO：在编译输出目录中匹配并拷贝 class 文件（含同名内部类）。
     */
    private void copyMatchingClassFiles(@NotNull ClassExportPlan plan) {
        Path targetDir = plan.targetDir();
        Path searchPath = plan.searchPath();
        if (targetDir == null || searchPath == null) {
            return;
        }
        try {
            Files.createDirectories(targetDir);
        } catch (IOException ex) {
            LOG.error(String.format("Failed to create directory: %s", targetDir), ex);
            PatcherNotificationUtils.errorNotification(project,
                    PatcherBundle.message("patcher.error.create.directory", targetDir, ex.getMessage()));
            return;
        }
        if (!Files.exists(searchPath)) {
            return;
        }
        String exactClass = plan.exactClass();
        String innerPrefix = plan.innerPrefix();
        try (Stream<Path> stream = Files.walk(searchPath)) {
            stream.filter(Files::isRegularFile).filter(p -> {
                String n = p.getFileName().toString();
                if (n.equals(exactClass)) {
                    return true;
                }
                // innerPrefix 为 null 表示资源文件，不需要匹配内部类
                return innerPrefix != null && n.startsWith(innerPrefix) && n.endsWith(PatcherConstants.CLASS_EXT);
            }).forEach(currentFile -> {
                try {
                    Path file = targetDir.resolve(currentFile.getFileName().toString());
                    Files.copy(currentFile, file, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ex) {
                    LOG.error(String.format("Failed to copy class file: %s", currentFile), ex);
                    PatcherNotificationUtils.errorNotification(project,
                            PatcherBundle.message("patcher.error.copy.class.file", currentFile, ex.getMessage()));
                }
            });
        } catch (IOException ex) {
            LOG.error(String.format("Failed to walk compile output: %s", searchPath), ex);
            PatcherNotificationUtils.errorNotification(project,
                    PatcherBundle.message("patcher.error.walk.compile.output", searchPath, ex.getMessage()));
        }
    }

    /**
     * 纯 IO：将 webapp 资源拷贝到目标路径（使用 ReadAction 内读取的字节快照）。
     */
    private void handleWebResource(@NotNull ClassExportPlan plan) {
        Path targetFile = plan.webTargetFile();
        byte[] bytes = plan.webBytes();
        if (targetFile == null || bytes == null) {
            return;
        }
        try {
            Files.createDirectories(targetFile.getParent());
            Files.write(targetFile, bytes);
        } catch (IOException ex) {
            LOG.error(String.format("Failed to copy web resource: %s", targetFile), ex);
            PatcherNotificationUtils.errorNotification(project,
                    PatcherBundle.message("patcher.error.copy.web.resource", targetFile, ex.getMessage()));
        }
    }

    /** class 文件导出的分支模式。 */
    private enum ExportMode {
        /** 忽略（位于 source root 下但无法解析出路径）。 */
        NONE,
        /** source root 对应的编译输出拷贝分支。 */
        SOURCE_ROOT,
        /** webapp 资源拷贝分支。 */
        WEBAPP
    }

    /**
     * class 文件导出所需的路径快照；根据 {@link #mode()} 使用对应字段。
     * webapp 分支额外携带 {@link #webBytes()}，避免在 ReadAction 外再打开流。
     */
    private record ClassExportPlan(@NotNull ExportMode mode,
            @Nullable Path targetDir,
            @Nullable Path searchPath,
            @Nullable String exactClass,
            @Nullable String innerPrefix,
            @Nullable Path webTargetFile,
            @Nullable byte[] webBytes) {
        static ClassExportPlan empty() {
            return new ClassExportPlan(ExportMode.NONE, null, null, null, null, null, null);
        }

        static ClassExportPlan source(Path targetDir, Path searchPath, String exact, String inner) {
            return new ClassExportPlan(ExportMode.SOURCE_ROOT, targetDir, searchPath, exact, inner, null, null);
        }

        static ClassExportPlan webapp(Path webTargetFile, byte[] webBytes) {
            return new ClassExportPlan(ExportMode.WEBAPP, null, null, null, null, webTargetFile, webBytes);
        }
    }

    /**
     * 解析 class 文件输出路径中的模块名部分。
     */
    @NotNull
    private String resolveModelName(@NotNull Module module,
            @Nullable String typeKey,
            @Nullable String selectedModuleName) {
        if (!PatcherConstants.BOOT_INF.equals(typeKey) && !PatcherConstants.WEB_INF.equals(typeKey)) {
            return "";
        }
        if (PatcherConstants.MULTI_MODULE_SENTINEL.equals(selectedModuleName)) {
            return module.getName();
        }
        VirtualFile moduleDir = ProjectUtil.guessModuleDir(module);
        String basePath = project.getBasePath();
        if (moduleDir != null && basePath != null) {
            VirtualFile parent = moduleDir.getParent();
            if (parent != null && isSamePath(parent.getPath(), basePath)) {
                return "";
            }
        }
        if (project.getName().equals(selectedModuleName)) {
            return "";
        }
        return selectedModuleName == null ? "" : selectedModuleName;
    }

    /**
     * Windows 文件系统大小写不敏感，优先走 {@link Files#isSameFile(Path, Path)} 做真实路径比较，
     * IO 异常时 fallback 为规范化路径字符串比较。
     */
    private static boolean isSamePath(String a, String b) {
        try {
            Path pa = Paths.get(a);
            Path pb = Paths.get(b);
            if (Files.exists(pa) && Files.exists(pb)) {
                return Files.isSameFile(pa, pb);
            }
            return pa.toAbsolutePath().normalize().equals(pb.toAbsolutePath().normalize());
        } catch (IOException ex) {
            return Paths.get(a).equals(Paths.get(b));
        }
    }

    /**
     * 在 preTask 完成后调度 class 导出：EDT 中触发 {@link CompilerManager#make}，
     * 编译成功后开一个 Backgroundable 任务完成 class 拷贝。
     * <p>
     * 抽出此方法仅为降低嵌套级别，外部行为与原写法一致。
     */
    private void scheduleClassExport(String savePath,
            ComboBox<String> moduleNameComboBox,
            ComboBox<PatcherModuleType> moduleTypeComboBox,
            Runnable reenable) {
        ApplicationManager.getApplication().invokeLater(() -> CompilerManager.getInstance(project)
                .make((aborted, errors, warnings, compileContext) -> {
                    if (aborted || errors != 0) {
                        reenable.run();
                        return;
                    }
                    new Task.Backgroundable(project, PatcherBundle.message("patcher.name"), true) {
                        @Override
                        public void run(@NotNull ProgressIndicator indicator) {
                            indicator.setIndeterminate(false);
                            exportClassFile(savePath, moduleNameComboBox, moduleTypeComboBox, indicator);
                            indicator.setFraction(1.0);
                        }

                        @Override
                        public void onCancel() {
                            PatcherNotificationUtils.warningNotification(project,
                                    PatcherBundle.message("patcher.notification.export.cancelled"));
                        }

                        @Override
                        public void onSuccess() {
                            PatcherNotificationUtils.successNotification(project, savePath);
                        }

                        @Override
                        public void onFinished() {
                            reenable.run();
                        }
                    }.queue();
                }));
    }

    @Override
    public void dispose() {
        // 插件动态卸载时释放资源
    }
}
