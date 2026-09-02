package org.trigger.opspilot.runbook;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class RunbookTextProcessor {
    private static final int MAX_CHUNK_CHARS = 1_200;
    private static final int CHUNK_OVERLAP_CHARS = 120;

    private RunbookTextProcessor() {
    }

    static List<ChunkDraft> chunk(String markdown) {
        String normalized = normalizeLineEndings(markdown);
        List<ChunkDraft> chunks = new ArrayList<>();
        String heading = "正文";
        StringBuilder section = new StringBuilder();
        for (String line : normalized.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.matches("^#{1,6}\\s+.+")) {
                appendSection(chunks, heading, section.toString());
                heading = trimmed.replaceFirst("^#{1,6}\\s+", "").trim();
                section.setLength(0);
            } else {
                if (!section.isEmpty()) section.append('\n');
                section.append(line);
            }
        }
        appendSection(chunks, heading, section.toString());
        if (chunks.isEmpty() && !normalized.isBlank()) {
            chunks.add(new ChunkDraft(0, "正文", normalized.trim()));
        }
        List<ChunkDraft> indexed = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            ChunkDraft chunk = chunks.get(index);
            indexed.add(new ChunkDraft(index, chunk.heading(), chunk.content()));
        }
        return indexed;
    }

    static List<String> tokenize(String text) {
        String normalized = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        String previousHan = null;
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                flushWord(tokens, word);
                String current = new String(Character.toChars(codePoint));
                tokens.add(current);
                if (previousHan != null) tokens.add(previousHan + current);
                previousHan = current;
            } else if (Character.isLetterOrDigit(codePoint) || codePoint == '_') {
                previousHan = null;
                word.appendCodePoint(codePoint);
            } else {
                previousHan = null;
                flushWord(tokens, word);
            }
        }
        flushWord(tokens, word);
        return tokens;
    }

    static String compact(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static void appendSection(List<ChunkDraft> chunks, String heading, String rawContent) {
        String content = rawContent.trim();
        if (content.isBlank()) return;
        int part = 1;
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(content.length(), start + MAX_CHUNK_CHARS);
            if (end < content.length()) {
                int paragraphBreak = content.lastIndexOf("\n\n", end);
                if (paragraphBreak > start + MAX_CHUNK_CHARS / 2) end = paragraphBreak;
            }
            String piece = content.substring(start, end).trim();
            if (!piece.isBlank()) {
                String partHeading = part == 1 ? heading : heading + "（续 " + part + "）";
                chunks.add(new ChunkDraft(0, clip(partHeading, 255), piece));
            }
            if (end >= content.length()) break;
            start = Math.max(start + 1, end - CHUNK_OVERLAP_CHARS);
            part++;
        }
    }

    private static void flushWord(List<String> tokens, StringBuilder word) {
        if (!word.isEmpty()) {
            tokens.add(word.toString());
            word.setLength(0);
        }
    }

    private static String normalizeLineEndings(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static String clip(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    record ChunkDraft(int index, String heading, String content) {
    }
}
