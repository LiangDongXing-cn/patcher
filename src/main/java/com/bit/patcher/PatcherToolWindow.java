package com.bit.patcher;

import com.intellij.icons.AllIcons;
import com.intellij.ide.util.TreeFileChooser;
import com.intellij.ide.util.TreeFileChooserFactory;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.psi.PsiFile;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.tree.TreeUtil;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.*;

/**
 * 工具窗口类：维护补丁导出的交互面板。
 *
 * @author Liang
 */
@Getter
public class PatcherToolWindow {

    private JPanel patcherWindowContent;
    private JPanel moduleTypePanel;
    private ComboBox<PatcherModuleType> moduleTypeComboBox;
    private JPanel moduleNamePanel;
    private ComboBox<String> moduleNameComboBox;
    private JPanel savePathPanel;
    private TextFieldWithBrowseButton savePathTextFieldWithBrowseButton;
    private JPanel saveFilesPanel;
    private JPanel exportOptionsPanel;
    private JPanel cleanupOptionsPanel;
    private JBCheckBox exportTheSourceCodeJbCheckBox;
    private JBCheckBox deleteOldPatcherFilesJbCheckBox;
    private JBCheckBox deleteToTrashJbCheckBox;
    private JLabel cleanupHintLabel;
    private JButton exportButton;
    private final Project project;
    private final ToolWindow toolWindow;

    public PatcherToolWindow(Project project, ToolWindow toolWindow) {
        this.project = project;
        this.toolWindow = toolWindow;
        applyI18nTitles();
        initCheckboxLinkage();
        initializationSaveFilesPanel();
    }

    /**
     * 通过 DynamicBundle 设置面板标题和组件文本，确保跟随 IDE 语言设置。
     */
    private void applyI18nTitles() {
        moduleTypePanel.setBorder(IdeBorderFactory.createTitledBorder(PatcherBundle.message("patcher.module.type"), false));
        moduleNamePanel.setBorder(IdeBorderFactory.createTitledBorder(PatcherBundle.message("patcher.module.name"), false));
        savePathPanel.setBorder(IdeBorderFactory.createTitledBorder(PatcherBundle.message("patcher.save.path"), false));
        saveFilesPanel.setBorder(IdeBorderFactory.createTitledBorder(PatcherBundle.message("patcher.save.files"), false));
        exportOptionsPanel.setBorder(IdeBorderFactory.createTitledBorder(PatcherBundle.message("patcher.panel.export.options"), false));
        cleanupOptionsPanel.setBorder(IdeBorderFactory.createTitledBorder(PatcherBundle.message("patcher.panel.cleanup.options"), false));
        exportTheSourceCodeJbCheckBox.setText(PatcherBundle.message("patcher.checkbox.export.sources"));
        deleteOldPatcherFilesJbCheckBox.setText(PatcherBundle.message("patcher.checkbox.delete.old"));
        deleteToTrashJbCheckBox.setText(PatcherBundle.message("patcher.checkbox.delete.to.trash"));
        cleanupHintLabel.setText(PatcherBundle.message("patcher.cleanup.hint"));
        cleanupHintLabel.setForeground(com.intellij.util.ui.JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        exportButton.setText(PatcherBundle.message("patcher.button.export"));
    }

    /**
     * 初始化复选框联动："删除旧补丁" 勾选时 "删除到回收站" 可操作；取消勾选时自动取消并禁用。
     */
    private void initCheckboxLinkage() {
        // 初始状态同步
        deleteToTrashJbCheckBox.setEnabled(deleteOldPatcherFilesJbCheckBox.isSelected());
        // 监听变化
        deleteOldPatcherFilesJbCheckBox.addActionListener(e -> {
            boolean selected = deleteOldPatcherFilesJbCheckBox.isSelected();
            deleteToTrashJbCheckBox.setEnabled(selected);
            if (!selected) {
                deleteToTrashJbCheckBox.setSelected(false);
            } else {
                deleteToTrashJbCheckBox.setSelected(true);
            }
        });
    }

    /**
     * 创建自定义的用户界面组件
     * <p>
     * 由 IntelliJ GUI Designer 生成的 .form 绑定调用，请勿删除。
     */
    public void createUIComponents() {

    }

    public JComponent getContent() {
        return patcherWindowContent;
    }

    /**
     * 初始化工具窗口组件（模块下拉框、保存路径、导出按钮等）
     */
    public void initialize() {
        // 幂等性：重进入不积累重复项
        this.moduleNameComboBox.removeAllItems();
        this.moduleTypeComboBox.removeAllItems();
        PatcherUtils.setModuleNameComboBox(project, this.moduleNameComboBox);
        PatcherUtils.setLibraryComboBox(project, this.moduleTypeComboBox);
        PatcherUtils.setDefaultSavePath(this.savePathTextFieldWithBrowseButton);
        PatcherUtils.setBrowseFolderListener(project, this.savePathTextFieldWithBrowseButton);
        ExportService.getInstance(project).exportFile(
                this.savePathTextFieldWithBrowseButton,
                this.moduleNameComboBox,
                this.moduleTypeComboBox,
                this.exportTheSourceCodeJbCheckBox,
                this.deleteOldPatcherFilesJbCheckBox,
                this.deleteToTrashJbCheckBox,
                this.exportButton);
    }

    /**
     * 初始化保存文件的面板
     */
    private void initializationSaveFilesPanel() {
        PatcherProjectService service = PatcherProjectService.getInstance(project);
        // 创建 ToolbarDecorator，并设置添加、编辑和删除操作
        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(service.getSaveFilesTree());
        decorator.setAddAction(anActionButton -> {
            // 处理添加操作
            TreeFileChooser treeFileChooser = TreeFileChooserFactory.getInstance(project)
                    .createFileChooser(PatcherBundle.message("patcher.value.select.patch.file"), null, null, null);
            treeFileChooser.showDialog();
            // 获取选中的文件
            PsiFile selectedFile = treeFileChooser.getSelectedFile();
            if (selectedFile != null) {
                // 获取选中文件的模块
                Module moduleForFile = ModuleUtil.findModuleForFile(selectedFile.getVirtualFile(), project);
                if (moduleForFile == null) {
                    return;
                }
                // 创建 PatcherVirtualFile 对象
                PatcherVirtualFile patcherVirtualFile = PatcherVirtualFile.builder()
                        .virtualFile(selectedFile.getVirtualFile())
                        .module(moduleForFile)
                        .build();
                // 添加到 map 中
                service.setVirtualFilesMapValue(patcherVirtualFile);
                service.setPatcherFileTree();
            }
        });
        decorator.setEditAction(anActionButton -> {
            // 获取选中的节点
            Object selected = service.getSaveFilesTree().getLastSelectedPathComponent();
            if (!(selected instanceof DefaultMutableTreeNode defaultMutableTreeNode)) {
                return;
            }
            if (defaultMutableTreeNode.getUserObject() instanceof PatcherVirtualFile patcherVirtualFile) {
                // 打开并激活编辑器
                if (patcherVirtualFile.getVirtualFile() == null) {
                    return;
                }
                FileEditorManager fileEditorManager = FileEditorManager.getInstance(project);
                fileEditorManager.openFile(patcherVirtualFile.getVirtualFile(), true);
            }
        });
        decorator.setRemoveAction(anActionButton -> {
            // 获取选中的节点
            Object selected = service.getSaveFilesTree().getLastSelectedPathComponent();
            if (!(selected instanceof DefaultMutableTreeNode defaultMutableTreeNode)) {
                return;
            }
            if (defaultMutableTreeNode.getUserObject() instanceof PatcherVirtualFile patcherVirtualFile) {
                // 从 map 中移除
                service.removeVirtualFilesMapValue(patcherVirtualFile);
                service.setPatcherFileTree();
            }
        });
        // 展开全部按钮
        AnAction expandAllAction = new AnAction(PatcherBundle.message("patcher.action.expand.all"), PatcherBundle.message("patcher.action.expand.all"), AllIcons.Actions.Expandall) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                TreeUtil.expandAll(service.getSaveFilesTree());
            }
        };
        // 收起全部按钮
        AnAction collapseAllAction = new AnAction(PatcherBundle.message("patcher.action.collapse.all"), PatcherBundle.message("patcher.action.collapse.all"), AllIcons.Actions.Collapseall) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                TreeUtil.collapseAll(service.getSaveFilesTree(), 0);
            }
        };
        decorator.addExtraActions(expandAllAction, collapseAllAction);

        saveFilesPanel.add(decorator.createPanel(), BorderLayout.CENTER);
    }
}
