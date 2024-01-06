package app.grapheneos.logviewer;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

public class ErrorDialog {

    public static void show(Context ctx, CharSequence title, Throwable ex) {
        var b = new AlertDialog.Builder(ctx);
        b.setTitle(title);
        String msg = Utils.printStackTraceToString(ex);
        b.setMessage(msg);
        b.setPositiveButton(R.string.action_copy, (d, w) -> {
            var cd = ClipData.newPlainText(null, msg);
            var cm = ctx.getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(cd);
        });
        b.show();
    }
}
