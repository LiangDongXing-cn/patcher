package com.bit.patcher;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.vfs.VirtualFile;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * 选中文件的轻量 VO。
 * 仅持有 {@link VirtualFile} 与 {@link Module} 两个来源字段，path/name/moduleName
 * 均动态派生以避免"双真相"。
 *
 * @author Liang
 */
@Getter
@Builder
@EqualsAndHashCode(of = "virtualFile")
public final class PatcherVirtualFile {

    private final VirtualFile virtualFile;
    private final Module module;

    public String getPath() {
        return virtualFile != null ? virtualFile.getPath() : "";
    }

    public String getName() {
        return virtualFile != null ? virtualFile.getName() : "";
    }

    public String getModuleName() {
        return module != null ? module.getName() : "";
    }

    @Override
    public String toString() {
        return getName();
    }
}
