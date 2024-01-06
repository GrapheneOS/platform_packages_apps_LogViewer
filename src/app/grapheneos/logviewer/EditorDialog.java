package app.grapheneos.logviewer;

import android.annotation.Nullable;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.InputFilter;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.util.function.Consumer;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

public class EditorDialog {

    public static void show(BaseActivity ctx,
                            boolean singleLine, CharSequence title,
                            @Nullable CharSequence initialText, @Nullable CharSequence hint,
                            Consumer<Editable> resultCallback) {
        var ed = new EditText(ctx);
        if (singleLine) {
            ed.setSingleLine(true);
            ed.setImeOptions(EditorInfo.IME_ACTION_DONE);
        }
        if (initialText != null) {
            ed.setText(initialText);
        }
        if (hint != null) {
            ed.setHint(hint);
        }

        ed.setFilters(new InputFilter[] { new InputFilter.LengthFilter(10_000) });

        var layout = new FrameLayout(ctx);
        var lp = new ViewGroup.MarginLayoutParams(MATCH_PARENT, WRAP_CONTENT);
        int padTb = ctx.dpToPx(16);
        int padLr = ctx.dpToPx(48);
        lp.setMargins(padLr, padTb, padLr, padTb);
        layout.addView(ed, lp);
        ed.requestFocus();

        var b = new AlertDialog.Builder(ctx);
        b.setTitle(title);
        b.setView(layout);
        b.setPositiveButton(R.string.action_apply, (d, w) -> {
            resultCallback.accept(ed.getText());
            d.dismiss();
        });

        AlertDialog d = b.create();

        if (singleLine) {
            ed.setOnEditorActionListener((v, actionId, ev) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    resultCallback.accept(ed.getText());
                    d.dismiss();
                    return true;
                }
                return false;
            });
        }

        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }
}
