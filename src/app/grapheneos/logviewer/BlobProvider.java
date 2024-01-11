package app.grapheneos.logviewer;

import android.annotation.Nullable;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;
import android.util.LruCache;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

// The purpose of this provider is to support sending blobs to other apps as a Uri without writing
// them to storage
public class BlobProvider extends ContentProvider {
    private static final String TAG = BlobProvider.class.getSimpleName();

    @Override
    public boolean onCreate() {
        return true;
    }

    static class Entry {
        final Uri uri;
        // blob is gzipped to reduce memory usage
        final byte[] gzBytes;
        final int size;

        public Entry(Uri uri, byte[] gzBytes, int size) {
            this.uri = uri;
            this.gzBytes = gzBytes;
            this.size = size;
        }
    }

    private static final LruCache<Uri, Entry> entries = new LruCache<>(40 * (1 << 20)) { // 40 MiB
        @Override
        protected int sizeOf(Uri key, Entry value) {
            return value.gzBytes.length;
        }
    };

    // Uri will be valid until our process is stopped or until the backing entry is evicted by new entries
    public static Uri getUri(String blobName, byte[] bytes) {
        var b = new Uri.Builder();
        b.scheme(ContentResolver.SCHEME_CONTENT);
        b.authority(BlobProvider.class.getName());
        b.path(blobName);
        Uri uri = b.build();

        var bos = new ByteArrayOutputStream(bytes.length);
        try (var s = new GZIPOutputStream(bos)) {
            s.write(bytes);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        var entry = new Entry(uri, bos.toByteArray(), bytes.length);
        synchronized (entries) {
            entries.put(uri, entry);
        }
        return uri;
    }

    @Nullable
    private static Entry getEntryForUri(Uri uri) {
        synchronized (entries) {
            return entries.get(uri);
        }
    }

    private static byte[] getEntryBytes(Entry e) {
        try (var s = new GZIPInputStream(new ByteArrayInputStream(e.gzBytes))) {
            return s.readAllBytes();
        } catch (IOException ex) {
            Log.e(TAG, "", ex);
            throw new IllegalStateException();
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Log.d(TAG, "openFile uri " + uri + ", mode " + mode + ", caller " + getCallingPackage());
        Entry entry = getEntryForUri(uri);
        if (entry == null) {
            throw new FileNotFoundException();
        }
        byte[] bytes = getEntryBytes(entry);
        try (SharedMemory mem = SharedMemory.create(null, bytes.length)) {
            ByteBuffer bb = mem.mapReadWrite();
            bb.put(bytes);
            SharedMemory.unmap(bb);
            mem.setProtect(OsConstants.PROT_READ);
            return mem.getFdDup();
        } catch (IOException|ErrnoException e) {
            Log.d(TAG, "", e);
            throw new FileNotFoundException();
        }
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Log.d(TAG, "query " + uri + ", proj " + Arrays.toString(projection) + ", caller " + getCallingPackage());
        Entry entry = getEntryForUri(uri);
        if (entry == null) {
            return null;
        }
        if (projection == null) {
            projection = new String[] {
                  OpenableColumns.DISPLAY_NAME,
                  OpenableColumns.SIZE,
            };
        }
        var c = new MatrixCursor(projection);
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; ++i) {
            String column = projection[i];
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row[i] = uri.getLastPathSegment();
            } else if (OpenableColumns.SIZE.equals(column)) {
                row[i] = Long.valueOf(entry.size);
            }
        }
        c.addRow(row);
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
