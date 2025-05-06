# <img src="https://raw.githubusercontent.com/Liang-Dongxing/patcher/master/src/main/resources/META-INF/pluginIcon.svg" alt="Patcher Logo" width="30" height="30"> Patcher

**Other language versions: [English](README_en.md)**

## 项目简介

Patcher 是一个 IntelliJ IDEA 插件，用于高效地提取和打包 Java 应用程序的补丁文件。主要功能包括：

- 从现有 Java 应用程序中提取修改内容
- 将修改打包成可执行的补丁文件
- 支持多种模块类型和文件格式
- 简化补丁创建和分发流程

## 功能特性

✔ 一键创建补丁文件  
✔ 支持多文件/文件夹批量处理  
✔ 自定义模块类型和名称  
✔ 灵活的保存路径设置  

## 安装指南

### 通过 JetBrains 插件市场安装

1. 打开 IntelliJ IDEA
2. 进入 `文件` → `设置` → `插件`
3. 在 Marketplace 中搜索 "Patcher"
4. 点击 `安装` 按钮安装插件
5. 重启 IDE 完成安装

[立即安装](https://plugins.jetbrains.com/plugin/12604-patcher)

## 使用说明

1. 在项目窗口或 Git 窗口中：
   - 右键点击目标文件/文件夹
   - 选择 `创建补丁` 选项

2. 在打开的 ToolWindow 中：
   - 设置模块类型
   - 输入模块名称
   - 选择保存路径
   - 点击 `导出` 按钮生成补丁

## 许可证

本项目采用 [MIT 许可证](LICENSE)。

版权所有 (c) 2025 作者名字

除非符合 MIT 许可证的要求，本项目中的所有代码都受版权保护。详细信息请参阅 LICENSE 文件。