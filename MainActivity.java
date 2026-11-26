package de.foxcenter.app;

import android.os.Bundle;
import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(CenterPlugin.class);
        registerPlugin(UpdatePlugin.class);
        super.onCreate(savedInstanceState);
        // Zurueck-Taste (Handy und Fernbedienung) geht zuerst an die Oberflaeche
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView wv = getBridge() != null ? getBridge().getWebView() : null;
                if (wv == null) {
                    finish();
                    return;
                }
                wv.evaluateJavascript("(window.foxBack&&window.foxBack())?'1':'0'", r -> {
                    if (r == null || !r.contains("1")) {
                        moveTaskToBack(true);
                    }
                });
            }
        });
    }
}
