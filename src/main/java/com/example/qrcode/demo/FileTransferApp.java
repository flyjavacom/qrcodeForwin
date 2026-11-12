package com.example.qrcode.demo;

import com.example.qrcode.utils.FileConvertUtil;
import com.example.qrcode.utils.FourQrFrameComposer;
import com.example.qrcode.utils.VideoQrDecoder;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Window;
import java.awt.Toolkit;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** Swing front end for legacy single-QR and new four-QR image workflows. */
public class FileTransferApp extends JFrame {
    private static final int MAX_STATUS_CHARACTERS = 30000;
    private static final Color BACKGROUND = new Color(0xF3F6FA);
    private static final Color SURFACE = Color.WHITE;
    private static final Color TEXT = new Color(0x182230);
    private static final Color MUTED = new Color(0x637083);
    private static final Color ACCENT = new Color(0x1769AA);
    private static final Color BORDER = new Color(0xE0E7EF);

    private final JButton uploadFileButton = new JButton("选择文件并生成四码图片");
    private final JButton selectFolderButton = new JButton("从二维码图片恢复");
    private final JButton uploadVideoButton = new JButton("从二维码视频恢复");
    private final JButton playGeneratedImagesButton = new JButton("播放图片");
    private final JButton openGeneratedFolderButton = new JButton("打开文件夹");
    private final JButton openReceivedFolderButton = new JButton("打开文件夹");
    private final JTextField generatedFramesPathField = new JTextField("尚未生成四码图片");
    private final JTextField receivedFilePathField = new JTextField("尚未解析文件");
    private final JTextPane statusTextPane = new JTextPane();
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JFileChooser fileChooser = new JFileChooser();
    private final JTextArea taskLabel = wrappedTextArea("准备就绪", 2, 12);
    private final TransferAppSettings settings = TransferAppSettings.load();

    public FileTransferApp() {
        setTitle("文件传输工具（无网机器 → 有网机器）");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        Dimension usableScreen = usableScreenSize();
        setMinimumSize(new Dimension(Math.min(720, usableScreen.width), Math.min(660, usableScreen.height)));
        getContentPane().setBackground(BACKGROUND);
        setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 14));
        applyUiDefaults();

        statusTextPane.setEditable(false);
        statusTextPane.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        statusTextPane.setForeground(new Color(0x344054));
        statusTextPane.setBackground(new Color(0xFBFCFE));
        statusTextPane.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JScrollPane statusScroll = new JScrollPane(statusTextPane);
        statusScroll.setBorder(BorderFactory.createLineBorder(BORDER));
        statusScroll.setPreferredSize(new Dimension(720, 76));

        progressBar.setStringPainted(true);
        progressBar.setString("就绪");

        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setOpaque(false);
        root.setBorder(BorderFactory.createEmptyBorder(22, 26, 20, 26));
        root.add(createHeader(), BorderLayout.NORTH);
        JPanel content = new JPanel(new GridBagLayout());
        content.setOpaque(false);
        GridBagConstraints contentRow = new GridBagConstraints();
        contentRow.gridx = 0;
        contentRow.weightx = 1;
        contentRow.fill = GridBagConstraints.HORIZONTAL;
        contentRow.anchor = GridBagConstraints.NORTHWEST;
        contentRow.insets = new java.awt.Insets(0, 0, 14, 0);
        contentRow.weighty = 0;
        content.add(createWorkArea(), contentRow);
        contentRow.gridy = 1;
        contentRow.weighty = 1;
        contentRow.fill = GridBagConstraints.BOTH;
        contentRow.insets = new java.awt.Insets(0, 0, 0, 0);
        content.add(createActivityPanel(statusScroll), contentRow);
        root.add(content, BorderLayout.CENTER);
        setContentPane(root);
        setSize(Math.min(1040, usableScreen.width), Math.min(790, usableScreen.height));
        setLocationRelativeTo(null);

        uploadFileButton.addActionListener(e -> selectFileForEncoding());
        selectFolderButton.addActionListener(e -> selectFolderForDecoding());
        uploadVideoButton.addActionListener(e -> selectVideoForDecoding());
        playGeneratedImagesButton.addActionListener(e -> playGeneratedFrames());
        openGeneratedFolderButton.addActionListener(e -> openDirectoryFromField(generatedFramesPathField));
        openReceivedFolderButton.addActionListener(e -> openDirectoryFromField(receivedFilePathField));
        generatedFramesPathField.setEditable(false);
        receivedFilePathField.setEditable(false);
        generatedFramesPathField.setBackground(Color.WHITE);
        receivedFilePathField.setBackground(Color.WHITE);
        stylePathField(generatedFramesPathField);
        stylePathField(receivedFilePathField);
    }

    private JPanel createHeader() {
        JPanel headerBar = new JPanel(new BorderLayout(12, 0));
        headerBar.setOpaque(false);
        JLabel title = new JLabel("文件传输工作台");
        title.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 22));
        title.setForeground(TEXT);
        headerBar.add(title, BorderLayout.WEST);
        JLabel badge = new JLabel("旧协议兼容  ·  四码模式");
        badge.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 12));
        badge.setForeground(ACCENT);
        badge.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xC8DDF0), 1, true),
                BorderFactory.createEmptyBorder(5, 9, 5, 9)));
        badge.setOpaque(true);
        badge.setBackground(new Color(0xEDF6FD));
        headerBar.add(badge, BorderLayout.CENTER);
        JPanel utilities = new JPanel(new GridLayout(1, 3, 8, 0));
        utilities.setOpaque(false);
        JButton helpButton = new JButton("使用说明");
        JButton settingsButton = new JButton("目录设置");
        JButton playbackButton = new JButton("播放速度");
        styleButton(helpButton, false);
        styleButton(settingsButton, false);
        styleButton(playbackButton, false);
        helpButton.addActionListener(e -> showUsage());
        settingsButton.addActionListener(e -> showDirectorySettings());
        playbackButton.addActionListener(e -> showPlaybackSettings());
        utilities.add(helpButton);
        utilities.add(settingsButton);
        utilities.add(playbackButton);
        headerBar.add(utilities, BorderLayout.EAST);
        stretchHorizontally(headerBar);
        return headerBar;
    }

    private JPanel createWorkArea() {
        JPanel area = new JPanel();
        area.setOpaque(false);
        area.setLayout(new GridBagLayout());
        GridBagConstraints row = new GridBagConstraints();
        row.gridx = 0;
        row.weightx = 1;
        row.fill = GridBagConstraints.HORIZONTAL;
        row.anchor = GridBagConstraints.NORTHWEST;
        row.insets = new java.awt.Insets(0, 0, 12, 0);
        JPanel send = cardPanel();
        send.setLayout(new BorderLayout(14, 12));
        JPanel sendHeading = new JPanel(new BorderLayout(12, 0));
        sendHeading.setOpaque(false);
        sendHeading.add(sectionHeading("发送文件", "生成新版四码二维码图片，可直接播放或用相机拍摄"), BorderLayout.CENTER);
        styleButton(uploadFileButton, true);
        uploadFileButton.setPreferredSize(new Dimension(244, 44));
        JPanel sendAction = new JPanel(new GridBagLayout());
        sendAction.setOpaque(false);
        sendAction.add(uploadFileButton);
        sendHeading.add(sendAction, BorderLayout.EAST);
        JPanel sendBody = new JPanel(new BorderLayout(0, 12));
        sendBody.setOpaque(false);
        sendBody.add(sendHeading, BorderLayout.NORTH);
        sendBody.add(createGeneratedFramesPanel(), BorderLayout.CENTER);
        send.add(sendBody, BorderLayout.CENTER);
        area.add(send, row);

        JPanel receive = cardPanel();
        receive.setLayout(new BorderLayout(0, 12));
        receive.add(sectionHeading("接收文件", "支持旧版单码与新版四码图片、视频恢复"), BorderLayout.NORTH);
        JPanel receiveContent = new JPanel(new GridLayout(1, 2, 10, 0));
        receiveContent.setOpaque(false);
        styleButton(selectFolderButton, false);
        styleButton(uploadVideoButton, false);
        selectFolderButton.setPreferredSize(new Dimension(220, 42));
        uploadVideoButton.setPreferredSize(new Dimension(220, 42));
        receiveContent.add(selectFolderButton);
        receiveContent.add(uploadVideoButton);
        receive.add(receiveContent, BorderLayout.CENTER);
        JPanel receivedPathRow = new JPanel(new BorderLayout(8, 0));
        receivedPathRow.setOpaque(false);
        JLabel receivedLabel = new JLabel("解析文件位置");
        receivedLabel.setPreferredSize(new Dimension(112, 32));
        receivedFilePathField.setToolTipText("最近一次成功恢复的文件路径");
        styleButton(openReceivedFolderButton, false);
        receivedPathRow.add(receivedLabel, BorderLayout.WEST);
        receivedPathRow.add(receivedFilePathField, BorderLayout.CENTER);
        openReceivedFolderButton.setPreferredSize(new Dimension(116, 38));
        receivedPathRow.add(openReceivedFolderButton, BorderLayout.EAST);
        receive.add(receivedPathRow, BorderLayout.SOUTH);
        row.gridy = 1;
        row.insets = new java.awt.Insets(0, 0, 0, 0);
        area.add(receive, row);
        stretchHorizontally(area);

        return area;
    }

    private JPanel createGeneratedFramesPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 5));
        panel.setOpaque(false);
        JLabel label = new JLabel("生成图片位置");
        label.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 12));
        label.setForeground(MUTED);
        panel.add(label, BorderLayout.NORTH);
        generatedFramesPathField.setToolTipText("四码图片输出目录");
        JPanel pathRow = new JPanel(new BorderLayout(8, 0));
        pathRow.setOpaque(false);
        pathRow.add(generatedFramesPathField, BorderLayout.CENTER);
        JPanel actions = new JPanel(new GridLayout(1, 2, 6, 0));
        actions.setOpaque(false);
        styleButton(openGeneratedFolderButton, false);
        styleButton(playGeneratedImagesButton, true);
        actions.setPreferredSize(new Dimension(224, 38));
        actions.add(openGeneratedFolderButton);
        actions.add(playGeneratedImagesButton);
        pathRow.add(actions, BorderLayout.EAST);
        panel.add(pathRow, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(720, 48));
        return panel;
    }

    private JPanel createSection(String title, String description, JButton action) {
        JPanel panel = cardPanel();
        panel.setLayout(new BorderLayout(14, 0));
        panel.add(sectionHeading(title, description), BorderLayout.CENTER);
        styleButton(action, true);
        action.setPreferredSize(new Dimension(190, 44));
        JPanel actionSlot = new JPanel(new java.awt.GridBagLayout());
        actionSlot.setOpaque(false);
        actionSlot.add(action);
        panel.add(actionSlot, BorderLayout.EAST);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        stretchHorizontally(panel);
        return panel;
    }

    private static void stretchHorizontally(JPanel panel) {
        Dimension preferred = panel.getPreferredSize();
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
    }

    private JPanel sectionHeading(String title, String description) {
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel heading = new JLabel(title);
        heading.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 16));
        heading.setForeground(TEXT);
        JTextArea detail = wrappedTextArea(description, 2, 12);
        heading.setAlignmentX(LEFT_ALIGNMENT);
        detail.setAlignmentX(LEFT_ALIGNMENT);
        detail.setMaximumSize(new Dimension(Integer.MAX_VALUE, detail.getPreferredSize().height));
        text.add(heading);
        text.add(Box.createRigidArea(new Dimension(0, 5)));
        text.add(detail);
        return text;
    }

    private JPanel createActivityPanel(JScrollPane statusScroll) {
        JPanel activity = cardPanel();
        activity.setLayout(new BorderLayout(0, 9));
        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel label = new JLabel("运行状态");
        label.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 14));
        label.setForeground(TEXT);
        taskLabel.setForeground(MUTED);
        taskLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, taskLabel.getPreferredSize().height));
        label.setAlignmentX(LEFT_ALIGNMENT);
        taskLabel.setAlignmentX(LEFT_ALIGNMENT);
        heading.add(label);
        heading.add(Box.createRigidArea(new Dimension(0, 4)));
        heading.add(taskLabel);
        progressBar.setPreferredSize(new Dimension(100, 20));
        activity.setPreferredSize(new Dimension(720, 156));
        activity.add(heading, BorderLayout.NORTH);
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setOpaque(false);
        content.add(progressBar, BorderLayout.NORTH);
        content.add(statusScroll, BorderLayout.CENTER);
        activity.add(content, BorderLayout.CENTER);
        stretchHorizontally(activity);
        return activity;
    }

    private JPanel cardPanel() {
        JPanel panel = new JPanel();
        panel.setBackground(SURFACE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1, true),
                BorderFactory.createEmptyBorder(14, 16, 14, 16)));
        return panel;
    }

    private static JTextArea wrappedTextArea(String text, int rows, int size) {
        JTextArea area = new JTextArea(text, rows, 32);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder());
        area.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, size));
        area.setForeground(MUTED);
        return area;
    }

    private void styleButton(JButton button, boolean primary) {
        button.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 13));
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setBackground(primary ? ACCENT : new Color(0xF7F9FC));
        button.setForeground(primary ? Color.WHITE : new Color(0x344054));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(primary ? ACCENT : BORDER, 1, true),
                BorderFactory.createEmptyBorder(7, 10, 7, 10)));
        button.setMinimumSize(new Dimension(88, 36));
        button.setPreferredSize(new Dimension(Math.max(96, button.getPreferredSize().width), 38));
        button.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
    }

    private static void stylePathField(JTextField field) {
        field.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        field.setForeground(new Color(0x344054));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1, true),
                BorderFactory.createEmptyBorder(0, 9, 0, 9)));
        field.setPreferredSize(new Dimension(260, 38));
    }

    private void applyUiDefaults() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Keep the platform default look and feel when the native one is unavailable.
        }
    }

    private void selectFileForEncoding() {
        clearGeneratedResult();
        setChooserDirectoryFromSettings();
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (fileChooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = fileChooser.getSelectedFile();
        if (!file.isFile()) {
            showError("所选路径不是文件。", "选择失败");
            return;
        }
        if (file.length() > FileConvertUtil.MAX_LEGACY_FILE_BYTES) {
            showError("当前二维码页格式最多支持 7 MiB。当前文件为 "
                    + String.format(java.util.Locale.ROOT, "%.2f", file.length() / (1024.0 * 1024.0)) + " MiB。", "文件过大");
            return;
        }

        Path output = TransferOutputPaths.besideFile(file.toPath(),
                new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date()));
        runTask("正在直接生成四码图片：" + file.getName(), () -> {
            FourQrFrameComposer.composeFile(file.toPath(), output, (done, total) -> {
                int percent = total == 0 ? 100 : (int) (done * 100L / total);
                updateProgress(percent, "正在生成四码图片帧 " + done + "/" + total);
            });
            return output.toString();
        }, result -> {
            updateProgress(100, "完成");
            setGeneratedResult(new File(result).toPath());
            appendStatus("四码图片目录：" + result);
            showArtifactDialog("四码图片生成完成", "已直接生成新协议四码图片（每帧最多 4 个二维码）。\n\n图片目录：" + result,
                    new ArtifactLocation("打开图片目录", new File(result).toPath()),
                    new ArtifactLocation("立即播放", () -> playFrames(new File(result).toPath())));
        });
    }

    private void selectFolderForDecoding() {
        clearReceivedResult();
        setChooserDirectoryFromSettings();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fileChooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File folder = fileChooser.getSelectedFile();
        Path output = TransferOutputPaths.besideDirectory(folder.toPath(),
                new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date()));
        runTask("正在识别二维码图片：" + folder.getName(),
                () -> FileConvertUtil.qrCode4ToFile(folder.getAbsolutePath(), output.toString()),
                result -> {
                    setReceivedResult(new File(result).toPath());
                    showArtifactDialog("文件恢复完成", "文件已恢复：\n" + result,
                            new ArtifactLocation("打开恢复文件目录", new File(result).toPath()));
                });
    }

    private void selectVideoForDecoding() {
        clearReceivedResult();
        setChooserDirectoryFromSettings();
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (fileChooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File video = fileChooser.getSelectedFile();
        String name = video.getName().toLowerCase(java.util.Locale.ROOT);
        if (!(name.endsWith(".mp4") || name.endsWith(".avi") || name.endsWith(".mkv")
                || name.endsWith(".mov") || name.endsWith(".flv") || name.endsWith(".wmv")
                || name.endsWith(".m4v") || name.endsWith(".webm"))) {
            showError("不支持该视频格式。", "选择失败");
            return;
        }
        Path output = TransferOutputPaths.besideFile(video.toPath(),
                new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date()));
        runTask("正在识别二维码视频：" + video.getName(), () -> {
            return VideoQrDecoder.videoToFile(video.getAbsolutePath(), output.toString(),
                    VideoQrDecoder.DEFAULT_FPS, (message, percent) -> {
                        updateProgress(percent, message);
                        if (percent % 10 == 0 || message.contains("缺页") || message.contains("补拍")) {
                            appendStatus("[视频 " + percent + "%] " + message);
                        }
                    });
        }, result -> {
            updateProgress(100, "完成");
            setReceivedResult(new File(result).toPath());
            showArtifactDialog("文件恢复完成", "文件已恢复：\n" + result
                            + "\n\n已识别二维码和缺页清单保存在：\n" + output,
                    new ArtifactLocation("打开结果目录", output),
                    new ArtifactLocation("打开补拍二维码目录", output.resolve("recognized_qr")));
        });
    }

    private void showUsage() {
        JTextArea help = new JTextArea(
                "文件传输工具使用说明\n\n"
                + "一、发送文件\n选择文件后直接生成新协议四码二维码图片，不落盘中间单码图片，也不生成视频。每帧最多 4 个二维码；生成图片位置、打开文件夹和播放按钮显示在发送文件区域下方。当前单文件上限为 7 MiB。\n\n"
                + "二、接收二维码图片\n选择包含二维码图片的文件夹。恢复文件会写入该图片文件夹旁边、以输入文件夹名称和时间命名的结果目录。新协议单码图片请连同 transfer_manifest.txt 一起保留；新版恢复文件按原文件名开头并追加生成时间。旧协议没有原文件名信息，会使用 recovered 开头。\n\n"
                + "三、接收二维码视频\n选择手机拍摄的视频。识别二维码、缺页清单和恢复文件会写入视频文件旁边、以视频文件名和时间命名的结果目录。建议使用原始视频，不要通过聊天软件压缩。\n\n"
                + "四、四码图片播放\n生成完成后，可点击发送文件区域中的“播放图片”或结果弹窗中的“立即播放”。默认停留时间为 0.2 秒，可在顶部“播放速度”设置中修改；播放窗口也可以临时减慢或加快。接收端必须使用支持多码帧和清单校验的新版本。\n\n"
                + "五、完整性与补拍\n新协议清单包含原文件名、页数、文件大小和 SHA-256。缺页或校验失败时不会输出成功文件。视频识别产生的补拍二维码保存在本次视频结果目录的 recognized_qr 子目录。\n\n"
                + "六、应用配置\n目录设置用于选择发送文件、接收二维码图片或接收视频时，选择窗口默认打开的目录；二维码图片、视频和恢复文件等过程产物位于相应输入旁边的结果目录。播放速度通过单独的“播放速度”设置调整。目录不存在时程序会自动创建。配置保存在当前 Windows 用户目录下的 .qrcode-forwin/settings.properties。\n\n"
                + "传输前建议先用小文件测试拍摄距离、对焦和清晰度。"
        );
        help.setEditable(false);
        help.setLineWrap(true);
        help.setWrapStyleWord(true);
        help.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        help.setBackground(Color.WHITE);
        help.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        JScrollPane scroll = new JScrollPane(help);
        Dimension usable = usableScreenSize();
        scroll.setPreferredSize(new Dimension(Math.max(260, Math.min(610, usable.width - 48)),
                Math.max(180, Math.min(440, usable.height - 120))));
        JOptionPane.showMessageDialog(this, scroll, "使用说明", JOptionPane.INFORMATION_MESSAGE);
    }

    private void showDirectorySettings() {
        JTextField qrField = new JTextField(settings.getQrOutputDirectory(), 28);
        JPanel form = new JPanel(new BorderLayout(0, 8));
        JLabel description = new JLabel("发送文件及接收图片、视频的选择窗口都从此文件夹开始");
        description.setForeground(MUTED);
        form.add(description, BorderLayout.NORTH);
        form.add(directoryRow("发送文件选择目录", qrField), BorderLayout.CENTER);
        int result = JOptionPane.showConfirmDialog(this, form, "发送文件目录设置",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            String qr = validateDirectory(qrField.getText());
            settings.setQrOutputDirectory(qr);
            settings.save();
            appendStatus("发送文件选择目录已保存：" + qr);
            JOptionPane.showMessageDialog(this, "发送文件选择目录已保存。", "设置完成", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            showError(ex.getMessage(), "目录设置失败");
        }
    }

    private void showPlaybackSettings() {
        JSpinner dwellSpinner = new JSpinner(new SpinnerNumberModel(
                settings.getPlaybackDwellMillis(), 200, 10000, 50));
        JPanel form = new JPanel(new BorderLayout(10, 0));
        JLabel label = new JLabel("每张图片停留");
        label.setPreferredSize(new Dimension(112, 32));
        form.add(label, BorderLayout.WEST);
        form.add(dwellSpinner, BorderLayout.CENTER);
        JLabel unit = new JLabel("毫秒（200 毫秒 = 每秒 5 张）");
        unit.setForeground(MUTED);
        form.add(unit, BorderLayout.EAST);
        int result = JOptionPane.showConfirmDialog(this, form, "播放速度设置",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            settings.setPlaybackDwellMillis((Integer) dwellSpinner.getValue());
            settings.save();
            appendStatus("图片播放速度已保存：每张停留 " + settings.getPlaybackDwellMillis() + " 毫秒。");
            JOptionPane.showMessageDialog(this, "播放速度已保存。", "设置完成", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            showError(ex.getMessage(), "播放速度设置失败");
        }
    }

    private JPanel directoryRow(String label, JTextField field) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        JLabel title = new JLabel(label);
        title.setPreferredSize(new Dimension(132, 32));
        JButton browse = new JButton("浏览…");
        styleButton(browse, false);
        browse.setPreferredSize(new Dimension(94, 36));
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(field.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("选择" + label);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                field.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        row.add(title, BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        row.add(browse, BorderLayout.EAST);
        return row;
    }

    private String validateDirectory(String value) throws IOException {
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("目录不能为空。");
        }
        Path path = new File(value.trim()).toPath().toAbsolutePath().normalize();
        java.nio.file.Files.createDirectories(path);
        if (!java.nio.file.Files.isDirectory(path) || !java.nio.file.Files.isWritable(path)) {
            throw new IOException("目录不可写：" + path);
        }
        return path.toString();
    }

    private void setChooserDirectoryFromSettings() {
        fileChooser.setCurrentDirectory(new File(settings.getQrOutputDirectory()));
    }

    private static String baseNameWithoutExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private Path lastGeneratedFramesDirectory;
    private Path lastReceivedFile;

    private void clearGeneratedResult() {
        lastGeneratedFramesDirectory = null;
        generatedFramesPathField.setText("尚未生成四码图片");
    }

    private void setGeneratedResult(Path directory) {
        lastGeneratedFramesDirectory = directory;
        String path = directory.toAbsolutePath().toString();
        generatedFramesPathField.setText(path);
        generatedFramesPathField.setToolTipText(path);
        generatedFramesPathField.setCaretPosition(0);
    }

    private void clearReceivedResult() {
        lastReceivedFile = null;
        receivedFilePathField.setText("尚未解析文件");
    }

    private void setReceivedResult(Path file) {
        lastReceivedFile = file;
        String path = file.toAbsolutePath().toString();
        receivedFilePathField.setText(path);
        receivedFilePathField.setToolTipText(path);
        receivedFilePathField.setCaretPosition(0);
    }

    private void openDirectoryFromField(JTextField field) {
        if (field == generatedFramesPathField && lastGeneratedFramesDirectory != null) {
            openDirectory(lastGeneratedFramesDirectory);
        } else if (field == receivedFilePathField && lastReceivedFile != null) {
            openDirectory(lastReceivedFile);
        } else {
            showInfo("当前没有可打开的结果目录。", "尚无结果");
        }
    }

    private void playGeneratedFrames() {
        if (lastGeneratedFramesDirectory != null) {
            playFrames(lastGeneratedFramesDirectory);
        } else {
            showInfo("请先选择文件并生成四码图片。", "尚未生成图片");
        }
    }

    private void playFrames(Path directory) {
        try {
            QrImagePlayerFrame.open(directory, settings.getPlaybackDwellMillis());
        } catch (IOException ex) {
            showError(ex.getMessage(), "播放失败");
        }
    }

    private <T> void runTask(String startMessage, Callable<T> task, Consumer<T> onSuccess) {
        setBusy(true);
        taskLabel.setText(startMessage);
        progressBar.setIndeterminate(true);
        progressBar.setString("处理中");
        appendStatus(startMessage);
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return task.call();
            }

            @Override
            protected void done() {
                progressBar.setIndeterminate(false);
                setBusy(false);
                try {
                    onSuccess.accept(get());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    showError("处理被中断。", "处理失败");
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                    appendStatus("处理失败：" + message);
                    if (cause instanceof FileConvertUtil.DataIncompleteException) {
                        showScrollableMessage("数据不完整", message, JOptionPane.WARNING_MESSAGE);
                        updateProgress(0, "数据不完整，未生成文件");
                    } else {
                        showError(message, "处理失败");
                    }
                }
            }
        }.execute();
    }

    private void setBusy(boolean busy) {
        uploadFileButton.setEnabled(!busy);
        selectFolderButton.setEnabled(!busy);
        uploadVideoButton.setEnabled(!busy);
        if (!busy) {
            progressBar.setIndeterminate(false);
            taskLabel.setText("准备就绪");
        } else {
            taskLabel.setText("任务处理中");
        }
    }

    private void updateProgress(int percent, String message) {
        SwingUtilities.invokeLater(() -> {
            progressBar.setIndeterminate(false);
            progressBar.setValue(Math.max(0, Math.min(100, percent)));
            progressBar.setString(Math.max(0, Math.min(100, percent)) + "%");
            taskLabel.setText(message);
        });
    }

    private void appendStatus(String message) {
        SwingUtilities.invokeLater(() -> {
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss ").format(new Date()) + message;
            System.out.println(line);
            Document document = statusTextPane.getDocument();
            try {
                document.insertString(document.getLength(), line + "\n", null);
                int excess = document.getLength() - MAX_STATUS_CHARACTERS;
                if (excess > 0) {
                    document.remove(0, excess);
                }
                statusTextPane.setCaretPosition(document.getLength());
            } catch (BadLocationException ignored) {
                // A log line should never stop the file operation.
            }
        });
    }

    private void showArtifactDialog(String title, String message, ArtifactLocation... locations) {
        Dimension usable = usableScreenSize();
        int width = Math.max(320, Math.min(700, usable.width - 20));
        int height = Math.max(220, Math.min(470, usable.height - 20));
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));
        JTextArea description = new JTextArea(message);
        description.setEditable(false);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        description.setOpaque(false);
        description.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        description.setCaretPosition(0);
        JScrollPane messageScroll = new JScrollPane(description);
        messageScroll.setBorder(BorderFactory.createEmptyBorder());
        messageScroll.setPreferredSize(new Dimension(Math.max(250, width - 54), Math.max(60, height - 190)));
        content.add(messageScroll, BorderLayout.CENTER);

        JPanel actions = new JPanel(new GridLayout(locations.length + 1, 1, 0, 5));
        for (ArtifactLocation location : locations) {
            JButton open = new JButton(location.label);
            open.addActionListener(e -> {
                Window window = SwingUtilities.getWindowAncestor(content);
                if (window != null) window.dispose();
                if (location.action != null) location.action.run();
                else openDirectory(location.path);
            });
            actions.add(open);
        }
        JButton close = new JButton("关闭");
        close.addActionListener(e -> {
            Window window = SwingUtilities.getWindowAncestor(content);
            if (window != null) window.dispose();
        });
        actions.add(close);
        content.add(actions, BorderLayout.SOUTH);
        JOptionPane pane = new JOptionPane(content, JOptionPane.INFORMATION_MESSAGE, JOptionPane.DEFAULT_OPTION);
        JDialog dialog = pane.createDialog(this, title);
        dialog.setModal(true);
        dialog.setResizable(true);
        dialog.setSize(width, height);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void showScrollableMessage(String title, String message, int messageType) {
        Dimension usable = usableScreenSize();
        JTextArea text = new JTextArea(message);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(text);
        scroll.setPreferredSize(new Dimension(Math.max(250, Math.min(620, usable.width - 70)),
                Math.max(140, Math.min(360, usable.height - 150))));
        JOptionPane.showMessageDialog(this, scroll, title, messageType);
    }

    private static Dimension usableScreenSize() {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        return new Dimension(Math.max(320, screen.width - 40), Math.max(240, screen.height - 80));
    }

    private void openDirectory(Path path) {
        try {
            Path directory = java.nio.file.Files.isDirectory(path) ? path : path.getParent();
            if (directory == null || !java.nio.file.Files.exists(directory)) {
                throw new IOException("目录不存在：" + path);
            }
            if (!Desktop.isDesktopSupported()) {
                throw new IOException("当前系统不支持自动打开文件夹。");
            }
            Desktop.getDesktop().open(directory.toFile());
        } catch (IOException | UnsupportedOperationException ex) {
            showError("无法打开目录：" + ex.getMessage(), "打开目录失败");
        }
    }

    private static final class ArtifactLocation {
        final String label;
        final Path path;
        final Runnable action;

        ArtifactLocation(String label, Path path) {
            this.label = label;
            this.path = path;
            this.action = null;
        }

        ArtifactLocation(String label, Runnable action) {
            this.label = label;
            this.path = null;
            this.action = action;
        }
    }

    private void showError(String message, String title) {
        appendStatus(title + "：" + message);
            showScrollableMessage(title, message, JOptionPane.ERROR_MESSAGE);
    }

    private void showInfo(String message, String title) {
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.INFORMATION_MESSAGE);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            FileTransferApp app = new FileTransferApp();
            app.setVisible(true);
        });
    }
}
