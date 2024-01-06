package app.grapheneos.logviewer;

import android.annotation.Nullable;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.text.TextUtils;
import android.util.Pair;

import java.util.List;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;

public class ViewModel {
    @Nullable
    final String sourcePackage; // e.g. app that crashed, app that logcat is filtered on
    final String title;
    final String header;
    final String body;

    // editable by the user
    String description = "";

    ViewModel(@Nullable String sourcePackage, String title, String header, String body) {
        this.sourcePackage = sourcePackage;
        this.title = title;
        this.header = header;
        this.body = body;
    }

    List<String> createHeaderLines() {
        List<String> res = Utils.splitLines(header);
        if (res.size() == 1 && res.get(0).isBlank()) {
            return emptyList();
        }
        return res;
    }

    List<String> createBodyLines() {
        return Utils.splitLines(body);
    }

    private Pair<ClipData, Boolean> asClipData(BaseActivity ctx) {
        int sumSize = header.getBytes(UTF_8).length + description.getBytes(UTF_8).length;
        int sumChars = 0;
        int bodyStartIndex = 0;

        List<String> bodyLines = createBodyLines();

        for (int i = bodyLines.size() - 1; i >= 0; --i) {
            String line = bodyLines.get(i);
            sumChars += line.length() + 1;
            sumSize += line.getBytes(UTF_8).length + 1;
            // avoid bumping into binder transaction size limits
            if (sumSize > 200_000) {
                bodyStartIndex = i + 1;
                break;
            }
        }

        var sb = new StringBuilder(sumChars + 1000);
        sb.append("```\n");

        List<String> headerLines = createHeaderLines();

        for (String s : headerLines) {
            sb.append(s);
            sb.append('\n');
        }

        if (headerLines.size() > 1) {
            sb.append('\n');
        }

        if (bodyStartIndex != 0) {
            sb.append("[[TRUNCATED]]\n");
        }

        for (int i = bodyStartIndex, m = bodyLines.size(); i < m; ++i) {
            sb.append(ctx.prepareLineForCopy(bodyLines.get(i)));
            sb.append('\n');
        }

        if (!description.isBlank()) {
            sb.append("\ndescription: ");
            sb.append(description);
            sb.append('\n');
        }

        sb.append("```\n");

        boolean truncated = bodyStartIndex != 0;

        return Pair.create(ClipData.newPlainText(title, sb.toString()), truncated);
    }

    void copyToClipbord(BaseActivity ctx) {
        Pair<ClipData, Boolean> pair = asClipData(ctx);
        var cm = ctx.getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(pair.first);

        Utils.showToast(ctx, ctx.getText(pair.second?
                R.string.copied_to_clipboard_truncated : R.string.copied_to_clipboard));
    }

    static class Snapshot {
        final String title;
        final String text;
        final byte[] textBytes;
        final String fileName;

        static final String MIME_TYPE = "application/octet-stream";

        Snapshot(String title, String text, byte[] textBytes) {
            this.title = title;
            this.text = text;
            this.textBytes = textBytes;
            fileName = TextUtils.trimToSize(title, 200) + ' '
                    + UUID.randomUUID().toString().substring(24) + ".txt";
        }

        static Snapshot create(ViewModel vm) {
            var b = new StringBuilder();

            List<String> headerLines = vm.createHeaderLines();

            for (String l : headerLines) {
                b.append(l);
                b.append('\n');
            }

            if (headerLines.size() > 1) {
                b.append('\n');
            }

            for (String l : vm.createBodyLines()) {
                b.append(l);
                b.append('\n');
            }

            String desc = vm.description;
            if (!desc.isBlank()) {
                b.append("\ndescription: ");
                b.append(desc);
                b.append('\n');
            }

            String text = b.toString();
            byte[] textBytes = text.getBytes(UTF_8);

            return new Snapshot(vm.title, text, textBytes);
        }
    }
}
