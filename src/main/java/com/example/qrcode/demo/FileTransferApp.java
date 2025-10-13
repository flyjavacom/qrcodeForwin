package com.example.qrcode.demo;

import com.example.qrcode.utils.FileConvertUtil;
import com.example.qrcode.utils.JCodecUtill;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

public class FileTransferApp extends JFrame {

    private JButton uploadFileButton;
    private JButton uploadVideoButton;
    private JButton selectFolderButton;

    private JTextArea descriptionTextArea;

    private JTextPane statusTextPane;


    private JFileChooser fileChooser;
    private File selectedFile;
    private File selectedFolder;
    private File selectedVideoFile;

    public FileTransferApp() {
        setTitle("文件传输工具（无网机器==>有网机器）  --学习交流工具，请勿用于传输涉密文件！");
        setSize(800, 800);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        // 初始化组件
        uploadFileButton = new JButton("「文件==》二维码图片  上传7M以内文件」");
        selectFolderButton = new JButton("「二维码图片==》文件]  选择二维码文件夹」");
        uploadVideoButton = new JButton("「二维码视频==》文件]  上传视频文件」");
        fileChooser = new JFileChooser();

        // 创建描述文本区域
        descriptionTextArea = new JTextArea();
        descriptionTextArea.setEditable(false);
        descriptionTextArea.setLineWrap(true);
        descriptionTextArea.setWrapStyleWord(true);
        descriptionTextArea.setText(
                "说明文档:\n" +
                        " \r\n" +
                        "0.准备条件 无网机器和有网机器需要安装该软件(JDK8)，以及可以拍照录视频的手机。  \r\n" +
                        "1.[无网机器] 点击按钮【「文件==》二维码图片  上传7M以内文件」】，选择需要传输文件。  \r\n" +
                        "2.[无网机器] 会在上传文件对应文件夹路径生成 temp_qr_codes 目录，里面存放的就是二维码图片。 \r\n" +
                        " \r\n" +
                        " 【方式1】手机拍照二维码方式  [一张一张拍照，图片需要清晰] \r\n" +
                        "3.1.[有网机器] 通过手机拍照+传输工具将二维码图片存在本电脑一个文件夹里面。  \r\n" +
                        "3.2.[有网机器] 点击按钮【「二维码图片==》文件]  选择二维码文件夹」】 \r\n" +
                        "3.2.[有网机器] 会在选择的文件夹路径生成解析后的文件。 \r\n" +
                        " \r\n" +
                        " 【方式2】手机拍二维码视频方式  [建议视频清晰情况下图片翻页速度可以快，例如一秒2张，一秒3张]\r\n" +
                        "4.1.[有网机器] 通过手机录像+传输工具将视频存在本电脑一个文件夹里面。 推荐网页版微信文件传输助手传输视频！ \r\n" +
                        "4.2.[有网机器] 点击按钮【「二维码视频==》文件]  上传视频文件」】，选择录有二维码的视频，\r\n" +
                        "4.2.[有网机器] 会在上传视频文件对应文件夹路径生成 videoToPic 目录,里面按帧存放视频图片。  \r\n"+
                        "4.2.[有网机器] 会在 videoToPic 路径生成解析后的文件。 \r\n"
        );
        JScrollPane scrollPane = new JScrollPane(descriptionTextArea);


        // 创建状态文本面板
        statusTextPane = new JTextPane();
        statusTextPane.setEditable(false); // 设置为不可编辑


        // 设置初始行数以控制高度
        FontMetrics fontMetrics = statusTextPane.getFontMetrics(statusTextPane.getFont());
        int lineHeight = fontMetrics.getHeight();
        int preferredHeight = lineHeight * 15; // 15 lines of text
        statusTextPane.setPreferredSize(new Dimension(getWidth(), preferredHeight));
        JScrollPane statusScrollPane = new JScrollPane(statusTextPane);



        // 设置布局
        setLayout(new BorderLayout());

        // 添加描述文本区域到北部
        add(scrollPane, BorderLayout.NORTH);

        // 创建按钮面板
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
        buttonPanel.add(createButtonRow(uploadFileButton));
        buttonPanel.add(Box.createRigidArea(new Dimension(0, 2))); // 分隔符
        buttonPanel.add(createButtonRow(selectFolderButton));
        buttonPanel.add(Box.createRigidArea(new Dimension(0, 2))); // 分隔符
        buttonPanel.add(createButtonRow(uploadVideoButton));

        add(buttonPanel, BorderLayout.CENTER);
        add(statusScrollPane, BorderLayout.SOUTH);

        // 添加事件监听器
        // [XLS文件==》二维码]  上传XLS文件
        uploadFileButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                new Thread(() -> {

                    try{
                        // 关闭按钮
                        disableButtons();
                        // 只能选择文件
                        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
                        int returnVal = fileChooser.showOpenDialog(FileTransferApp.this);
                        if (returnVal == JFileChooser.APPROVE_OPTION) {
                            selectedFile = fileChooser.getSelectedFile();
                            appendStatus("状态: 已选择文件 " + selectedFile.getName());

                            // 判断文件是否7m 以内
                            long fileSizeInBytes = selectedFile.length();
                            double fileSizeInMB = (double) fileSizeInBytes / (1024 * 1024);
                            if (fileSizeInMB > 7) {
                                appendStatus("已选择文件大小！ " + fileSizeInMB + " MB");
                                throw new Exception("文件大小超过7M ，请重新选择！");
                            }

                            appendStatus("开始将文件转成 二维码文件！ " + selectedFile.getName());
                            String res = FileConvertUtil.fileToQrCode(selectedFile.getAbsolutePath());
                            appendStatus("完成将文件转成 二维码文件！ 生成路径：" + res);
                            JOptionPane.showMessageDialog(FileTransferApp.this, "完成将文件转成 二维码文件！生成路径："+ res, "处理成功", JOptionPane.INFORMATION_MESSAGE);

                        } else {
                            appendStatus("状态: 未选择文件");
                            JOptionPane.showMessageDialog(FileTransferApp.this, "未选择文件 " , "错误", JOptionPane.ERROR_MESSAGE);
                        }
                    }catch (Exception ex){
                        ex.printStackTrace();
                        appendStatus("异常 [文件==》二维码]！ " + ex.toString());
                        JOptionPane.showMessageDialog(FileTransferApp.this, "异常 [文件==》二维码]！ "  + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                    }finally {
                        // 开启按钮
                        enableButtons();
                    }
                }).start();

            }
        });




        // [二维码图片==》XLS文件]  选择二维码文件夹
        selectFolderButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                new Thread(() -> {

                    try{
                        // 关闭按钮
                        disableButtons();
                        // 只能选择文件夹
                        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                        int returnVal = fileChooser.showOpenDialog(FileTransferApp.this);
                        if (returnVal == JFileChooser.APPROVE_OPTION) {
                            selectedFolder = fileChooser.getSelectedFile();
                            appendStatus("状态: 已选择文件夹 " + selectedFolder.getAbsolutePath());

                            appendStatus("开始 [二维码图片==》文件]！ " + selectedFolder.getName());
                            String res = FileConvertUtil.qrCode4ToFile(selectedFolder.getAbsolutePath());
                            appendStatus("完成 [二维码图片==》文件]！ 生成路径："+ res);
                            JOptionPane.showMessageDialog(FileTransferApp.this, "完成 [二维码图片==》文件]！生成路径："+ res, "处理成功", JOptionPane.INFORMATION_MESSAGE);
                        } else {
                            appendStatus("状态: 未选择文件夹");
                            JOptionPane.showMessageDialog(FileTransferApp.this, "未选择文件夹 " , "错误", JOptionPane.ERROR_MESSAGE);
                        }
                    }catch (Exception ex){
                        ex.printStackTrace();
                        appendStatus("异常  [二维码图片==》文件]！ " + ex.toString());
                        JOptionPane.showMessageDialog(FileTransferApp.this, "异常  [二维码图片==》文件]！ "  + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                    }finally {
                        // 开启按钮
                        enableButtons();
                    }
                }).start();
            }
        });

        // 上传视频文件
        // [二维码视频==》XLS文件]  上传视频文件
        uploadVideoButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                new Thread(() -> {

                    try{
                        // 关闭按钮
                        disableButtons();
                        // 只能选择文件
                        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
                        int returnVal = fileChooser.showOpenDialog(FileTransferApp.this);
                        if (returnVal == JFileChooser.APPROVE_OPTION) {
                            selectedVideoFile = fileChooser.getSelectedFile();
                            appendStatus("状态: 已选择视频文件 " + selectedVideoFile.getName());
                            // 文件格式判断
                            if (!isVideoFile(selectedVideoFile)) {
                                throw new Exception("不支持该视频文件格式");
                            }
                            String videoPath = selectedVideoFile.getAbsolutePath();
                            // 视频当前文件夹
                            String videoParent = selectedVideoFile.getParent();
                            appendStatus("开始 [二维码视频==》图片]！ " + selectedVideoFile.getName());
                            String outputDirPath = videoParent + "/videoToPic/";
                            JCodecUtill.videoToPic(videoPath,outputDirPath );
                            appendStatus("完成 [二维码视频==》按帧截取图片]！ 按帧截取图片输出目录：" + outputDirPath);
                            appendStatus("开始 [按帧截取图片==》文件]！ 按帧截取图片输出目录：" + outputDirPath);
                            String res = FileConvertUtil.qrCode4ToFile(outputDirPath);
                            appendStatus("完成 [按帧截取图片==》文件]！ " + selectedVideoFile.getName());
                            appendStatus("完成 [二维码视频==》按帧截取图片==》文件]！ 生成路径："+ res);
                            JOptionPane.showMessageDialog(FileTransferApp.this, "完成 [二维码视频==》文件]！生成路径："+ res, "处理成功", JOptionPane.INFORMATION_MESSAGE);
                        } else {
                            appendStatus("状态: 未选择文件");
                            JOptionPane.showMessageDialog(FileTransferApp.this, "未选择文件 " , "错误", JOptionPane.ERROR_MESSAGE);
                        }
                    }catch (Exception ex){
                        ex.printStackTrace();
                        appendStatus("异常 [二维码视频==》文件]！ " + ex.toString());
                        JOptionPane.showMessageDialog(FileTransferApp.this, "异常 [二维码视频==》文件]！ "  + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                    }finally {
                        // 开启按钮
                        enableButtons();
                    }
                }).start();
            }
        });
    }





    private JPanel createButtonRow(JButton button) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        panel.add(button);
        return panel;
    }

    /**
     * 窗口日志展示
     * @param message
     */
    private void appendStatus(String message) {
        String currentText = statusTextPane.getText();
        if (!currentText.isEmpty()) {
            currentText += "\n";
        }
        // 时间
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss ").format(new Date());

        String timestampAndMessage =timestamp + message;
        currentText += timestampAndMessage ;
        System.out.println(currentText);
        statusTextPane.setText(currentText);
        // 自动滚动到底部
        statusTextPane.setCaretPosition(statusTextPane.getDocument().getLength());
    }




    /**
     *  按钮不能点击
     */
    private void disableButtons() {
        uploadFileButton.setEnabled(false);
        selectFolderButton.setEnabled(false);
        uploadVideoButton.setEnabled(false);
    }

    /**
     *  按钮能点击
     */
    private void enableButtons() {
        uploadFileButton.setEnabled(true);
        selectFolderButton.setEnabled(true);
        uploadVideoButton.setEnabled(true);
    }

    /**
     * 获取文件名后缀
     * @param fileName
     * @return
     */
    private String getFileExtension(String fileName) {
        if (fileName.lastIndexOf(".") != -1 && fileName.lastIndexOf(".") != 0) {
            return fileName.substring(fileName.lastIndexOf(".") + 1);
        } else {
            return "";
        }
    }

    /**
     * 判断是否是视频
     * @param file
     * @return
     */
    private boolean isVideoFile(File file) {
        String extension = getFileExtension(file.getName()).toLowerCase();
        return extension.equals("mp4") || extension.equals("avi") || extension.equals("mkv") || extension.equals("mov") || extension.equals("flv") || extension.equals("wmv");
    }
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            FileTransferApp app = new FileTransferApp();
            app.setVisible(true);
        });
    }
}


