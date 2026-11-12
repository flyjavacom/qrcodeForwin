package com.example.qrcode.utils;

import com.example.qrcode.demo.FrameDwellTiming;
import com.example.qrcode.demo.TransferAppSettings;
import com.example.qrcode.demo.TransferOutputPaths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.Assert.*;

public class FourQrCompatibilityTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void legacyPageFormatStillReconstructsTheOriginalBytes() throws Exception {
        byte[] original = new byte[2500];
        new Random(42).nextBytes(original);
        String content = String.format(Locale.ROOT, "%10s", "bin")
                + Base64.getEncoder().encodeToString(original);
        PageCollector collector = new PageCollector();
        String[] pages = splitLegacy(content);
        for (int i = 0; i < pages.length; i++) {
            assertTrue(collector.accept(String.format(Locale.ROOT, "%4d", i) + pages[i], "test-page-" + i));
        }

        Path out = temporaryFolder.newFolder("legacy-receive").toPath();
        Path recovered = java.nio.file.Paths.get(FileConvertUtil.buildFileFromPages(out.toString(),
                collector.getPageContentMap(), collector.getConflictCount()));
        assertArrayEquals(original, Files.readAllBytes(recovered));
    }

    @Test
    public void newReceiverStillReadsLegacySingleQrImages() throws Exception {
        Path qrFolder = temporaryFolder.newFolder("legacy-single-qr").toPath();
        byte[] original = "legacy receiver compatibility".getBytes(StandardCharsets.UTF_8);
        String payload = String.format(Locale.ROOT, "%10s", "txt")
                + Base64.getEncoder().encodeToString(original);
        QRCodeUtil.encode(String.format(Locale.ROOT, "%4d", 0) + payload,
                qrFolder.resolve("qr_code_0.png").toString());

        String recovered = FileConvertUtil.qrCode4ToFile(qrFolder.toString());
        assertArrayEquals(original, Files.readAllBytes(java.nio.file.Paths.get(recovered)));
    }

    @Test
    public void fourQrFramesDecodeIndividuallyAndKeepNumericPageOrder() throws Exception {
        Path input = temporaryFolder.newFolder("qr-input").toPath();
        Path output = temporaryFolder.newFolder("four-frames").toPath();
        for (int page = 0; page < 5; page++) {
            String qrText = String.format(Locale.ROOT, "%4d", page)
                    + String.format(Locale.ROOT, "%10s", "bin")
                    + Base64.getEncoder().encodeToString(("page-" + page).getBytes(StandardCharsets.UTF_8));
            // Intentionally use unpadded numeric names to catch lexical sorting regressions.
            QRCodeUtil.encode(qrText, input.resolve("qr_code_" + page + ".png").toString());
        }
        Files.write(input.resolve("transfer_manifest.txt"),
                new TransferManifest(5, 0, TransferManifest.sha256(new byte[0])).toPayload().getBytes(StandardCharsets.UTF_8));

        FourQrFrameComposer.composeFolder(input.toString(), output.toString(), null);
        List<String> manifestFrame = QRCodeUtil.decodeAll(ImageIO.read(output.resolve("four_qr_frame_000000.png").toFile()));
        List<String> dataFrame = QRCodeUtil.decodeAll(ImageIO.read(output.resolve("four_qr_frame_000001.png").toFile()));
        List<String> lastFrame = QRCodeUtil.decodeAll(ImageIO.read(output.resolve("four_qr_frame_000002.png").toFile()));
        List<String> quadrantFrame = QRCodeUtil.decodeFourQuadrants(
                ImageIO.read(output.resolve("four_qr_frame_000001.png").toFile()));
        assertTrue("first frame must carry the v2 file manifest",
                manifestFrame.stream().anyMatch(text -> text.startsWith(TransferManifest.PREFIX)));
        assertEquals("first data frame should contain four independently decoded QRs", 4, dataFrame.size());
        assertEquals("quadrant fallback should independently parse all four image regions", 4,
                quadrantFrame.size());
        List<String> heavyDataFrame = QRCodeUtil.decodeAllHeavy(
                ImageIO.read(output.resolve("four_qr_frame_000001.png").toFile()));
        assertEquals("targeted heavy decode should inspect every located QR region", 4, heavyDataFrame.size());
        assertEquals("last partial frame should contain one independently decoded QR", 1, lastFrame.size());
        List<Integer> firstPages = parsePages(dataFrame);
        List<Integer> lastPages = parsePages(lastFrame);
        assertEquals(java.util.Arrays.asList(0, 1, 2, 3), firstPages);
        assertEquals(java.util.Collections.singletonList(4), lastPages);
    }

    @Test
    public void sourceFileGeneratesOnlyFourQrFramesAndRoundTripsAsImages() throws Exception {
        Path work = temporaryFolder.newFolder("direct-four-qr").toPath();
        Path source = work.resolve("direct-source.bin");
        byte[] original = new byte[1800];
        new Random(811).nextBytes(original);
        Files.write(source, original);
        Path frames = work.resolve("direct-source_20261112_120000_000");

        FourQrFrameComposer.composeFile(source, frames, null);
        File[] intermediatePages = frames.toFile().listFiles((dir, name) -> name.startsWith("qr_code_"));
        assertEquals("The new sender must not persist intermediate single-code pages", 0, intermediatePages.length);
        String recovered = FileConvertUtil.qrCode4ToFile(frames.toString(), work.resolve("received").toString());
        assertArrayEquals(original, Files.readAllBytes(java.nio.file.Paths.get(recovered)));
        assertTrue(java.nio.file.Paths.get(recovered).getFileName().toString().startsWith("direct-source_"));
    }

    @Test
    public void generatedWorkDirectoriesAreSiblingsOfTheirInputs() throws Exception {
        Path parent = temporaryFolder.newFolder("sibling-output-paths").toPath();
        Path sourceFile = parent.resolve("report.final.bin");
        Path sourceImages = Files.createDirectory(parent.resolve("qr_images"));

        Path fileOutput = TransferOutputPaths.besideFile(sourceFile, "20261112_120000_000");
        Path imageOutput = TransferOutputPaths.besideDirectory(sourceImages, "20261112_120001_000");

        assertEquals(parent, fileOutput.getParent());
        assertEquals("report.final_20261112_120000_000", fileOutput.getFileName().toString());
        assertEquals(parent, imageOutput.getParent());
        assertEquals("qr_images_20261112_120001_000", imageOutput.getFileName().toString());
    }

    @Test
    public void missingPageMessageDoesNotClaimThatTheQrWasNeverInTheVideo() throws Exception {
        java.util.Map<Integer, String> pages = new java.util.HashMap<>();
        pages.put(0, "page-zero");
        pages.put(2, "page-two");
        try {
            FileConvertUtil.buildFileFromPages(temporaryFolder.getRoot().getAbsolutePath(), pages, 0,
                    java.util.Collections.singletonList(1));
            fail("Missing middle page must stop file output");
        } catch (FileConvertUtil.DataIncompleteException expected) {
            assertTrue(expected.getMessage().contains("不能判断二维码没有入镜"));
            assertFalse(expected.getMessage().contains("每一帧都单独强解"));
        }
    }

    @Test
    public void fourQrMissingPageMessageMapsPageToImageAndQuadrant() throws Exception {
        PageCollector collector = new PageCollector();
        collector.markMultiQrFrame();
        collector.accept(new TransferManifest(7, 0, TransferManifest.sha256(new byte[0])).toPayload(),
                "manifest");
        for (int page : new int[]{0, 1, 2, 3, 4, 6}) {
            String payload = String.format(Locale.ROOT, "%10s", "bin")
                    + Base64.getEncoder().encodeToString(new byte[]{(byte) page});
            collector.accept(String.format(Locale.ROOT, "%4d", page) + payload, "page-" + page);
        }

        try {
            FileConvertUtil.buildFileFromPages(temporaryFolder.getRoot().getAbsolutePath(), collector, null);
            fail("Missing page 5 must stop file output");
        } catch (FileConvertUtil.DataIncompleteException expected) {
            assertTrue(expected.getMessage().contains(
                    "第 5 页 → 第 2 张数据图片的右上二维码（含开头清单控制画面时为第 3 个画面）"));
        }
    }

    @Test
    public void fourQrFrameIsAcceptedOnlyWhenEveryExpectedPageIsDecoded() {
        PageCollector collector = new PageCollector();
        collector.accept(new TransferManifest(8, 0, TransferManifest.sha256(new byte[0])).toPayload(),
                "manifest");
        java.util.Map<Integer, double[]> pageTimes = new java.util.HashMap<>();

        // A single-region decode may finish before another worker identifies the four-code layout.
        VideoQrDecoder.acceptFramePages(java.util.Collections.singletonList(testPage(4)),
                collector, pageTimes, 0.5, "early-single-region");
        assertEquals(1, collector.size());

        List<String> partialFrame = new ArrayList<>();
        partialFrame.add(testPage(4));
        partialFrame.add(testPage(5));
        partialFrame.add(testPage(7));
        assertTrue(VideoQrDecoder.acceptFramePages(partialFrame, collector, pageTimes, 1.0, "partial-frame").isEmpty());
        assertTrue("Switching to four-QR mode discards pages prematurely accepted as single QR images",
                collector.hasMultiQrFrame());
        assertTrue("A partial four-QR frame must not contribute any pages to the file", collector.isEmpty());

        List<String> completeFrame = new ArrayList<>();
        for (int page = 4; page < 8; page++) {
            completeFrame.add(testPage(page));
        }
        assertEquals(java.util.Arrays.asList(4, 5, 6, 7),
                VideoQrDecoder.acceptFramePages(completeFrame, collector, pageTimes, 2.0, "complete-frame"));
        assertEquals("A complete four-QR frame contributes all four pages", 4, collector.size());
    }

    @Test
    public void complementaryCandidateFramesAreAggregatedBeforeAtomicCommit() {
        PageCollector collector = new PageCollector();
        collector.accept(new TransferManifest(8, 0, TransferManifest.sha256(new byte[0])).toPayload(),
                "manifest");
        collector.markMultiQrFrame();
        java.util.Map<Integer, java.util.Map<Integer, java.util.Map<String, Integer>>> pending = new java.util.TreeMap<>();
        java.util.Map<Integer, double[]> pageTimes = new java.util.HashMap<>();
        java.util.Set<Integer> targetPages = new java.util.HashSet<>(java.util.Arrays.asList(4, 5, 6, 7));
        List<Integer> foundPages = new ArrayList<>();
        List<Double> foundTimes = new ArrayList<>();

        VideoQrDecoder.accumulateFourQrPages(java.util.Arrays.asList(testPage(4), testPage(5)), pending);
        VideoQrDecoder.commitCompleteFourQrGroups(pending, collector, pageTimes,
                targetPages, foundPages, foundTimes, 1.0, "candidate-a");
        assertTrue("An incomplete combined page group must still be rejected atomically", collector.isEmpty());

        VideoQrDecoder.accumulateFourQrPages(java.util.Arrays.asList(testPage(6), testPage(7)), pending);
        VideoQrDecoder.commitCompleteFourQrGroups(pending, collector, pageTimes,
                targetPages, foundPages, foundTimes, 1.1, "candidate-b");
        assertEquals("Complementary candidates from the same page group should commit together", 4, collector.size());
        assertEquals(java.util.Arrays.asList(4, 5, 6, 7), foundPages);
    }

    @Test
    public void fourQrWindowUsesMajorityVoteAndRejectsTies() {
        PageCollector collector = new PageCollector();
        collector.accept(new TransferManifest(4, 0, TransferManifest.sha256(new byte[0])).toPayload(),
                "manifest");
        collector.markMultiQrFrame();
        java.util.Map<Integer, java.util.Map<Integer, java.util.Map<String, Integer>>> pending =
                new java.util.TreeMap<>();
        java.util.Map<Integer, double[]> pageTimes = new java.util.HashMap<>();
        java.util.Set<Integer> targets = new java.util.HashSet<>(java.util.Arrays.asList(0, 1, 2, 3));
        List<Integer> found = new ArrayList<>();
        List<Double> times = new ArrayList<>();
        List<String> goodFrame = new ArrayList<>();
        for (int page = 0; page < 4; page++) goodFrame.add(testPage(page));
        VideoQrDecoder.accumulateFourQrPages(goodFrame, pending);
        List<String> oneBadRead = new ArrayList<>(goodFrame);
        oneBadRead.set(2, testPage(2, "BAD"));
        VideoQrDecoder.accumulateFourQrPages(oneBadRead, pending);
        VideoQrDecoder.accumulateFourQrPages(goodFrame, pending);
        VideoQrDecoder.commitCompleteFourQrGroups(pending, collector, pageTimes, targets,
                found, times, 1.0, "majority-vote");
        assertEquals("A stable majority across the window should win over one bad read", 4, collector.size());

        PageCollector tieCollector = new PageCollector();
        tieCollector.accept(new TransferManifest(4, 0, TransferManifest.sha256(new byte[0])).toPayload(),
                "manifest");
        tieCollector.markMultiQrFrame();
        java.util.Map<Integer, java.util.Map<Integer, java.util.Map<String, Integer>>> tied =
                new java.util.TreeMap<>();
        VideoQrDecoder.accumulateFourQrPages(goodFrame, tied);
        List<String> otherFrame = new ArrayList<>(goodFrame);
        otherFrame.set(2, testPage(2, "BAD"));
        VideoQrDecoder.accumulateFourQrPages(otherFrame, tied);
        VideoQrDecoder.commitCompleteFourQrGroups(tied, tieCollector, new java.util.HashMap<Integer, double[]>(),
                targets, new ArrayList<Integer>(), new ArrayList<Double>(), 1.0, "tied-vote");
        assertTrue("Ambiguous page contents must reject the entire page group", tieCollector.isEmpty());
    }

    @Test
    public void manifestAllowsShortFirstAndLastPageGroups() {
        assertManifestPageGroupCommits(3, 0, new int[]{0, 1, 2});
        assertManifestPageGroupCommits(5, 4, new int[]{4});
    }

    private void assertManifestPageGroupCommits(int totalPages, int groupStart, int[] pageNumbers) {
        PageCollector collector = new PageCollector();
        collector.accept(new TransferManifest(totalPages, 0, TransferManifest.sha256(new byte[0])).toPayload(),
                "manifest");
        collector.markMultiQrFrame();
        java.util.Map<Integer, java.util.Map<Integer, java.util.Map<String, Integer>>> pending = new java.util.TreeMap<>();
        java.util.Map<Integer, double[]> pageTimes = new java.util.HashMap<>();
        java.util.Set<Integer> targets = new java.util.HashSet<>();
        List<Integer> found = new ArrayList<>();
        List<Double> times = new ArrayList<>();
        List<String> decoded = new ArrayList<>();
        for (int page : pageNumbers) {
            decoded.add(testPage(page));
        }

        VideoQrDecoder.accumulateFourQrPages(decoded, pending);
        VideoQrDecoder.commitCompleteFourQrGroups(pending, collector, pageTimes,
                targets, found, times, 1.0, "edge-page-group");
        assertEquals("The manifest-defined short edge group should commit as a unit",
                pageNumbers.length, collector.size());
        for (int page : pageNumbers) {
            assertTrue("Expected edge page " + page + " in group starting " + groupStart,
                    collector.getPageContentMap().containsKey(page));
        }
    }

    @Test
    public void fourQrVideoRoundTripsAFileUsingTheLegacyPagePayloads() throws Exception {
        Path work = temporaryFolder.newFolder("four-qr-video-e2e").toPath();
        Path source = work.resolve("sample.bin");
        byte[] original = new byte[2500];
        new Random(2026).nextBytes(original);
        Files.write(source, original);

        String legacyQrDirectory = FileConvertUtil.fileToQrCode(source.toString());
        String singlePhotoRecovered = FileConvertUtil.qrCode4ToFile(legacyQrDirectory,
                work.resolve("single-photo-received").toString());
        assertArrayEquals("New single-QR images plus sidecar manifest must preserve the original name", original,
                Files.readAllBytes(java.nio.file.Paths.get(singlePhotoRecovered)));
        assertTrue(java.nio.file.Paths.get(singlePhotoRecovered).getFileName().toString().startsWith("sample_"));
        Path fourFrames = work.resolve("four_qr_frames");
        FourQrFrameComposer.composeFolder(legacyQrDirectory, fourFrames.toString(), null);
        String photoRecovered = FileConvertUtil.qrCode4ToFile(fourFrames.toString(),
                work.resolve("four-photo-received").toString());
        assertArrayEquals("New four-QR still frames must reconstruct a file", original,
                Files.readAllBytes(java.nio.file.Paths.get(photoRecovered)));
        assertTrue(java.nio.file.Paths.get(photoRecovered).getFileName().toString().startsWith("sample_"));
        String video = FFmpegUtil.geneteVideoFromImageDir(fourFrames.toString(), 5.0, "sample");
        assertTrue(java.nio.file.Paths.get(video).getFileName().toString().startsWith("sample_"));
        Path received = work.resolve("received");
        String recovered = VideoQrDecoder.videoToFile(video, received.toString(), VideoQrDecoder.DEFAULT_FPS, null);

        assertArrayEquals(original, Files.readAllBytes(java.nio.file.Paths.get(recovered)));
        assertTrue(java.nio.file.Paths.get(recovered).getFileName().toString().startsWith("sample_"));
    }

    @Test
    public void legacySingleQrVideoStillReconstructsAFile() throws Exception {
        Path work = temporaryFolder.newFolder("legacy-single-video").toPath();
        Path source = work.resolve("legacy.bin");
        byte[] original = new byte[1500];
        new Random(17).nextBytes(original);
        Files.write(source, original);

        String qrDirectory = FileConvertUtil.fileToQrCode(source.toString());
        String video = FFmpegUtil.geneteVideoFromImageDir(qrDirectory);
        String recovered = VideoQrDecoder.videoToFile(video, work.resolve("received").toString(),
                VideoQrDecoder.DEFAULT_FPS, null);
        assertArrayEquals(original, Files.readAllBytes(java.nio.file.Paths.get(recovered)));
        assertTrue(java.nio.file.Paths.get(recovered).getFileName().toString().startsWith("recovered_"));
    }

    @Test
    public void newProtocolSingleQrVideoUsesManifestAndReconstructsAFile() throws Exception {
        Path work = temporaryFolder.newFolder("new-single-video").toPath();
        Path source = work.resolve("single.bin");
        byte[] original = new byte[1500];
        new Random(29).nextBytes(original);
        Files.write(source, original);
        Path legacyQr = java.nio.file.Paths.get(FileConvertUtil.fileToQrCode(source.toString()));
        Path singleFrames = work.resolve("single_qr_frames");
        Files.createDirectories(singleFrames);

        TransferManifest manifest = TransferManifest.parse(new String(
                Files.readAllBytes(legacyQr.resolve("transfer_manifest.txt")), StandardCharsets.UTF_8).trim());
        QRCodeUtil.encode(manifest.toPayload(), singleFrames.resolve("a_manifest.png").toString());
        for (int page = 0; page < manifest.totalPages; page++) {
            Files.copy(legacyQr.resolve("qr_code_" + page + ".png"),
                    singleFrames.resolve(String.format(Locale.ROOT, "b_page_%04d.png", page)));
        }

        String video = FFmpegUtil.geneteVideoFromImageDir(singleFrames.toString(), 5.0);
        String recovered = VideoQrDecoder.videoToFile(video, work.resolve("received").toString(),
                VideoQrDecoder.DEFAULT_FPS, null);
        assertArrayEquals(original, Files.readAllBytes(java.nio.file.Paths.get(recovered)));
        assertTrue(java.nio.file.Paths.get(recovered).getFileName().toString().startsWith("single_"));
    }

    @Test
    public void playerTimingNeverAdvancesBeforeMinimumVisibleDuration() {
        long paintedAt = 1_000_000_000L;
        assertFalse(FrameDwellTiming.hasElapsed(paintedAt, paintedAt + 199_000_000L, 200));
        assertTrue(FrameDwellTiming.hasElapsed(paintedAt, paintedAt + 200_000_000L, 200));
        // A stalled UI advances late; it does not skip or compress the following frame's dwell time.
        assertTrue(FrameDwellTiming.hasElapsed(paintedAt, paintedAt + 900_000_000L, 200));
        assertFalse(FrameDwellTiming.hasElapsed(0, paintedAt + 900_000_000L, 200));
        assertFalse(FrameDwellTiming.hasElapsed(paintedAt + 900_000_000L,
                paintedAt + 1_099_000_000L, 200));
        assertTrue(FrameDwellTiming.hasElapsed(paintedAt + 900_000_000L,
                paintedAt + 1_100_000_000L, 200));
    }

    @Test
    public void playerCountdownRunsThreeFullSecondsBeforePlayback() {
        long startedAt = 2_000_000_000L;
        assertEquals(3, FrameDwellTiming.countdownSecondsRemaining(startedAt, startedAt, 3));
        assertEquals(3, FrameDwellTiming.countdownSecondsRemaining(startedAt,
                startedAt + 1L, 3));
        assertEquals(2, FrameDwellTiming.countdownSecondsRemaining(startedAt,
                startedAt + 1_000_000_000L, 3));
        assertEquals(1, FrameDwellTiming.countdownSecondsRemaining(startedAt,
                startedAt + 2_000_000_000L, 3));
        assertEquals(1, FrameDwellTiming.countdownSecondsRemaining(startedAt,
                startedAt + 2_999_999_999L, 3));
        assertEquals(0, FrameDwellTiming.countdownSecondsRemaining(startedAt,
                startedAt + 3_000_000_000L, 3));
    }

    @Test
    public void playbackSpeedSettingPersistsAndEnforcesMinimum() throws Exception {
        String previousHome = System.getProperty("user.home");
        Path home = temporaryFolder.newFolder("settings-home").toPath();
        try {
            System.setProperty("user.home", home.toString());
            TransferAppSettings settings = TransferAppSettings.load();
            settings.setPlaybackDwellMillis(750);
            settings.save();

            TransferAppSettings reloaded = TransferAppSettings.load();
            assertEquals(750, reloaded.getPlaybackDwellMillis());
            try {
                reloaded.setPlaybackDwellMillis(199);
                fail("Values below the minimum must be rejected");
            } catch (IllegalArgumentException expected) {
                // Keep the minimum at 200 ms to protect the visible dwell time.
            }
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    public void manifestHashMismatchStopsBeforeWritingOutput() throws Exception {
        byte[] original = "intact bytes".getBytes(StandardCharsets.UTF_8);
        byte[] wrong = "different bytes".getBytes(StandardCharsets.UTF_8);
        String page = String.format(Locale.ROOT, "%10s", "txt") + Base64.getEncoder().encodeToString(original);
        PageCollector collector = new PageCollector();
        assertFalse(collector.accept(new TransferManifest(1, original.length, TransferManifest.sha256(wrong)).toPayload(),
                "manifest-qr"));
        assertTrue(collector.accept(String.format(Locale.ROOT, "%4d", 0) + page, "data-qr"));

        try {
            FileConvertUtil.buildFileFromPages(temporaryFolder.getRoot().getAbsolutePath(), collector, null);
            fail("A manifest hash mismatch must prevent file output");
        } catch (FileConvertUtil.DataIncompleteException expected) {
            assertTrue(expected.getMessage().contains("SHA-256"));
        }
    }

    @Test
    public void multiQrReceiveRequiresAFileManifest() throws Exception {
        PageCollector collector = new PageCollector();
        collector.markMultiQrFrame();
        assertTrue(collector.accept(String.format(Locale.ROOT, "%4d", 0)
                + String.format(Locale.ROOT, "%10s", "txt")
                + Base64.getEncoder().encodeToString("data".getBytes(StandardCharsets.UTF_8)), "multi-frame"));
        try {
            FileConvertUtil.buildFileFromPages(temporaryFolder.getRoot().getAbsolutePath(), collector, null);
            fail("Multi-QR transfers without a manifest must be rejected");
        } catch (FileConvertUtil.DataIncompleteException expected) {
            assertTrue(expected.getMessage().contains("SHA-256 清单"));
        }
    }

    @Test
    public void legacySenderRejectsFilesBeyondItsPageNumberLimit() throws Exception {
        Path oversized = temporaryFolder.newFile("too-large.bin").toPath();
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(oversized.toFile(), "rw")) {
            file.setLength(FileConvertUtil.MAX_LEGACY_FILE_BYTES + 1);
        }
        try {
            FileConvertUtil.fileToQrCode(oversized.toString());
            fail("Legacy format must reject a file that exceeds its documented limit");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("7 MiB"));
        }
    }

    private static String[] splitLegacy(String content) {
        int size = PageCollector.SEGMENT_DATA_SIZE;
        int count = (content.length() + size - 1) / size;
        String[] pages = new String[count];
        for (int i = 0; i < count; i++) {
            int start = i * size;
            pages[i] = content.substring(start, Math.min(content.length(), start + size));
        }
        return pages;
    }

    private static String testPage(int page) {
        return testPage(page, new byte[]{(byte) page});
    }

    private static String testPage(int page, String content) {
        return testPage(page, content.getBytes(StandardCharsets.UTF_8));
    }

    private static String testPage(int page, byte[] bytes) {
        String payload = String.format(Locale.ROOT, "%10s", "bin")
                + Base64.getEncoder().encodeToString(bytes);
        return String.format(Locale.ROOT, "%4d", page) + payload;
    }

    private static List<Integer> parsePages(List<String> pages) {
        List<Integer> result = new ArrayList<>();
        for (String page : pages) {
            result.add(PageCollector.parsePage(page));
        }
        java.util.Collections.sort(result);
        return result;
    }
}
