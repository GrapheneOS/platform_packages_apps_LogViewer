package app.grapheneos.logviewer;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

public class Utils {

    public static String printStackTraceToString(Throwable t) {
        var baos = new ByteArrayOutputStream(1000);
        t.printStackTrace(new PrintStream(baos));
        return baos.toString();
    }

    public static CharSequence loadAppLabel(Context ctx, String pkgName) {
        PackageManager pm = ctx.getPackageManager();

        ApplicationInfo ai;
        try {
            ai = pm.getApplicationInfo(pkgName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return pkgName;
        }

        return ai.loadLabel(pm);
    }

    @Nullable
    public static String getInstallingPackage(Context ctx, String pkgName) {
        PackageManager pm = ctx.getPackageManager();
        InstallSourceInfo isi;
        try {
            isi = pm.getInstallSourceInfo(pkgName);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        return isi.getInstallingPackageName();
    }

    public static List<String> splitLines(String s) {
        return Arrays.asList(s.split("\n"));
    }

    public static void showToast(Context ctx, CharSequence text) {
        Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
    }
}
