package com.example.qrcode.demo;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Creates unique work folders beside the file or image folder being processed. */
public final class TransferOutputPaths {
    private TransferOutputPaths() { }

    public static Path besideFile(Path inputFile, String timestamp) {
        Path absolute = inputFile.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || absolute.getFileName() == null) {
            throw new IllegalArgumentException("无法为所选文件确定同级输出目录：" + inputFile);
        }
        String name = absolute.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String prefix = dot > 0 ? name.substring(0, dot) : name;
        return parent.resolve(prefix + "_" + timestamp);
    }

    public static Path besideDirectory(Path inputDirectory, String timestamp) {
        Path absolute = inputDirectory.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || absolute.getFileName() == null) {
            throw new IllegalArgumentException("所选二维码目录没有可用的同级输出位置：" + inputDirectory);
        }
        return parent.resolve(absolute.getFileName().toString() + "_" + timestamp);
    }
}
