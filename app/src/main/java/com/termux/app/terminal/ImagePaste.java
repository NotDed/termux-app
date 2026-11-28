package com.termux.app.terminal;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gets an image to Claude Code: from the clipboard, the gallery or the camera, saved under
 * ~/.cache/pasted-images and pasted into the tab as a file path, which Claude attaches as
 * [Image #N]. (Claude Code's own Ctrl+V image paste can't read Android's clipboard.)
 */
public final class ImagePaste {

    public static final int REQUEST_PICK_IMAGES = 0x1A01;
    public static final int REQUEST_TAKE_PHOTO = 0x1A02;
    public static final int REQUEST_PICK_FILES = 0x1A03;
    private static final String STATE_CAMERA_URI = "image_paste_camera_uri";
    private static final String STATE_TARGET = "image_paste_target";

    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final long KEEP_MS = 7L * 24 * 60 * 60 * 1000;
    private static final File DIR = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".cache/pasted-images");
    /** Documents are kept apart from images, under their own (made safe) names. */
    private static final File FILES_DIR = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".cache/pasted-files");
    private static final long MAX_FILE_BYTES = 32L * 1024 * 1024;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** The camera's output, kept until the camera returns. */
    @Nullable private static Uri sCameraUri;
    /** The tab that was current when the 📎 key was tapped. */
    @Nullable private static String sTargetHandle;

    private ImagePaste() {}

    // ---- The 📎 popup ----

    static void showPopup(TermuxActivity activity, android.view.View anchor) {
        TerminalSession session = activity.getCurrentSession();
        TabBarPopup popup = new TabBarPopup(activity, activity.getString(R.string.title_paste_image));
        if (session == null || !session.isRunning()) {
            popup.addMessage(TabBarPopup.Kind.INFO, activity.getString(R.string.msg_paste_image_no_tab), null, null);
            popup.show(anchor);
            return;
        }
        sTargetHandle = session.mHandle;

        boolean hasImage = clipboardImageUris(activity).size() > 0;
        TabBarPopup.Row clip = popup.addLinkRow(activity.getString(R.string.action_paste_image_clipboard),
            activity.getString(hasImage ? R.string.msg_paste_image_clipboard_yes : R.string.msg_paste_image_clipboard_no), () -> {
                popup.dismiss();
                paste(activity, clipboardImageUris(activity));
            });
        clip.setEnabled(hasImage);
        popup.addLinkRow(activity.getString(R.string.action_paste_image_gallery), null, () -> {
            popup.dismiss();
            pickFromGallery(activity);
        });
        popup.addLinkRow(activity.getString(R.string.action_paste_image_camera), null, () -> {
            popup.dismiss();
            takePhoto(activity);
        });
        popup.addLinkRow(activity.getString(R.string.action_attach_document), activity.getString(R.string.msg_attach_document), () -> {
            popup.dismiss();
            pickFiles(activity);
        });
        List<String> ssh = TabProcesses.sshTarget(session.getPid());
        popup.addFooter(ssh != null ? activity.getString(R.string.msg_attach_ssh_footer, ssh.get(ssh.size() - 1))
            : activity.getString(R.string.msg_paste_image_footer));
        popup.show(anchor);
    }

    // ---- Sources ----

    /** Image Uris in the clipboard, if any. Only readable while Termux has focus. */
    static List<Uri> clipboardImageUris(Context context) {
        List<Uri> uris = new ArrayList<>();
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            if (clip == null) return uris;
            ClipDescription description = clip.getDescription();
            boolean describedAsImage = description != null && description.hasMimeType("image/*");
            ContentResolver resolver = context.getContentResolver();
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri == null) continue;
                String type = resolver.getType(uri);
                if (describedAsImage || (type != null && type.startsWith("image/"))) uris.add(uri);
            }
        } catch (Exception ignored) {}
        return uris;
    }

    private static void pickFromGallery(TermuxActivity activity) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT)
            .setType("image/*")
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            activity.startActivityForResult(Intent.createChooser(intent, activity.getString(R.string.action_paste_image_gallery)), REQUEST_PICK_IMAGES);
        } catch (Exception e) {
            showMessage(activity, TabBarPopup.Kind.ERROR, activity.getString(R.string.msg_paste_image_no_app));
        }
    }

    private static void pickFiles(TermuxActivity activity) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT)
            .setType("*/*")
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            activity.startActivityForResult(Intent.createChooser(intent, activity.getString(R.string.action_attach_document)), REQUEST_PICK_FILES);
        } catch (Exception e) {
            showMessage(activity, TabBarPopup.Kind.ERROR, activity.getString(R.string.msg_paste_image_no_app));
        }
    }

    private static void takePhoto(TermuxActivity activity) {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        sCameraUri = null;
        try {
            // A new entry in Pictures/Termux for the camera to write to
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "termux-" + timestamp() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29) values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Termux");
            sCameraUri = activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        } catch (Exception ignored) {
            // Without a place to write, the camera returns a small preview instead
        }
        if (sCameraUri != null) {
            intent.putExtra(MediaStore.EXTRA_OUTPUT, sCameraUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        try {
            activity.startActivityForResult(intent, REQUEST_TAKE_PHOTO);
        } catch (Exception e) {
            deleteCameraEntry(activity);
            showMessage(activity, TabBarPopup.Kind.ERROR, activity.getString(R.string.msg_paste_image_no_camera));
        }
    }

    /** Handle the gallery or camera result. Returns true if it was ours. */
    public static boolean onActivityResult(TermuxActivity activity, int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_PICK_FILES) {
            if (resultCode != Activity.RESULT_OK || data == null) return true;
            attachFiles(activity, urisOf(data));
            return true;
        }
        if (requestCode == REQUEST_PICK_IMAGES) {
            if (resultCode != Activity.RESULT_OK || data == null) return true;
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri uri = clip.getItemAt(i).getUri();
                    if (uri != null) uris.add(uri);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            paste(activity, uris);
            return true;
        }
        if (requestCode == REQUEST_TAKE_PHOTO) {
            Uri cameraUri = sCameraUri;
            sCameraUri = null;
            if (resultCode != Activity.RESULT_OK) {
                if (cameraUri != null) deleteUri(activity, cameraUri);
                return true;
            }
            if (cameraUri != null) {
                List<Uri> uris = new ArrayList<>();
                uris.add(cameraUri);
                paste(activity, uris);
            } else if (data != null && data.getExtras() != null && data.getExtras().get("data") instanceof Bitmap) {
                Bitmap preview = (Bitmap) data.getExtras().get("data");
                EXECUTOR.execute(() -> {
                    String path = saveBitmap(preview);
                    MAIN.post(() -> finish(activity, path == null ? new ArrayList<>() : singletonList(path), path == null));
                });
            }
            return true;
        }
        return false;
    }

    public static void onSaveInstanceState(Bundle state) {
        if (sCameraUri != null) state.putString(STATE_CAMERA_URI, sCameraUri.toString());
        if (sTargetHandle != null) state.putString(STATE_TARGET, sTargetHandle);
    }

    public static void onRestoreInstanceState(@Nullable Bundle state) {
        if (state == null) return;
        String uri = state.getString(STATE_CAMERA_URI);
        if (uri != null && sCameraUri == null) sCameraUri = Uri.parse(uri);
        String target = state.getString(STATE_TARGET);
        if (target != null && sTargetHandle == null) sTargetHandle = target;
    }

    // ---- Saving and pasting ----

    private static void paste(TermuxActivity activity, List<Uri> uris) {
        if (uris.isEmpty()) {
            showMessage(activity, TabBarPopup.Kind.INFO, activity.getString(R.string.msg_paste_image_clipboard_no));
            return;
        }
        ContentResolver resolver = activity.getContentResolver();
        EXECUTOR.execute(() -> {
            cleanup();
            List<String> paths = new ArrayList<>();
            boolean failed = false;
            for (Uri uri : uris) {
                String path = save(resolver, uri, paths.size());
                if (path != null) paths.add(path);
                else failed = true;
            }
            boolean anyFailed = failed;
            MAIN.post(() -> finish(activity, paths, anyFailed));
        });
    }

    private static List<Uri> urisOf(Intent data) {
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        return uris;
    }

    /**
     * Attach picked files: images as plain paths (Claude attaches them as images), anything else
     * as an @ mention of its path, which Claude reads (PDFs and text directly).
     */
    private static void attachFiles(TermuxActivity activity, List<Uri> uris) {
        if (uris.isEmpty()) return;
        ContentResolver resolver = activity.getContentResolver();
        EXECUTOR.execute(() -> {
            cleanup();
            List<String> tokens = new ArrayList<>();
            boolean failed = false;
            for (Uri uri : uris) {
                String type = null;
                try {
                    type = resolver.getType(uri);
                } catch (Exception ignored) {}
                String token;
                if (type != null && type.startsWith("image/")) {
                    token = save(resolver, uri, tokens.size());
                } else {
                    String path = saveDocument(resolver, uri, type);
                    token = path == null ? null : "@" + path;
                }
                if (token != null) tokens.add(token);
                else failed = true;
            }
            boolean anyFailed = failed;
            MAIN.post(() -> finish(activity, tokens, anyFailed));
        });
    }

    /** Copy a document to ~/.cache/pasted-files under a safe version of its name. Returns its path, or null. */
    @Nullable
    private static String saveDocument(ContentResolver resolver, Uri uri, @Nullable String type) {
        try {
            FILES_DIR.mkdirs();
            File file = new File(FILES_DIR, timestamp() + "-" + safeName(displayName(resolver, uri), type));
            try (InputStream in = resolver.openInputStream(uri); OutputStream out = new FileOutputStream(file)) {
                if (in == null) return null;
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    total += read;
                    if (total > MAX_FILE_BYTES) {
                        out.close();
                        file.delete();
                        return null;
                    }
                    out.write(buffer, 0, read);
                }
            }
            return file.length() > 0 ? file.getAbsolutePath() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String displayName(ContentResolver resolver, Uri uri) {
        try (android.database.Cursor cursor = resolver.query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {}
        String last = uri.getLastPathSegment();
        return last == null ? null : new File(last).getName();
    }

    /** "My report (final).pdf" becomes "My_report__final_.pdf": no spaces or shell characters, at most 80. */
    static String safeName(@Nullable String name, @Nullable String type) {
        String safe = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^\\.+", "");
        String extension = "";
        int dot = safe.lastIndexOf('.');
        if (dot > 0 && safe.length() - dot <= 10) {
            extension = safe.substring(dot);
            safe = safe.substring(0, dot);
        } else if (type != null) {
            String fromType = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(type);
            extension = fromType != null ? "." + fromType : "";
        }
        if (safe.isEmpty()) safe = "file";
        if (safe.length() > 80 - extension.length()) safe = safe.substring(0, 80 - extension.length());
        return safe + extension;
    }

    /**
     * Paste the saved paths ("@path" for documents) into the target tab, typed but not run. In an
     * SSH tab the files are uploaded to that host first and its paths are pasted instead.
     */
    private static void finish(TermuxActivity activity, List<String> paths, boolean failed) {
        if (paths.isEmpty()) {
            showMessage(activity, TabBarPopup.Kind.ERROR, activity.getString(R.string.msg_paste_image_failed));
            return;
        }
        TerminalSession session = findTarget(activity);
        if (session == null || session.getEmulator() == null) {
            showMessage(activity, TabBarPopup.Kind.INFO, activity.getString(R.string.msg_paste_image_no_tab));
            return;
        }
        int pid = session.getPid();
        EXECUTOR.execute(() -> {
            List<String> ssh = TabProcesses.sshTarget(pid);
            if (ssh == null) {
                MAIN.post(() -> pasteInto(activity, session, paths, failed));
                return;
            }
            String host = ssh.get(ssh.size() - 1);
            TabBarPopup[] progress = new TabBarPopup[1];
            MAIN.post(() -> {
                progress[0] = new TabBarPopup(activity, activity.getString(R.string.title_paste_image));
                progress[0].addMessage(TabBarPopup.Kind.INFO, activity.getString(R.string.msg_attach_uploading, host), null, null);
                progress[0].showCentered(false, null);
            });
            List<String> remote = new ArrayList<>();
            String error = null;
            for (String token : paths) {
                boolean document = token.startsWith("@");
                String[] result = upload(activity, ssh, new File(document ? token.substring(1) : token));
                if (result[0] != null) remote.add((document ? "@" : "") + result[0]);
                else if (error == null) error = result[1];
            }
            String firstError = error;
            MAIN.post(() -> {
                if (progress[0] != null) progress[0].dismiss();
                if (!remote.isEmpty()) pasteInto(activity, session, remote, failed);
                if (firstError != null)
                    showMessage(activity, TabBarPopup.Kind.ERROR, activity.getString(R.string.msg_attach_upload_failed, host, firstError));
            });
        });
    }

    private static void pasteInto(TermuxActivity activity, TerminalSession session, List<String> paths, boolean failed) {
        if (!session.isRunning() || session.getEmulator() == null) {
            showMessage(activity, TabBarPopup.Kind.INFO, activity.getString(R.string.msg_paste_image_no_tab));
            return;
        }
        session.getEmulator().paste(String.join(" ", paths) + " ");
        if (failed) showMessage(activity, TabBarPopup.Kind.WARN, activity.getString(R.string.msg_paste_image_some_failed));
    }

    /** Remote script: $0 is the (already safe) file name. Prints the absolute path it saved to. */
    private static final String REMOTE_SAVE = "sh -c 'd=\"$HOME/.cache/termux-attach\"; mkdir -p \"$d\" && "
        + "{ find \"$d\" -type f -mtime +7 -delete 2>/dev/null; cat > \"$d/$0\" && printf %s \"$d/$0\"; }' ";

    /**
     * Copy a file to the SSH host with the user's key, never prompting. Returns {remote path, null}
     * or {null, error}. Off the main thread.
     */
    private static String[] upload(Context context, List<String> ssh, File file) {
        String name = file.getName();
        if (!name.matches("[A-Za-z0-9._-]+")) return new String[]{null, "bad file name"};
        List<String> command = new ArrayList<>();
        command.add(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/ssh");
        command.add("-o");
        command.add("BatchMode=yes");
        command.add("-o");
        command.add("ConnectTimeout=10");
        command.addAll(ssh.subList(0, ssh.size() - 1));
        command.add("--");
        command.add(ssh.get(ssh.size() - 1));
        command.add(REMOTE_SAVE + name);
        File errors = null;
        try {
            errors = File.createTempFile("attach-ssh", ".err", context.getCacheDir());
            ProcessBuilder builder = BackgroundShell.newProcessBuilder(context, command.toArray(new String[0]));
            builder.redirectErrorStream(false);
            builder.redirectInput(file);
            builder.redirectError(errors);
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (InputStream in = process.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = in.read(buffer)) > 0 && output.length() < 4096)
                        output.append(new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
            });
            reader.start();
            if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroy();
                return new String[]{null, "timed out"};
            }
            reader.join(2000);
            // The last line, in case the remote shell's startup files print something first
            String path = output.toString().trim();
            path = path.substring(path.lastIndexOf('\n') + 1).trim();
            if (process.exitValue() == 0 && path.startsWith("/") && path.endsWith("/" + name)) return new String[]{path, null};
            String message = BackgroundShell.tail(errors.getAbsolutePath(), 1);
            return new String[]{null, message.isEmpty() ? "ssh exited with " + process.exitValue() : message};
        } catch (Exception e) {
            return new String[]{null, String.valueOf(e.getMessage())};
        } finally {
            if (errors != null) errors.delete();
        }
    }

    @Nullable
    private static TerminalSession findTarget(TermuxActivity activity) {
        if (sTargetHandle != null && activity.getTermuxService() != null) {
            for (com.termux.shared.shell.TermuxSession termuxSession : activity.getTermuxService().getTermuxSessions()) {
                TerminalSession session = termuxSession.getTerminalSession();
                if (session != null && sTargetHandle.equals(session.mHandle) && session.isRunning()) return session;
            }
        }
        TerminalSession current = activity.getCurrentSession();
        return current != null && current.isRunning() ? current : null;
    }

    /** Copy one image to ~/.cache/pasted-images in a format Claude reads. Returns its path, or null. */
    @Nullable
    private static String save(ContentResolver resolver, Uri uri, int index) {
        try {
            String type = resolver.getType(uri);
            String extension = extensionFor(type);
            DIR.mkdirs();
            File file = new File(DIR, "img-" + timestamp() + (index > 0 ? "-" + (index + 1) : "") + "." + (extension != null ? extension : "png"));
            if (extension != null) {
                try (InputStream in = resolver.openInputStream(uri); OutputStream out = new FileOutputStream(file)) {
                    if (in == null) return null;
                    byte[] buffer = new byte[64 * 1024];
                    long total = 0;
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        total += read;
                        if (total > MAX_BYTES) {
                            out.close();
                            file.delete();
                            return null;
                        }
                        out.write(buffer, 0, read);
                    }
                }
            } else {
                // HEIC and other formats Claude doesn't read: convert to PNG
                Bitmap bitmap;
                try (InputStream in = resolver.openInputStream(uri)) {
                    bitmap = in == null ? null : BitmapFactory.decodeStream(in);
                }
                if (bitmap == null) return null;
                try (OutputStream out = new FileOutputStream(file)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            }
            return file.length() > 0 ? file.getAbsolutePath() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String saveBitmap(Bitmap bitmap) {
        try {
            DIR.mkdirs();
            File file = new File(DIR, "img-" + timestamp() + ".png");
            try (OutputStream out = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            return file.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String extensionFor(@Nullable String mimeType) {
        if (mimeType == null) return null;
        switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "image/png": return "png";
            case "image/jpeg":
            case "image/jpg": return "jpg";
            case "image/gif": return "gif";
            case "image/webp": return "webp";
            default: return null;
        }
    }

    /** Remove pasted images older than a week. */
    private static void cleanup() {
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        for (File dir : new File[]{DIR, FILES_DIR}) {
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File file : files) if (file.lastModified() < cutoff) file.delete();
        }
    }

    private static void deleteCameraEntry(Context context) {
        if (sCameraUri != null) deleteUri(context, sCameraUri);
        sCameraUri = null;
    }

    private static void deleteUri(Context context, Uri uri) {
        try {
            context.getContentResolver().delete(uri, null, null);
        } catch (Exception ignored) {}
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
    }

    private static List<String> singletonList(String value) {
        List<String> list = new ArrayList<>();
        list.add(value);
        return list;
    }

    private static void showMessage(TermuxActivity activity, TabBarPopup.Kind kind, String message) {
        TabBarPopup panel = new TabBarPopup(activity, activity.getString(R.string.title_paste_image));
        panel.addMessage(kind, message, null, null);
        panel.addButtons(new TabBarPopup.Button(activity.getString(R.string.action_ok), TabBarPopup.Button.PRIMARY, panel::dismiss));
        panel.showCentered(true, null);
    }

}
