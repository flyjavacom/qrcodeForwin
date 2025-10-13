package com.example.qrcode;

import com.example.qrcode.demo.FileTransferApp;

import javax.swing.*;

//TIP 要<b>运行</b>代码，请按 <shortcut actionId="Run"/> 或
// 点击装订区域中的 <icon src="AllIcons.Actions.Execute"/> 图标。
public class Main {
    public static void main(String[] args) {
        //TIP 当文本光标位于高亮显示的文本处时按 <shortcut actionId="ShowIntentionActions"/>
        // 查看 IntelliJ IDEA 建议如何修正。
        System.out.printf("Hello and welcome!");

        SwingUtilities.invokeLater(() -> {
            FileTransferApp app = new FileTransferApp();
            app.setVisible(true);
        });
    }
}