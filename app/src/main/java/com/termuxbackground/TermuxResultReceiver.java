package com.termuxbackground;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

/**
 * Receives the execution result of a RUN_COMMAND started by {@link WebAppInterface}
 * via the Termux RunCommandService. Termux sends it back through the pending
 * intent we provide; the result is a "result" bundle with "stdout", "stderr",
 * "exitCode", "err" and "errmsg" entries (Termux plugin result contract).
 *
 * Registered as {@code exported="false"}: only reachable through our own
 * pending intent, so no external app can trigger it.
 */
public class TermuxResultReceiver extends BroadcastReceiver {

    private static final String TAG = "TermuxBG";
    private static final String RESULT_BUNDLE_KEY = "result";
    private static final String EXIT_CODE_KEY = "exitCode";
    private static final String ERRMSG_KEY = "errmsg";
    private static final String ORIGIN_EXTRA = "com.termuxbackground.origin";
    private static final String ORIGIN_VALUE = "termux-background-result";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ORIGIN_VALUE.equals(intent.getStringExtra(ORIGIN_EXTRA))) {
            Log.d(TAG, "TERMUX_RESULT: ignoring intent without origin token");
            return;
        }
        Bundle result = intent.getBundleExtra(RESULT_BUNDLE_KEY);
        if (result == null) {
            Log.d(TAG, "TERMUX_RESULT: no result bundle in intent");
            return;
        }

        int exitCode = result.getInt(EXIT_CODE_KEY, -1);
        String errmsg = result.getString(ERRMSG_KEY);
        String stdout = result.getString("stdout");
        Log.d(TAG, "TERMUX_RESULT: exitCode=" + exitCode + " errmsg=" + errmsg + " stdout=" + stdout);

        if (exitCode == 0 && errmsg == null) {
            Toast.makeText(context, "Termux command completed successfully", Toast.LENGTH_SHORT).show();
        } else {
            String message = "Termux command failed";
            if (errmsg != null) {
                message += ": " + errmsg;
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        }
    }
}