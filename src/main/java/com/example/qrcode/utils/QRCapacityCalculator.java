package com.example.qrcode.utils;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.HashMap;
import java.util.Map;

/**
 * 没有使用，纯粹为了 展示二维码 大小跟 保存内容大小 和纠错级别的关系
 */
public class QRCapacityCalculator {

    public static void main(String[] args) {
        // 定义不同的版本和纠错级别
        int[] versions = {1, 10, 20, 30, 40};
        ErrorCorrectionLevel[] errorLevels = {
                ErrorCorrectionLevel.L,
                ErrorCorrectionLevel.M,
                ErrorCorrectionLevel.Q,
                ErrorCorrectionLevel.H
        };

        for (int version : versions) {
            for (ErrorCorrectionLevel errorLevel : errorLevels) {
                calculateCapacity(version, errorLevel);
            }
        }
    }

    private static void calculateCapacity(int version, ErrorCorrectionLevel errorLevel) {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.ERROR_CORRECTION, errorLevel);

        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        try {
            String  data = "hello word";
            BitMatrix bitMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, 1, 1, hints);
            int totalBits = bitMatrix.getWidth() * bitMatrix.getHeight();
            int eccBits = getECCBits(bitMatrix, version, errorLevel);
            int dataBits = totalBits - eccBits;

            System.out.printf("Version: %d, Error Level: %s ", version, errorLevel);
            System.out.printf("Total Bits: %d, ECC Bits: %d, Data Bits: %d ", totalBits, eccBits, dataBits);
            System.out.printf("Max Numeric Capacity: %d digits ", getMaxNumericCapacity(dataBits));
            System.out.printf("Max Alphanumeric Capacity: %d characters ", getMaxAlphanumericCapacity(dataBits));
            System.out.printf("Max Byte/Binary Capacity: %d bytes ", getMaxByteBinaryCapacity(dataBits));
            System.out.printf("Max Kanji/Kanji/Hanzi Capacity: %d characters ", getMaxKanjiCapacity(dataBits));
            System.out.println("----------------------------------------");
        } catch (WriterException e) {
            System.err.println("Error calculating capacity: " + e.getMessage());
        }
    }

    private static int getECCBits(BitMatrix bitMatrix, int version, ErrorCorrectionLevel errorLevel) {
        // 根据版本和纠错级别计算ECC位数
        // 这里简化处理，实际需要根据具体算法计算
        switch (errorLevel) {
            case L:
                return (int) (bitMatrix.getWidth() * bitMatrix.getHeight() * 0.07);
            case M:
                return (int) (bitMatrix.getWidth() * bitMatrix.getHeight() * 0.15);
            case Q:
                return (int) (bitMatrix.getWidth() * bitMatrix.getHeight() * 0.25);
            case H:
                return (int) (bitMatrix.getWidth() * bitMatrix.getHeight() * 0.30);
            default:
                return 0;
        }
    }

    private static int getMaxNumericCapacity(int dataBits) {
        return (dataBits * 10) / 11;
    }

    private static int getMaxAlphanumericCapacity(int dataBits) {
        return (dataBits * 11) / 13;
    }

    private static int getMaxByteBinaryCapacity(int dataBits) {
        return dataBits / 8;
    }

    private static int getMaxKanjiCapacity(int dataBits) {
        return (dataBits * 13) / 26;
    }
}