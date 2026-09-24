package dev.nofocus.folderplayer;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Read-only access to a single finished song, granted explicitly by Save/Share. */
public final class SongFileProvider extends ContentProvider {
    static Uri uri(Context context, String name) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".songs").appendPath(name).build();
    }
    private File file(Uri uri) throws FileNotFoundException {
        if (uri.getPathSegments().size() != 1) throw new FileNotFoundException();
        File directory = SongDownloadService.directory(getContext());
        File file = new File(directory, uri.getLastPathSegment());
        try {
            if (!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()) || !file.isFile()) throw new FileNotFoundException();
        } catch (IOException error) { throw new FileNotFoundException(); }
        return file;
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        return SongDownloadSpec.mime(name == null ? "mp3" : name.substring(name.lastIndexOf('.') + 1));
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String order) {
        try {
            File file = file(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : columns) row.add(OpenableColumns.DISPLAY_NAME.equals(column) ? file.getName() : OpenableColumns.SIZE.equals(column) ? file.length() : null);
            return cursor;
        } catch (FileNotFoundException error) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
}
