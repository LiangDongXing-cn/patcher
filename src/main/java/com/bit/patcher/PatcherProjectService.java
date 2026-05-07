package com.bit.patcher;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.tree.TreeUtil;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 项目级服务：承载每个 Project 独立的补丁文件树、模块分组以及 ToolWindow 引用。
 * 取代原来的全局静态 PVFUtils / PatcherUtils.patcherToolWindow。
 * <p>
 * 线程语义：{@link #getVirtualFilesMap()}（返回不可变视图）及 {@link #setVirtualFilesMapValue}
 * /
 * {@link #removeVirtualFilesMapValue} / {@link #resetWith(List)} 支持并发调用；涉及
 * Swing 组件的
 * {@link #setPatcherFileTree()}、{@link #removeAllChildren()}、{@link #clearAll()}
 * 内部自动
 * 切回 EDT。
 *
 * @author Liang
 */
@Service(Service.Level.PROJECT)
public final class PatcherProjectService {

    private final Project project;
    private final Tree saveFilesTree = new Tree();
    private final Map<String, CopyOnWriteArrayList<PatcherVirtualFile>> virtualFilesMap = new ConcurrentHashMap<>();
    private volatile PatcherToolWindow toolWindow;

    public PatcherProjectService(Project project) {
        this.project = project;
        this.saveFilesTree.setRootVisible(false);
    }

    public static PatcherProjectService getInstance(Project project) {
        return project.getService(PatcherProjectService.class);
    }

    public Project getProject() {
        return project;
    }

    public Tree getSaveFilesTree() {
        return saveFilesTree;
    }

    /**
     * 返回不可变视图，避免外部绕过服务封装直接修改内部状态。
     * 外部写入均走 {@link #setVirtualFilesMapValue} / {@link #removeVirtualFilesMapValue}
     * /
     * {@link #resetWith(List)} / {@link #clearAll()}。
     */
    public Map<String, List<PatcherVirtualFile>> getVirtualFilesMap() {
        return Collections.unmodifiableMap(virtualFilesMap);
    }

    public PatcherToolWindow getToolWindow() {
        return toolWindow;
    }

    public void setToolWindow(PatcherToolWindow toolWindow) {
        this.toolWindow = toolWindow;
    }

    /**
     * 将一个 PatcherVirtualFile 加入到其所属 Module 的列表（原子去重）。
     */
    public void setVirtualFilesMapValue(PatcherVirtualFile virtualFile) {
        CopyOnWriteArrayList<PatcherVirtualFile> list = virtualFilesMap
                .computeIfAbsent(virtualFile.getModuleName(), k -> new CopyOnWriteArrayList<>());
        // CopyOnWriteArrayList#addIfAbsent 保证 contains+add 的原子性
        list.addIfAbsent(virtualFile);
    }

    /**
     * 从列表中移除指定文件；列表变空时移除该键。
     */
    public void removeVirtualFilesMapValue(PatcherVirtualFile patcherVirtualFile) {
        virtualFilesMap.compute(patcherVirtualFile.getModuleName(), (k, list) -> {
            if (list == null) {
                return null;
            }
            list.remove(patcherVirtualFile);
            return list.isEmpty() ? null : list;
        });
    }

    /**
     * 原子地用一批 {@link PatcherVirtualFile} 重置当前状态，并刷新树。
     * 适用于 PatcherAction 等批量填充场景，避免调用方又 clear 又 add 带来的竞态。
     */
    public void resetWith(List<PatcherVirtualFile> files) {
        virtualFilesMap.clear();
        if (files != null) {
            for (PatcherVirtualFile pvf : files) {
                setVirtualFilesMapValue(pvf);
            }
        }
        setPatcherFileTree();
    }

    /**
     * 依据 virtualFilesMap 重建树模型，并更新模块下拉框。
     * <p>
     * 内部通过 {@code invokeLater} 切回 EDT，允许从任意线程调用。
     */
    public void setPatcherFileTree() {
        ApplicationManager.getApplication().invokeLater(this::doSetPatcherFileTree);
    }

    private void doSetPatcherFileTree() {
        if (toolWindow != null) {
            if (virtualFilesMap.size() > 1) {
                toolWindow.getModuleNameComboBox()
                        .setItem(PatcherConstants.MULTI_MODULE_SENTINEL);
            } else {
                for (String s : virtualFilesMap.keySet()) {
                    toolWindow.getModuleNameComboBox().setItem(s);
                }
            }
        }
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        virtualFilesMap.forEach((key, value) -> {
            DefaultMutableTreeNode moduleNode = new DefaultMutableTreeNode(key);
            value.forEach(virtualFile -> moduleNode.add(new DefaultMutableTreeNode(virtualFile)));
            root.add(moduleNode);
        });
        DefaultTreeModel model = new DefaultTreeModel(root);
        saveFilesTree.setModel(model);
        TreeUtil.expandAll(saveFilesTree);
    }

    /**
     * 清空树显示（不清 map）。
     */
    public void removeAllChildren() {
        ApplicationManager.getApplication().invokeLater(() -> {
            ((DefaultMutableTreeNode) saveFilesTree.getModel().getRoot()).removeAllChildren();
            ((DefaultTreeModel) saveFilesTree.getModel()).reload();
        });
    }

    /**
     * 同时清空 map 与树显示。
     */
    public void clearAll() {
        virtualFilesMap.clear();
        removeAllChildren();
    }
}
