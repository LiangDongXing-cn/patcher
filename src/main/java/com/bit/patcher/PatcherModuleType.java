package com.bit.patcher;

import lombok.Builder;
import lombok.Getter;

/**
 * 模块类型下拉项：{@code name} 用于 UI 展示，{@code type} 用于路径辨识
 * (BOOT-INF / WEB-INF / 空串)。
 *
 * @author Liang
 */
@Getter
@Builder
public class PatcherModuleType {

    private final String name;
    private final String type;

    @Override
    public String toString() {
        return name;
    }
}
