package com.example.qrcode.demo;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Full-screen-capable slideshow that never shortens a frame to catch up after lag. */
public final class QrImagePlayerFrame extends JFrame {
    private static final int COUNTDOWN_SECONDS = 3;
    private final List<File> frames;
    private final ImagePanel imagePanel = new ImagePanel();
    private final JLabel status = new JLabel("正在加载图片…", JLabel.CENTER);
    private final JButton pauseButton = new JButton("暂停");
    private final JButton slowerButton = new JButton("减慢");
    private final JButton fasterButton = new JButton("加快");
    private final Timer timer;

    private int index;
    private int dwellMillis = 200;
    private boolean paused;
    private BufferedImage nextImage;
    private int nextIndex = -1;
    private boolean loading;
    private long countdownStartedAtNanos;
    private int lastCountdownSecond;
    private boolean playbackEnded;

    private QrImagePlayerFrame(List<File> frames, int initialDwellMillis) {
        super("四码图片播放");
        this.frames = frames;
        this.dwellMillis = Math.max(200, initialDwellMillis);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        setMinimumSize(new Dimension(Math.min(560, screen.width), Math.min(420, screen.height)));
        setSize(Math.min(1120, screen.width - 40), Math.min(900, screen.height - 80));
        setLocationRelativeTo(null);
        setExtendedState(JFrame.MAXIMIZED_BOTH);

        imagePanel.setBackground(Color.WHITE);
        add(imagePanel, BorderLayout.CENTER);
        JPanel controls = new JPanel();
        controls.add(pauseButton);
        controls.add(slowerButton);
        controls.add(fasterButton);
        controls.add(status);
        add(controls, BorderLayout.SOUTH);

        pauseButton.addActionListener(e -> {
            paused = !paused;
            pauseButton.setText(paused ? "继续" : "暂停");
            updateStatus();
        });
        slowerButton.addActionListener(e -> {
            dwellMillis += 50;
            updateStatus();
        });
        fasterButton.addActionListener(e -> {
            dwellMillis = Math.max(200, dwellMillis - 50);
            updateStatus();
        });
        timer = new Timer(20, e -> advanceIfReady());
        timer.start();
        loadFrame(0, true);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                timer.stop();
            }
        });
    }

    public static void open(Path directory) throws IOException {
        open(directory, 200);
    }

    public static void open(Path directory, int initialDwellMillis) throws IOException {
        File[] files = directory.toFile().listFiles(file -> file.isFile()
                && file.getName().startsWith("four_qr_frame_")
                && file.getName().toLowerCase(Locale.ROOT).endsWith(".png"));
        if (files == null || files.length == 0) {
            throw new IOException("没有可播放的四码帧：" + directory);
        }
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        List<File> ordered = new ArrayList<>(Arrays.asList(files));
        QrImagePlayerFrame player = new QrImagePlayerFrame(ordered, initialDwellMillis);
        player.setVisible(true);
    }

    private void loadFrame(int frameIndex, boolean current) {
        if (frameIndex >= frames.size() || loading) {
            return;
        }
        loading = true;
        File file = frames.get(frameIndex);
        new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() throws Exception {
                return ImageIO.read(file);
            }

            @Override
            protected void done() {
                loading = false;
                try {
                    BufferedImage image = get();
                    if (image == null) {
                        throw new IOException("无法读取图片：" + file.getName());
                    }
                    if (current) {
                        index = frameIndex;
                        nextImage = image;
                        nextIndex = frameIndex;
                        countdownStartedAtNanos = 0;
                        lastCountdownSecond = COUNTDOWN_SECONDS;
                        imagePanel.showCountdown(COUNTDOWN_SECONDS);
                        pauseButton.setEnabled(false);
                        status.setText("即将开始播放 · " + COUNTDOWN_SECONDS + " 秒");
                        loadNext();
                    } else {
                        nextImage = image;
                        nextIndex = frameIndex;
                    }
                } catch (Exception ex) {
                    status.setText("读取失败：" + file.getName() + "（" + ex.getMessage() + "）");
                }
            }
        }.execute();
    }

    private void loadNext() {
        if (index + 1 < frames.size() && nextImage == null && !loading) {
            loadFrame(index + 1, false);
        }
    }

    private void advanceIfReady() {
        if (countdownStartedAtNanos > 0) {
            int remaining = FrameDwellTiming.countdownSecondsRemaining(
                    countdownStartedAtNanos, System.nanoTime(), COUNTDOWN_SECONDS);
            if (remaining == 0) {
                countdownStartedAtNanos = 0;
                pauseButton.setEnabled(true);
                showNextLoadedFrame();
                return;
            }
            if (remaining != lastCountdownSecond) {
                lastCountdownSecond = remaining;
                imagePanel.showCountdown(remaining);
                status.setText("即将开始播放 · " + remaining + " 秒");
            }
            return;
        }
        if (countdownStartedAtNanos == 0 && imagePanel.getCountdownPaintedAtNanos() > 0) {
            countdownStartedAtNanos = imagePanel.getCountdownPaintedAtNanos();
        }
        if (countdownStartedAtNanos == 0 && lastCountdownSecond == COUNTDOWN_SECONDS) {
            return;
        }
        if (paused || playbackEnded) {
            return;
        }
        long shownAt = imagePanel.getShownAtNanos();
        if (shownAt == 0) {
            return;
        }
        if (!FrameDwellTiming.hasElapsed(shownAt, System.nanoTime(), dwellMillis)) {
            return;
        }
        if (nextImage == null || nextIndex < 0) {
            if (index + 1 >= frames.size() && !loading) {
                finishPlayback();
            }
            return;
        }
        showNextLoadedFrame();
    }

    private void showNextLoadedFrame() {
        if (nextImage == null || nextIndex < 0) {
            return;
        }
        BufferedImage image = nextImage;
        index = nextIndex;
        nextImage = null;
        nextIndex = -1;
        imagePanel.showImage(image);
        updateStatus();
        loadNext();
    }

    private void finishPlayback() {
        if (playbackEnded) {
            return;
        }
        playbackEnded = true;
        timer.stop();
        pauseButton.setEnabled(false);
        status.setText("播放结束 · 共 " + frames.size() + " 张图片");
        JOptionPane.showMessageDialog(this, "图片播放已结束，共播放 " + frames.size() + " 张。",
                "播放结束", JOptionPane.INFORMATION_MESSAGE);
    }

    private void updateStatus() {
        status.setText("第 " + (index + 1) + "/" + frames.size() + " 张  |  最短停留 "
                + dwellMillis + " ms" + (paused ? "  |  已暂停" : ""));
    }

    private static final class ImagePanel extends JPanel {
        private BufferedImage image;
        private long shownAtNanos;
        private long countdownPaintedAtNanos;
        private int countdownNumber;

        void showImage(BufferedImage image) {
            this.image = image;
            this.countdownNumber = 0;
            this.shownAtNanos = 0;
            this.countdownPaintedAtNanos = 0;
            repaint();
        }

        void showCountdown(int seconds) {
            this.image = null;
            this.countdownNumber = seconds;
            this.shownAtNanos = 0;
            this.countdownPaintedAtNanos = 0;
            repaint();
        }

        long getShownAtNanos() {
            return shownAtNanos;
        }

        long getCountdownPaintedAtNanos() {
            return countdownPaintedAtNanos;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (countdownNumber > 0) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g.setColor(new Color(0x1769AA));
                    g.setFont(getFont().deriveFont(java.awt.Font.BOLD, 96f));
                    String number = Integer.toString(countdownNumber);
                    java.awt.FontMetrics metrics = g.getFontMetrics();
                    g.drawString(number, (getWidth() - metrics.stringWidth(number)) / 2,
                            (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2);
                    g.setFont(getFont().deriveFont(java.awt.Font.PLAIN, 24f));
                    String label = "准备播放二维码图片";
                    metrics = g.getFontMetrics();
                    g.setColor(new Color(0x637083));
                    g.drawString(label, (getWidth() - metrics.stringWidth(label)) / 2,
                            (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2 + 74);
                } finally {
                    g.dispose();
                }
                if (countdownPaintedAtNanos == 0) {
                    countdownPaintedAtNanos = System.nanoTime();
                }
                return;
            }
            if (image == null) {
                return;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                double scale = Math.min((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight());
                int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
                int x = (getWidth() - width) / 2;
                int y = (getHeight() - height) / 2;
                g.drawImage(image, x, y, width, height, null);
            } finally {
                g.dispose();
            }
            if (shownAtNanos == 0) {
                shownAtNanos = System.nanoTime();
            }
        }
    }
}
