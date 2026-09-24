package dev.nofocus.folderplayer;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Launches isolated command-line tools; no third-party Android wrapper is linked. */
final class DownloadRuntime {
    interface Progress { void report(String message); }
    private final Context context;
    private volatile boolean cancelled;
    private volatile Process process;
    private volatile int group;
    private File runtime;
    private File bin;

    DownloadRuntime(Context context) { this.context = context.getApplicationContext(); }

    void checkCancelled() { if (cancelled) throw new CancellationException(); }

    void cancel() {
        cancelled = true;
        // Let the startup handshake reveal the group before killing the parent.
        if (group > 0) killProcess();
    }

    private void killProcess() {
        // The Python launcher creates its own session before printing this PID.
        // Kill the entire group, including FFmpeg and QuickJS, before removing job files.
        int pid = group;
        if (pid > 0) try { Os.kill(-pid, OsConstants.SIGKILL); } catch (Exception ignored) { }
        Process child = process;
        if (child != null) child.destroy();
    }

    synchronized void prepare(Progress progress) throws Exception {
        if (Build.VERSION.SDK_INT < 24) throw new IOException("Song downloads need Android 7 or newer.");
        checkCancelled();
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        if (!new File(nativeDir, "libpython.so").isFile()) throw new IOException("The download tools are missing. Reinstall the app.");
        runtime = new File(context.getNoBackupFilesDir(), "download-tools-" + context.getString(R.string.download_runtime_version));
        bin = new File(runtime, "bin");
        if (!new File(runtime, "ready").isFile()) {
            progress.report("Preparing downloads…");
            removeTree(runtime);
            if (!bin.mkdirs()) throw new IOException("Could not prepare storage.");
            unpack(new File(nativeDir, "libpython.zip.so"), new File(runtime, "python"));
            unpack(new File(nativeDir, "libffmpeg.zip.so"), new File(runtime, "ffmpeg"));
            try (InputStream input = context.getResources().openRawResource(R.raw.yt_dlp);
                 OutputStream output = new FileOutputStream(new File(runtime, "yt-dlp"))) { copy(input, output); }
            checkCancelled();
            new File(runtime, "ready").createNewFile();
        }
        File[] versions = context.getNoBackupFilesDir().listFiles();
        if (versions != null) for (File old : versions) {
            if (old.getName().startsWith("download-tools-") && !old.equals(runtime)) removeTree(old);
        }
        // Native library paths can change after installing an update.
        for (String tool : new String[]{"ffmpeg", "ffprobe"}) {
            File link = new File(bin, tool);
            link.delete();
            Os.symlink(new File(nativeDir, "lib" + tool + ".so").getAbsolutePath(), link.getAbsolutePath());
        }
    }

    int run(List<String> args, Progress output) throws Exception {
        checkCancelled();
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        // This small launcher owns a process group so cancellation also stops transcoding.
        String launcher = "import os,sys,runpy;os.setsid();print('NOFOCUS_PID:'+str(os.getpid()),flush=True);"
                + "sys.argv=sys.argv[1:];runpy.run_path(sys.argv[0],run_name='__main__')";
        List<String> command = new ArrayList<>(Arrays.asList(new File(nativeDir, "libpython.so").getAbsolutePath(),
                "-u", "-c", launcher, new File(runtime, "yt-dlp").getAbsolutePath(),
                "--ffmpeg-location", bin.getAbsolutePath(), "--js-runtimes", "quickjs:" + new File(nativeDir, "libqjs.so")));
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("PYTHONHOME", new File(runtime, "python/usr").getAbsolutePath());
        env.put("PYTHONPATH", "");
        env.put("PYTHONUNBUFFERED", "1");
        env.put("SSL_CERT_FILE", new File(runtime, "python/usr/etc/tls/cert.pem").getAbsolutePath());
        env.put("LD_LIBRARY_PATH", new File(runtime, "python/usr/lib") + ":" + new File(runtime, "ffmpeg/usr/lib"));
        env.put("HOME", runtime.getAbsolutePath());
        env.put("TMPDIR", context.getCacheDir().getAbsolutePath());
        env.put("PATH", bin + ":" + nativeDir + ":" + env.get("PATH"));
        builder.directory(runtime);
        Process child = builder.start();
        process = child;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (group == 0 && line.startsWith("NOFOCUS_PID:")) {
                    group = Integer.parseInt(line.substring(12));
                    if (cancelled) killProcess();
                } else output.report(line);
                if (cancelled && group > 0) killProcess();
            }
            int code = child.waitFor();
            checkCancelled();
            return code;
        } catch (IOException error) {
            checkCancelled();
            throw error;
        } finally {
            killProcess();
            child.waitFor();
            process = null; group = 0;
        }
    }

    private void unpack(File archive, File directory) throws Exception {
        if (!directory.mkdirs()) throw new IOException("Could not prepare download tools.");
        String root = directory.getCanonicalPath() + File.separator;
        List<FileHeader> links = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive)) {
            for (FileHeader header : zip.getFileHeaders()) {
                checkCancelled();
                File target = new File(directory, header.getFileName());
                if (!target.getCanonicalPath().startsWith(root)) throw new IOException("Invalid tool archive path.");
                byte[] attrs = header.getExternalFileAttributes();
                if (attrs != null && attrs.length == 4 && (attrs[3] & 0xF0) == 0xA0) { links.add(header); continue; }
                if (header.isDirectory()) { target.mkdirs(); continue; }
                target.getParentFile().mkdirs();
                try (InputStream input = zip.getInputStream(header); OutputStream output = new FileOutputStream(target)) { copy(input, output); }
            }
            // Resolve symlinks only after regular files exist, and keep every target inside this runtime.
            for (FileHeader header : links) {
                checkCancelled();
                File target = new File(directory, header.getFileName());
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                try (InputStream input = zip.getInputStream(header)) { copy(input, buffer); }
                String value = buffer.toString("UTF-8");
                if (new File(value).isAbsolute() || !new File(target.getParentFile(), value).getCanonicalPath().startsWith(root))
                    throw new IOException("Invalid tool archive link.");
                target.getParentFile().mkdirs();
                Os.symlink(value, target.getAbsolutePath());
            }
        }
    }

    private void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[65536]; int count;
        while ((count = input.read(buffer)) != -1) { checkCancelled(); output.write(buffer, 0, count); }
    }

    static void removeTree(File file) throws IOException {
        try {
            if (OsConstants.S_ISDIR(Os.lstat(file.getAbsolutePath()).st_mode)) {
                File[] children = file.listFiles();
                if (children == null) throw new IOException("Cannot read temporary folder.");
                for (File child : children) removeTree(child);
            }
            if (!file.delete()) throw new IOException("Cannot remove temporary download.");
        } catch (android.system.ErrnoException error) {
            if (error.errno != OsConstants.ENOENT) throw new IOException(error);
        }
    }
}
