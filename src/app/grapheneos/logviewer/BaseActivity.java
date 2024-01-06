package app.grapheneos.logviewer;

import android.annotation.Nullable;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.text.Editable;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static app.grapheneos.logviewer.Utils.splitLines;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Collections.emptyList;

public abstract class BaseActivity extends Activity {
    // Activity instanceId, used to locate viewModel
    private ParcelUuid instanceId;
    protected ViewModel viewModel;

    @Nullable
    abstract ViewModel createViewModel();

    private static final LruCache<ParcelUuid, ViewModel> viewModels = new LruCache<>(100 * (1 << 20)) {
        @Override
        protected int sizeOf(ParcelUuid key, ViewModel value) {
            int multiplier = 2; // at most 2 bytes per char
            return (value.header.length() + value.body.length() + value.description.length()) * multiplier;
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ParcelUuid instanceId;
        ViewModel vm = null;
        if (savedInstanceState != null) {
            instanceId = savedInstanceState.getParcelable(KEY_INSTANCE_ID, ParcelUuid.class);
            vm = viewModels.get(instanceId);
        } else {
            instanceId = new ParcelUuid(UUID.randomUUID());
        }

        this.instanceId = instanceId;

        if (vm == null) {
            vm = createViewModel();
        }

        if (vm != null) {
            viewModels.put(instanceId, vm);
        } else {
            finishAndRemoveTask();
            return;
        }

        this.viewModel = vm;

        setTitle(vm.title);
        final Context ctx = this;

        this.listAdapter = new AListAdapter();
        updateListItems();
        {
            // RecyclerView doesn't support programmatic instantion properly, e.g. scrollbar would
            // be broken
            var v = (RecyclerView) getLayoutInflater().inflate(R.layout.recycler_view, null);
            this.listView = v;
            v.setAdapter(listAdapter);
            v.setLayoutManager(new LinearLayoutManager(ctx));
            // needed for state restoration
            v.setId(1);
        }
        if (savedInstanceState == null && shouldScrollToBottom()) {
            scrollToBottom();
        }

        // pinch-to-zoom for list items
        listScaleGestureDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.OnScaleGestureListener() {
            private float startSizeSp;
            private float prevInvalidateSizeSp;

            @Override
            public boolean onScaleBegin(ScaleGestureDetector sgd) {
                prevInvalidateSizeSp = startSizeSp = fontSizeSp;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector sgd) {
                fontSizeSp = clampFontSizeSp(startSizeSp * sgd.getScaleFactor());
                if (Math.abs(prevInvalidateSizeSp - fontSizeSp) > 0.05f) {
                    prevInvalidateSizeSp = fontSizeSp;
                    listAdapter.notifyDataSetChanged();
                }
                return false;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector sgd) {
                listAdapter.notifyDataSetChanged();
            }
        });
        listScaleGestureDetector.setQuickScaleEnabled(false);

        LinearLayout btnLayout;
        {
            var l = new LinearLayout(ctx);
            l.setOrientation(LinearLayout.HORIZONTAL);
            l.setGravity(Gravity.CENTER);
            btnLayout = l;
        }
        {
            var b = new Button(ctx);
            b.setText(R.string.action_copy);
            b.setOnClickListener(v -> viewModel.copyToClipbord(this));
            btnLayout.addView(b);
        }
        if (shouldShowReportButton()) {
            var b = new Button(ctx);
            b.setText(R.string.action_report);
            b.setOnClickListener(v -> {
                viewModel.copyToClipbord(this);
                String url = "https://github.com/GrapheneOS/os-issue-tracker/issues";
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            });
            btnLayout.addView(b);
        } else {
            var b = new Button(ctx);
            b.setText(R.string.action_share);
            b.setOnClickListener(v -> onActionShare());
            btnLayout.addView(b);
        }
        for (BottomButton bb : createExtraBottomButtons()) {
            var b = new Button(ctx);
            b.setText(bb.text);
            b.setOnClickListener(bb.action);
            btnLayout.addView(b);
        }
        {
            var l = new LinearLayout(ctx);
            l.setOrientation(LinearLayout.VERTICAL);
            var listLp = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f);
            l.addView(listView, listLp);
            l.addView(btnLayout);
            int pad = dpToPx(16);
            l.setPadding(pad, pad, pad, pad);
            setContentView(l);
        }
    }

    private static final String KEY_INSTANCE_ID = "instance_id";

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(KEY_INSTANCE_ID, instanceId);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (isFinishing()) {
            ParcelUuid id = this.instanceId;
            if (id != null) {
                viewModels.remove(id);
            }
        }
    }

    private final ArrayList<String> listItems = new ArrayList<>();
    private AListAdapter listAdapter;
    private RecyclerView listView;

    private float fontSizeSp = getInitialFontSizeSp();
    private ScaleGestureDetector listScaleGestureDetector;

    void updateListItems() {
        ArrayList<String> l = listItems;
        l.clear();

        ViewModel m = viewModel;
        List<String> headerLines = m.createHeaderLines();
        l.addAll(headerLines);
        if (!headerLines.isEmpty()) {
            l.add("");
        }
        l.addAll(m.createBodyLines());

        String desc = m.description;
        if (!desc.isBlank()) {
            l.add("");
            l.addAll(splitLines("description: " + desc));
        }
        listAdapter.notifyDataSetChanged();
    }

    void scrollToBottom() {
        listView.scrollToPosition(listItems.size() - 1);
    }

    class AListAdapter extends RecyclerView.Adapter<AListAdapter.VHolder> {
        static class VHolder extends RecyclerView.ViewHolder {
            final TextView textView;

            VHolder(TextView item) {
                super(item);
                this.textView = item;
            }
        }

        @Override
        public VHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Context ctx = parent.getContext();
            var v = new TextView(ctx);
            v.setTypeface(Typeface.MONOSPACE);
            boolean isNight = ctx.getResources().getConfiguration().isNightModeActive();
            // default color is too light
            int color = isNight ?  0xff_d0_d0_d0 : 0xff_00_00_00;
            v.setTextColor(color);
            return new VHolder(v);
        }

        @Override
        public void onBindViewHolder(VHolder holder, int pos) {
            TextView v = holder.textView;
            v.setTextSize(fontSizeSp);
            v.setText(prepareLineForDisplay(listItems.get(pos)));
        }

        @Override
        public int getItemCount() {
            return listItems.size();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        listScaleGestureDetector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    void onActionShare() {
        ViewModel.Snapshot s = ViewModel.Snapshot.create(viewModel);
        Uri uri = BlobProvider.getUri(s.fileName, s.textBytes);
        var i = new Intent(Intent.ACTION_SEND);
        i.putExtra(Intent.EXTRA_SUBJECT, s.fileName);
        i.setType(ViewModel.Snapshot.MIME_TYPE);
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, s.fileName));
    }

    int dpToPx(int dp) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, (float) dp, dm);
    }

    boolean shouldScrollToBottom() {
        return false;
    }

    private static float clampFontSizeSp(float v) {
        return min(24f, max(2f, v));
    }

    float getInitialFontSizeSp() {
        return 12f;
    }

    boolean shouldShowReportButton() {
        return false;
    }

    String prepareLineForCopy(String s) {
        return s;
    }

    String prepareLineForDisplay(String s) {
        return s;
    }

    static class BottomButton {
        final CharSequence text;
        final View.OnClickListener action;

        BottomButton(CharSequence text, View.OnClickListener action) {
            this.text = text;
            this.action = action;
        }
    }

    List<BottomButton> createExtraBottomButtons() {
        return emptyList();
    }

    private MenuItem miShare;
    private MenuItem miSave;
    private MenuItem miSetDescription;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (shouldShowReportButton()) {
            miShare = menu.add(R.string.action_share);
        }
        miSave = menu.add(R.string.action_save);
        miSetDescription = menu.add(getDescriptionActionTitle())
            .setIcon(R.drawable.ic_add_description)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    private CharSequence getDescriptionActionTitle() {
        boolean isEmpty = viewModel.description.isEmpty();
        return getText(isEmpty? R.string.add_description : R.string.update_description);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (miShare == item) {
            onActionShare();
            return true;
        }
        if (miSave == item) {
            SnapshotSaver.start(this);
            return true;
        }
        if (miSetDescription == item) {
            EditorDialog.show(this, false, getDescriptionActionTitle(),
                    viewModel.description, null, (Editable res) -> {
                String s = res.toString().trim();
                viewModel.description = s;
                miSetDescription.setTitle(getDescriptionActionTitle());
                updateListItems();
                scrollToBottom();
            });
            return true;
        }
        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode >= SnapshotSaver.MIN_REQUEST_CODE) {
            if (resultCode != RESULT_OK || data == null) {
                return;
            }
            SnapshotSaver.onActivityResult(this, requestCode, data.getData());
        }
    }
}
