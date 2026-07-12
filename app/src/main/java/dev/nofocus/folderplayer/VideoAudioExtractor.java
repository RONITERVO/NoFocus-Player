package dev.nofocus.folderplayer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Locale;

public final class VideoAudioExtractor {
    public static final String OUTPUT_FOLDER_NAME = "NoFocus extracted audio";

    private static final int MAX_RECURSION_DEPTH = 48;
    private static final int DEFAULT_BUFFER_SIZE = 1024 * 1024;
    private static final int MAX_NOTES = 8;

    private final Context context;
    private final ContentResolver resolver;

    public VideoAudioExtractor(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = context.getContentResolver();
    }

    public Result extract(String tree, Callback callback) {
        Result result = new Result();
        if (Build.VERSION.SDK_INT < 26) {
            result.failed++;
            result.addNote("Audio extraction needs Android 8.0+ for writing SAF files.");
            return result;
        }
        if (tree == null || tree.trim().isEmpty()) {
            result.failed++;
            result.addNote("Choose a folder first.");
            return result;
        }

        Uri treeUri = Uri.parse(tree);
        String rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId);

        try {
            callback.onStatus("Preparing output folder...");
            DocumentRef outputRoot = ensureDirectory(rootDocumentUri, OUTPUT_FOLDER_NAME);

            ArrayList<VideoItem> videos = new ArrayList<>();
            scanVideos(treeUri, rootDocumentId, outputRoot.documentId, "", videos, 0);
            result.total = videos.size();
            if (videos.isEmpty()) {
                result.addNote("No supported video files found in the selected folder.");
                return result;
            }

            for (int i = 0; i < videos.size(); i++) {
                VideoItem video = videos.get(i);
                callback.onStatus("Extracting " + (i + 1) + " / " + videos.size() + ": " + video.name);
                try {
                    extractOne(treeUri, outputRoot, video, result);
                } catch (Exception e) {
                    result.failed++;
                    result.addNote(video.name + ": " + cleanMessage(e));
                }
            }
        } catch (Exception e) {
            result.failed++;
            result.addNote(cleanMessage(e));
        }
        return result;
    }

    private void extractOne(Uri treeUri, DocumentRef outputRoot, VideoItem video, Result result) throws IOException {
        OutputSpec spec = inspectSource(video.uri);
        if (spec == null) {
            result.skipped++;
            result.addNote(video.name + ": no supported audio track.");
            return;
        }

        DocumentRef outputDir = ensureDirectoryPath(outputRoot, video.relativeDir);
        String outputName = baseName(video.name) + "." + spec.extension;
        DocumentRef existingOutput = findChild(outputDir.uri, outputName);
        if (existingOutput != null) {
            if (existingOutput.size != 0) {
                result.alreadyExists++;
                return;
            }
            DocumentsContract.deleteDocument(resolver, existingOutput.uri);
        }

        Uri outputUri = DocumentsContract.createDocument(resolver, outputDir.uri, spec.mimeType, outputName);
        if (outputUri == null) {
            throw new IOException("Could not create " + outputName);
        }

        boolean completed = false;
        try {
            remuxAudio(video.uri, outputUri, spec.outputFormat);
            completed = true;
            result.extracted++;
        } finally {
            if (!completed) {
                try {
                    DocumentsContract.deleteDocument(resolver, outputUri);
                } catch (Exception ignored) {
                    // Keep the original error.
                }
            }
        }
    }

    private OutputSpec inspectSource(Uri sourceUri) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, sourceUri, null);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                OutputSpec spec = outputSpecForAudioMime(mime);
                if (spec != null) {
                    return spec;
                }
            }
            return null;
        } finally {
            extractor.release();
        }
    }

    private void remuxAudio(Uri sourceUri, Uri outputUri, int outputFormat) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        File tempFile = null;
        boolean muxerStarted = false;
        boolean wroteSample = false;

        try {
            extractor.setDataSource(context, sourceUri, null);
            int sourceTrack = findSupportedAudioTrack(extractor);
            if (sourceTrack < 0) {
                throw new IOException("No supported audio track.");
            }

            MediaFormat sourceFormat = extractor.getTrackFormat(sourceTrack);
            extractor.selectTrack(sourceTrack);

            tempFile = File.createTempFile("nofocus-audio-", ".tmp", context.getCacheDir());
            muxer = new MediaMuxer(tempFile.getAbsolutePath(), outputFormat);
            int muxerTrack = muxer.addTrack(sourceFormat);
            muxer.start();
            muxerStarted = true;

            int bufferSize = DEFAULT_BUFFER_SIZE;
            if (sourceFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                bufferSize = Math.max(bufferSize, sourceFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            }
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            while (true) {
                int trackIndex = extractor.getSampleTrackIndex();
                if (trackIndex < 0) {
                    break;
                }
                if (trackIndex != sourceTrack) {
                    extractor.advance();
                    continue;
                }

                buffer.clear();
                int sampleSize = extractor.readSampleData(buffer, 0);
                if (sampleSize < 0) {
                    break;
                }

                int extractorFlags = extractor.getSampleFlags();
                int codecFlags = (extractorFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                bufferInfo.set(0, sampleSize, extractor.getSampleTime(), codecFlags);
                muxer.writeSampleData(muxerTrack, buffer, bufferInfo);
                wroteSample = true;
                extractor.advance();
            }

            if (!wroteSample) {
                throw new IOException("Audio track had no samples.");
            }
            muxer.stop();
            muxerStarted = false;
            copyFileToOutput(tempFile, outputUri);
        } finally {
            if (muxer != null) {
                try {
                    if (muxerStarted) {
                        muxer.stop();
                    }
                } catch (Exception ignored) {
                    // Keep any original failure.
                }
                try {
                    muxer.release();
                } catch (Exception ignored) {
                    // Already released.
                }
            }
            extractor.release();
            if (tempFile != null && tempFile.exists() && !tempFile.delete()) {
                tempFile.deleteOnExit();
            }
        }
    }

    private void copyFileToOutput(File source, Uri outputUri) throws IOException {
        FileInputStream input = null;
        OutputStream output = null;
        try {
            input = new FileInputStream(source);
            output = resolver.openOutputStream(outputUri, "w");
            if (output == null) {
                throw new IOException("Could not open output stream.");
            }
            byte[] buffer = new byte[64 * 1024];
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                output.write(buffer, 0, read);
            }
            output.flush();
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Exception ignored) {
                    // Already closed.
                }
            }
            if (output != null) {
                try {
                    output.close();
                } catch (Exception ignored) {
                    // Already closed.
                }
            }
        }
    }

    private int findSupportedAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (outputSpecForAudioMime(mime) != null) {
                return i;
            }
        }
        return -1;
    }

    private OutputSpec outputSpecForAudioMime(String mime) {
        if (mime == null) {
            return null;
        }
        String lower = mime.toLowerCase(Locale.US);
        if ("audio/mp4a-latm".equals(lower) || "audio/aac".equals(lower) || "audio/mpeg".equals(lower)) {
            return new OutputSpec(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "m4a", "audio/mp4");
        }
        if ("audio/opus".equals(lower) || "audio/vorbis".equals(lower)) {
            return new OutputSpec(MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, "webm", "audio/webm");
        }
        if ("audio/3gpp".equals(lower) || "audio/amr".equals(lower) || "audio/amr-wb".equals(lower)) {
            return new OutputSpec(MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP, "3gp", "audio/3gpp");
        }
        return null;
    }

    private void scanVideos(Uri treeUri, String parentDocumentId, String outputDocumentId, String relativeDir, ArrayList<VideoItem> out, int depth) {
        if (depth > MAX_RECURSION_DEPTH) {
            return;
        }

        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId);
        Cursor cursor = null;
        try {
            cursor = resolver.query(childrenUri, documentProjection(), null, null, null);
            if (cursor == null) {
                return;
            }
            int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);

            while (cursor.moveToNext()) {
                String documentId = idColumn >= 0 ? cursor.getString(idColumn) : null;
                if (documentId == null || documentId.equals(outputDocumentId)) {
                    continue;
                }
                String name = nameColumn >= 0 ? cursor.getString(nameColumn) : documentId;
                String mime = mimeColumn >= 0 ? cursor.getString(mimeColumn) : null;

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    scanVideos(treeUri, documentId, outputDocumentId, relativeDir + sanitizePathPart(name) + "/", out, depth + 1);
                } else if (isSupportedVideo(name, mime)) {
                    Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                    out.add(new VideoItem(documentUri, name == null ? documentId : name, relativeDir));
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private DocumentRef ensureDirectoryPath(DocumentRef root, String relativeDir) throws IOException {
        DocumentRef current = root;
        if (relativeDir == null || relativeDir.trim().isEmpty()) {
            return current;
        }

        String[] parts = relativeDir.split("/");
        for (String part : parts) {
            if (part.trim().isEmpty()) {
                continue;
            }
            current = ensureDirectory(current.uri, part);
        }
        return current;
    }

    private DocumentRef ensureDirectory(Uri parentUri, String name) throws IOException {
        DocumentRef existing = findChild(parentUri, name);
        if (existing != null) {
            if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(existing.mimeType)) {
                throw new IOException(name + " exists but is not a folder.");
            }
            return existing;
        }

        Uri created = DocumentsContract.createDocument(resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (created == null) {
            throw new IOException("Could not create folder " + name);
        }
        String documentId = DocumentsContract.getDocumentId(created);
        return new DocumentRef(created, documentId, name, DocumentsContract.Document.MIME_TYPE_DIR);
    }

    private DocumentRef findChild(Uri parentUri, String displayName) {
        String parentDocumentId = DocumentsContract.getDocumentId(parentUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, parentDocumentId);
        Cursor cursor = null;
        try {
            cursor = resolver.query(childrenUri, documentProjection(), null, null, null);
            if (cursor == null) {
                return null;
            }
            int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);

            while (cursor.moveToNext()) {
                String childName = nameColumn >= 0 ? cursor.getString(nameColumn) : null;
                if (!displayName.equals(childName)) {
                    continue;
                }
                String documentId = idColumn >= 0 ? cursor.getString(idColumn) : null;
                if (documentId == null) {
                    return null;
                }
                String mime = mimeColumn >= 0 ? cursor.getString(mimeColumn) : null;
                Uri childUri = DocumentsContract.buildDocumentUriUsingTree(parentUri, documentId);
                long size = sizeColumn >= 0 && !cursor.isNull(sizeColumn) ? cursor.getLong(sizeColumn) : -1;
                return new DocumentRef(childUri, documentId, childName, mime, size);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    private String[] documentProjection() {
        return new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
    }

    private boolean isSupportedVideo(String name, String mime) {
        if (mime != null && mime.toLowerCase(Locale.US).startsWith("video/")) {
            return true;
        }
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".mp4")
                || lower.endsWith(".m4v")
                || lower.endsWith(".mkv")
                || lower.endsWith(".webm")
                || lower.endsWith(".mov")
                || lower.endsWith(".3gp")
                || lower.endsWith(".3gpp");
    }

    private String baseName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "audio";
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return name;
        }
        return name.substring(0, dot);
    }

    private String sanitizePathPart(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "folder";
        }
        return name.replace("/", "_").replace("\\", "_");
    }

    private String cleanMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }
        return e.getClass().getSimpleName() + ": " + message;
    }

    public interface Callback {
        void onStatus(String status);
    }

    public static final class Result {
        public int total;
        public int extracted;
        public int alreadyExists;
        public int skipped;
        public int failed;
        public final ArrayList<String> notes = new ArrayList<>();

        void addNote(String note) {
            if (note != null && notes.size() < MAX_NOTES) {
                notes.add(note);
            }
        }

        public boolean hasPlayableOutput() {
            return extracted > 0 || alreadyExists > 0;
        }

        public String summary() {
            return "Videos: " + total
                    + ", extracted: " + extracted
                    + ", already there: " + alreadyExists
                    + ", skipped: " + skipped
                    + ", failed: " + failed;
        }

        public String detailText() {
            StringBuilder builder = new StringBuilder(summary());
            builder.append("\nOutput folder: ").append(OUTPUT_FOLDER_NAME);
            if (!notes.isEmpty()) {
                builder.append("\n");
                for (int i = 0; i < notes.size(); i++) {
                    if (i > 0) {
                        builder.append("\n");
                    }
                    builder.append("- ").append(notes.get(i));
                }
            }
            return builder.toString();
        }
    }

    private static final class VideoItem {
        final Uri uri;
        final String name;
        final String relativeDir;

        VideoItem(Uri uri, String name, String relativeDir) {
            this.uri = uri;
            this.name = name;
            this.relativeDir = relativeDir == null ? "" : relativeDir;
        }
    }

    private static final class DocumentRef {
        final Uri uri;
        final String documentId;
        final String name;
        final String mimeType;
        final long size;

        DocumentRef(Uri uri, String documentId, String name, String mimeType) {
            this(uri, documentId, name, mimeType, -1);
        }

        DocumentRef(Uri uri, String documentId, String name, String mimeType, long size) {
            this.uri = uri;
            this.documentId = documentId;
            this.name = name;
            this.mimeType = mimeType;
            this.size = size;
        }
    }

    private static final class OutputSpec {
        final int outputFormat;
        final String extension;
        final String mimeType;

        OutputSpec(int outputFormat, String extension, String mimeType) {
            this.outputFormat = outputFormat;
            this.extension = extension;
            this.mimeType = mimeType;
        }
    }
}
