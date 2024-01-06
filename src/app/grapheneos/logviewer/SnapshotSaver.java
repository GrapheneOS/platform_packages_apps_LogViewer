package app.grapheneos.logviewer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static app.grapheneos.logviewer.Utils.showToast;

class SnapshotSaver {
    static final int MIN_REQUEST_CODE = 1000;
    private static int activityRequestCodeSrc = MIN_REQUEST_CODE;
    private static LruCache<Integer, ViewModel.Snapshot> pendingSnapshots = new LruCache(5);

    static void start(BaseActivity ctx) {
        ViewModel.Snapshot s = ViewModel.Snapshot.create(ctx.viewModel);
        var i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.setType(ViewModel.Snapshot.MIME_TYPE);
        i.putExtra(Intent.EXTRA_TITLE, s.fileName);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        int reqCode = activityRequestCodeSrc++;
        pendingSnapshots.put(Integer.valueOf(reqCode), s);
        ctx.startActivityForResult(i, reqCode);
    }

    static void onActivityResult(BaseActivity ctx, int requestCode, Uri uri) {
        ViewModel.Snapshot s = pendingSnapshots.remove(Integer.valueOf(requestCode));
        if (s == null) {
            return;
        }
        bgExecutor.execute(() -> writeToUri(ctx, s, uri));
    }

    private static Executor bgExecutor = Executors.newCachedThreadPool();

    static void writeToUri(Context ctx, ViewModel.Snapshot s, Uri uri) {
        ContentResolver cr = ctx.getContentResolver();
        ParcelFileDescriptor pfd;
        try {
            pfd = cr.openFileDescriptor(uri, "w");
        } catch (Exception e) {
            ctx.getMainExecutor().execute(() ->
                    ErrorDialog.show(ctx, ctx.getText(R.string.toast_unable_to_open_file), e));
            return;
        }

        if (pfd == null) {
            ctx.getMainExecutor().execute(() ->
                    showToast(ctx, ctx.getText(R.string.toast_unable_to_open_file)));
            return;
        }

        try (var os = new ParcelFileDescriptor.AutoCloseOutputStream(pfd)) {
            os.write(s.textBytes);
        } catch (Exception e) {
            ctx.getMainExecutor().execute(() ->
                    ErrorDialog.show(ctx, ctx.getText(R.string.unable_to_save_file), e));
        }

        ctx.getMainExecutor().execute(() ->
                showToast(ctx, ctx.getString(R.string.toast_saved, s.fileName)));
    }
}
