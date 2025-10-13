package com.example.qrcode.utils;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;

public class ExcelBase64Converter {

    // Method to convert XLS file to Base64 string
    public static String convertXlsToBase64(String filePath) throws IOException {
        byte[] bytes = Files.readAllBytes(Paths.get(filePath));
        return Base64.getEncoder().encodeToString(bytes);
    }

    // Method to convert Base64 string to XLS file
    public static void convertBase64ToXls(String base64String, String filePath) throws IOException {
        byte[] decodedBytes = Base64.getDecoder().decode(base64String);
        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            fos.write(decodedBytes);
        }
    }

    public static void main(String[] args) {
        try {
           // String xlsFilePath = "example.xls";
            String xlsFilePath = "C:\\soft\\tesseract-ocr\\text\\1111.xls";
            String base64FilePath = "C:\\soft\\tesseract-ocr\\text\\1111_base64.txt";
           // String outputXlsFilePath = "output_example.xls";
            String outputXlsFilePath = "C:\\soft\\tesseract-ocr\\text\\out-base64toxls111.xls";

            // Convert XLS file to Base64 string
            String base64String = convertXlsToBase64(xlsFilePath);
            System.out.println("Base64 String:");
            System.out.println(base64String);

            // Save Base64 string to a file (optional)
            Files.write(Paths.get(base64FilePath), base64String.getBytes());

            // Convert Base64 string back to XLS file
            convertBase64ToXls(base64String, outputXlsFilePath);
            System.out.println("Converted XLS file saved as " + outputXlsFilePath);

        } catch (IOException e) {
            System.err.println(e.getMessage());
        }
    }
}


