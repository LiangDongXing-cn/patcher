package com.bit.patcher;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * 工具窗口工厂类：负责挂载 Patcher ToolWindow 的 UI 内容。
 *
 * @author Liang
 */
public class PatcherFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        PatcherProjectService service = PatcherProjectService.getInstance(project);
        // 先清状态，再创建/挂载 UI，避免上次项目残留数据闪现
        service.clearAll();

        PatcherToolWindow patcherToolWindow = new PatcherToolWindow(project, toolWindow);
        service.setToolWindow(patcherToolWindow);
        patcherToolWindow.initialize();

        Content content = ContentFactory.getInstance().createContent(
                patcherToolWindow.getContent(),
                PatcherBundle.message("patcher.name"),
                false);
        toolWindow.getContentManager().addContent(content);
    }
}
