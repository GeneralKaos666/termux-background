package com.termuxbackground;

import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class WebAppInterface {

    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND";
    private static final String TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String TERMUX_SH_PATH = "/data/data/com.termux/files/usr/bin/sh";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final int MAX_IMAGE_DIMENSION = 1600;
    private static final int MAX_IMAGE_BYTES = 400 * 1024;

    private final Context context;
    private final ContentResolver contentResolver;
    private final PackageManager packageManager;
    private final WebView webView;
    private final PendingIntent termuxResultPendingIntent;

    private Uri lastImageUri;

    public WebAppInterface(Context context, WebView webView, PendingIntent termuxResultPendingIntent) {
        this.context = context;
        this.contentResolver = context.getContentResolver();
        this.packageManager = context.getPackageManager();
        this.webView = webView;
        this.termuxResultPendingIntent = termuxResultPendingIntent;
    }

    public void setLastImageUri(Uri uri) {
        this.lastImageUri = uri;
    }

    @JavascriptInterface
    public String getStatus() {
        try {
            JSONObject status = buildStatus();
            return status.toString();
        } catch (Exception e) {
            return buildError("Failed to build status: " + e.getMessage()).toString();
        }
    }

@JavascriptInterface
    public String applyBackground(String payloadJson) {
        JSONObject result = new JSONObject();
        try {
            JSONObject payload = new JSONObject(payloadJson == null ? "{}" : payloadJson);
            Uri imageUri = resolveImageUri(payload.optString("imageUri", null));
            String opacityStr = payload.optString("opacity", "");
            String animation = payload.optString("animation", "none");
            boolean blur = payload.optBoolean("blur", false);

            Status status = parseStatus();
            if (!status.canRunCommands) {
                return buildBlocked("Termux is not ready: " + status.lastError + ".").toString();
            }

            if (imageUri == null) {
                return buildError("Select an image before applying.").toString();
            }

            double opacity = parseOpacity(opacityStr);
            if (Double.isNaN(opacity)) {
                return buildError("Invalid opacity value.").toString();
            }

            if (!validateAnimation(animation)) {
                return buildError("Invalid animation option.").toString();
            }

            String mimeType = contentResolver.getType(imageUri);
            if (!isSupportedMime(mimeType)) {
                return buildError("Unsupported image type. Use PNG or JPEG.").toString();
            }

            byte[] imageBytes = decodeScaledImage(imageUri);
            String imageBase64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

            String script = buildApplyScript(opacity, blur, animation);
            JSONObject reloadResult = runTermuxCommand(TERMUX_SH_PATH, new String[]{"-c", script}, TERMUX_HOME, imageBase64);
            if (!reloadResult.optBoolean("ok", false)) {
                return reloadResult.toString();
            }

            result.put("ok", true);
            result.put("blocked", false);
            result.put("message", "Background applied and Termux settings reloaded.");
            return result.toString();
        } catch (JSONException e) {
            return buildError("Invalid payload: " + e.getMessage()).toString();
        } catch (IOException e) {
            return buildError("Failed to prepare image: " + e.getMessage()).toString();
        }
    }

@JavascriptInterface
    public String resetBackground() {
        try {
            Status status = parseStatus();
            if (!status.canRunCommands) {
                return buildBlocked("Termux is not ready: " + status.lastError + ".").toString();
            }

            String script = buildResetScript();
            JSONObject reloadResult = runTermuxCommand(TERMUX_SH_PATH, new String[]{"-c", script}, TERMUX_HOME, null);
            if (!reloadResult.optBoolean("ok", false)) {
                return reloadResult.toString();
            }

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("blocked", false);
            result.put("message", "Background settings reset and Termux reloaded.");
            return result.toString();
        } catch (Exception e) {
            return buildError("Failed to reset: " + e.getMessage()).toString();
        }
    }

    private Status parseStatus() {
        Status status = new Status();
        status.termuxInstalled = isPackageInstalled(TERMUX_PACKAGE);
        status.runCommandServiceAvailable = isRunCommandServiceAvailable();
        status.runCommandPermissionGranted = hasRunCommandPermission();
        status.canRunCommands = status.termuxInstalled && status.runCommandServiceAvailable && status.runCommandPermissionGranted;
        if (!status.termuxInstalled) {
            status.lastError = "Termux not installed";
        } else if (!status.runCommandServiceAvailable) {
            status.lastError = "Termux RUN_COMMAND service unavailable (update Termux)";
        } else if (!status.runCommandPermissionGranted) {
            status.lastError = "RUN_COMMAND permission not granted";
        }
        return status;
    }

    private JSONObject buildStatus() throws JSONException {
        Status status = parseStatus();
        JSONObject statusJson = new JSONObject();
        statusJson.put("termuxInstalled", status.termuxInstalled);
        statusJson.put("runCommandPermissionGranted", status.runCommandPermissionGranted);
        statusJson.put("canRunCommands", status.canRunCommands);
        statusJson.put("lastError", status.lastError);
        statusJson.put("appVersion", BuildConfig.VERSION_NAME);
        return statusJson;
    }

    private boolean validateAnimation(String animation) {
        return TextUtils.equals(animation, "none") || TextUtils.equals(animation, "scroll");
    }

    private boolean isSupportedMime(String mimeType) {
        if (mimeType == null) return false;
        return mimeType.equals("image/png") || mimeType.equals("image/jpeg");
    }

    private double parseOpacity(String opacityStr) {
        try {
            double value = Double.parseDouble(opacityStr);
            if (value >= 0.0 && value <= 1.0) {
                return value;
            }
            return Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private Uri resolveImageUri(String uriFromPayload) {
        if (!TextUtils.isEmpty(uriFromPayload)) {
            return Uri.parse(uriFromPayload);
        }
        return lastImageUri;
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            packageManager.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean isRunCommandServiceAvailable() {
        Intent intent = new Intent(TERMUX_RUN_COMMAND_ACTION);
        intent.setPackage(TERMUX_PACKAGE);
        List<ResolveInfo> services = packageManager.queryIntentServices(intent, 0);
        return services != null && !services.isEmpty();
    }

    private boolean hasRunCommandPermission() {
        return context.checkSelfPermission(TERMUX_RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    private byte[] decodeScaledImage(Uri uri) throws IOException {
        Bitmap bitmap;
        try (InputStream in = contentResolver.openInputStream(uri)) {
            if (in == null) {
                throw new IOException("Unable to open selected file.");
            }
            bitmap = BitmapFactory.decodeStream(in);
        }
        if (bitmap == null) {
            throw new IOException("Unable to decode the selected image.");
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int maxDim = Math.max(width, height);
        if (maxDim > MAX_IMAGE_DIMENSION) {
            float scale = (float) MAX_IMAGE_DIMENSION / maxDim;
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.round(width * scale), Math.round(height * scale), true);
            bitmap.recycle();
            bitmap = scaled;
        }

        byte[] bytes = null;
        for (int quality = 85; quality >= 40; quality -= 5) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
            if (out.size() <= MAX_IMAGE_BYTES) {
                bytes = out.toByteArray();
                break;
            }
        }
        bitmap.recycle();
        if (bytes == null) {
            throw new IOException("Image is too large to transfer to Termux even after scaling. Try a smaller image.");
        }
        return bytes;
    }

    private String buildApplyScript(double opacity, boolean blur, String animation) {
        StringBuilder script = new StringBuilder();
        script.append("set -e\n");
        script.append("d=${HOME:-/data/data/com.termux/files/home}\n");
        script.append("mkdir -p \"$d/.termux\"\n");
        script.append("cd \"$d/.termux\"\n");
        script.append("base64 -d > background.jpg\n");
        script.append("if [ -f termux.properties ]; then\n");
        script.append("  grep -vE '^background(\\.|=)' termux.properties > termux.properties.tmp\n");
        script.append("else\n");
        script.append("  : > termux.properties.tmp\n");
        script.append("fi\n");
        script.append("printf 'background=background.jpg\\n");
        script.append("background.opacity=").append(String.valueOf(opacity)).append("\\n");
        script.append("background.blur=").append(blur ? "true" : "false").append("\\n");
        script.append("background.animation=").append(animation).append("\\n' >> termux.properties.tmp\n");
        script.append("mv termux.properties.tmp termux.properties\n");
        script.append("ls -l background.jpg\n");
        script.append("cat termux.properties\n");
        script.append("termux-reload-settings\n");
        return script.toString();
    }

    private String buildResetScript() {
        StringBuilder script = new StringBuilder();
        script.append("set -e\n");
        script.append("d=${HOME:-/data/data/com.termux/files/home}\n");
        script.append("mkdir -p \"$d/.termux\"\n");
        script.append("cd \"$d/.termux\"\n");
        script.append("rm -f background.jpg background.png\n");
        script.append("if [ -f termux.properties ]; then\n");
        script.append("  grep -vE '^background(\\.|=)' termux.properties > termux.properties.tmp\n");
        script.append("  mv termux.properties.tmp termux.properties\n");
        script.append("fi\n");
        script.append("termux-reload-settings\n");
        return script.toString();
    }

    private JSONObject runTermuxCommand(String executable, String[] arguments, String workdir, String stdin) {
        Status status = parseStatus();
        if (!status.canRunCommands) {
            return buildBlocked("Termux is not ready: " + status.lastError + ".");
        }

        Intent intent = new Intent(TERMUX_RUN_COMMAND_ACTION);
        intent.setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE);
        intent.putExtra("com.termux.RUN_COMMAND_PATH", executable);
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments);
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", workdir);
        intent.putExtra("com.termux.RUN_COMMAND_RUNNER", "app-shell");
        intent.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Termux Background settings reload");
        if (stdin != null) {
            intent.putExtra("com.termux.RUN_COMMAND_STDIN", stdin);
        }
        if (termuxResultPendingIntent != null) {
            intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", termuxResultPendingIntent);
        }
        try {
            context.startService(intent);
            JSONObject response = new JSONObject();
            response.put("ok", true);
            response.put("blocked", false);
            response.put("message", "Reload triggered");
            return response;
        } catch (SecurityException e) {
            return buildBlocked("RUN_COMMAND permission not granted: " + e.getMessage());
        } catch (Exception e) {
            return buildError("RUN_COMMAND invocation failed: " + e.getMessage());
        }
    }

    private JSONObject buildError(String message) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("ok", false);
            obj.put("blocked", false);
            obj.put("message", message);
        } catch (JSONException ignored) {
        }
        return obj;
    }

    private JSONObject buildBlocked(String message) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("ok", false);
            obj.put("blocked", true);
            obj.put("message", message);
        } catch (JSONException ignored) {
        }
        return obj;
    }

    private static class Status {
        boolean termuxInstalled;
        boolean runCommandServiceAvailable;
        boolean runCommandPermissionGranted;
        boolean canRunCommands;
        String lastError;
    }
}
