package javax.microedition.util;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;

import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.overlay.OverlayView;

public abstract class J2meHost extends AppCompatActivity {
    private FrameLayout displayableContainer;
    private OverlayView overlayView;
    private Displayable current;
    private boolean visible;
    private boolean pauseSuppressed;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        displayableContainer = new FrameLayout(this);
        overlayView = new OverlayView(this, null);
    }

    public final Context getContext() {
        return this;
    }

    public final Activity getActivity() {
        return this;
    }

    public final OverlayView getOverlayView() {
        return overlayView;
    }

    public final FrameLayout getDisplayableContainer() {
        return displayableContainer;
    }

    public Displayable getCurrent() {
        return current;
    }

    public void setCurrent(Displayable displayable) {
        current = displayable;
        runOnUiThread(() -> {
            displayableContainer.removeAllViews();
            if (displayable != null) {
                displayableContainer.addView(displayable.getDisplayableView(), new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            }
        });
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public boolean isPauseSuppressed() {
        return pauseSuppressed;
    }

    public void setPauseSuppressed(boolean pauseSuppressed) {
        this.pauseSuppressed = pauseSuppressed;
    }

    public void showExitConfirmation() {
        finish();
    }

    public void toast(@StringRes int messageId) {
        toast(getString(messageId));
    }

    public void toast(String message) {
        runOnUiThread(() -> android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show());
    }

    public void openOptionsMenu() {
        super.openOptionsMenu();
    }

    public String getAppName() {
        CharSequence title = getTitle();
        return title != null ? title.toString() : "J2ME";
    }
}
