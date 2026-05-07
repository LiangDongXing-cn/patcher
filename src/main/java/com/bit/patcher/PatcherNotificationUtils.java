package com.bit.patcher;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.awt.Desktop;
import java.io.File;

/**
 * 通知工具类：所有通知均绑定 Project，避免跨项目串扰。
 */
public final class PatcherNotificationUtils {

    private static final Logger LOG = Logger.getInstance(PatcherNotificationUtils.class);

    private PatcherNotificationUtils() {
    }

    /**
     * 成功通知
     */
    public static void successNotification(@NotNull Project project, String openPath) {
        Notification notification = new Notification(PatcherUtils.PATCHER_ID,
                PatcherBundle.message("patcher.name"),
                PatcherBundle.message("patcher.notification.content"),
                NotificationType.INFORMATION);
        NotificationAction action = new NotificationAction(PatcherBundle.message("patcher.notification.open")) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                openInFileExplorer(project, openPath);
            }
        };
        notification.addAction(action);
        Notifications.Bus.notify(notification, project);
    }

    /**
     * 警告通知
     */
    public static void warningNotification(@NotNull Project project, String message) {
        LOG.warn(message);
        Notification notification = new Notification(PatcherUtils.PATCHER_ID,
                PatcherBundle.message("patcher.name"),
                message,
                NotificationType.WARNING);
        Notifications.Bus.notify(notification, project);
    }

    /**
     * 错误通知。
     * <p>
     * 仅负责通知展示，日志由调用方使用 {@code LOG.error(msg, throwable)} 输出，避免：
     * 1) 与调用方重复写一遍 error；2) IDEA Error 面板把插件标记为抛异常。
     */
    public static void errorNotification(@NotNull Project project, String content) {
        LOG.warn(content);
        Notification notification = new Notification(PatcherUtils.PATCHER_ID,
                PatcherBundle.message("patcher.name"),
                content,
                NotificationType.ERROR);
        Notifications.Bus.notify(notification, project);
    }

    /**
     * 安全地在文件资源管理器中打开目录：
     * - 无桌面环境 / 不支持 OPEN 时，给出警告通知而不是抛异常
     * - 捕获所有 Throwable（含 UnsupportedOperationException / HeadlessException）
     */
    private static void openInFileExplorer(@NotNull Project project, String openPath) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            warningNotification(project, PatcherBundle.message("patcher.notification.open.not.supported"));
            return;
        }
        try {
            Desktop.getDesktop().open(new File(openPath));
        } catch (Throwable t) {
            LOG.warn(String.format("Failed to open path: %s", openPath), t);
            warningNotification(project, PatcherBundle.message("patcher.notification.open.file.failed", openPath));
        }
    }
}
