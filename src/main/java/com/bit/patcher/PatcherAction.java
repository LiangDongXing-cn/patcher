package com.bit.patcher;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 右键菜单中的"创建补丁"Action：展示 ToolWindow 并将选中文件按模块分组添加。
 * <p>
 * update 在 BGT 执行；actionPerformed 先在 EDT 弹出 ToolWindow，
 * 然后将递归扫描放到后台线程 + ReadAction，避免大目录输入时冻结 UI。
 *
 * @author Liang
 */
public class PatcherAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile[] virtualFiles = getSelectedFiles(e);
        boolean visible = e.getProject() != null && virtualFiles != null && virtualFiles.length > 0;
        e.getPresentation().setEnabledAndVisible(visible);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent anActionEvent) {
        Project project = anActionEvent.getProject();
        if (project == null) {
            return;
        }
        ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
        ToolWindow toolWindow = toolWindowManager.getToolWindow(PatcherUtils.PATCHER_ID);
        if (toolWindow == null) {
            return;
        }
        if (!toolWindow.isVisible()) {
            toolWindow.show(null);
        }

        VirtualFile[] virtualFiles = getSelectedFiles(anActionEvent);
        if (virtualFiles == null || virtualFiles.length == 0) {
            return;
        }

        // 大目录递归扫描放后台 + ReadAction，避免阻塞 EDT。
        new Task.Backgroundable(project, PatcherBundle.message("patcher.name"), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                List<PatcherVirtualFile> collected = new ArrayList<>();
                for (VirtualFile virtualFile : virtualFiles) {
                    indicator.checkCanceled();
                    ApplicationManager.getApplication().runReadAction((Runnable) () -> collected.addAll(PatcherUtils.getExportFile(project, virtualFile)));
                }
                PatcherProjectService.getInstance(project).resetWith(collected);
            }
        }.queue();
    }

    /**
     * 统一从事件中拉取选中的 VirtualFile 数组，供 update / actionPerformed 共用。
     */
    @Nullable
    private static VirtualFile[] getSelectedFiles(@NotNull AnActionEvent e) {
        return e.getData(PlatformDataKeys.VIRTUAL_FILE_ARRAY);
    }
}
