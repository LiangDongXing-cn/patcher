package com.bit.patcher;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * 项目级持久化配置：保存用户修改的补丁导出路径等设置，重启 IDE 后自动恢复。
 *
 * @author Liang
 */
@Service(Service.Level.PROJECT)
@State(name = "PatcherSettings", storages = @Storage("patcher_settings.xml"))
public final class PatcherSettings implements PersistentStateComponent<PatcherSettings.State> {

    private State myState = new State();

    public static PatcherSettings getInstance(Project project) {
        return project.getService(PatcherSettings.class);
    }

    @Override
    public @Nullable State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.myState = state;
    }

    /**
     * 获取保存路径；如果未配置过则返回默认桌面路径。
     */
    public String getSavePath() {
        if (StringUtil.isEmptyOrSpaces(myState.savePath)) {
            return getDefaultSavePath();
        }
        return myState.savePath;
    }

    /**
     * 保存用户选择的路径。
     */
    public void setSavePath(String path) {
        myState.savePath = path;
    }

    private static String getDefaultSavePath() {
        return System.getProperty("user.home") + File.separator
                + PatcherConstants.DEFAULT_SAVE_PATH_SUFFIX;
    }

    public static class State {
        public String savePath;
    }
}
