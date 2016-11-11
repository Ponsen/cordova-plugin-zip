package org.apache.cordova;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


import android.net.Uri;

import org.apache.cordova.CordovaResourceApi.OpenForReadResult;
import org.json.JSONException;
import org.json.JSONObject;

import android.util.Log;

public class Zip extends CordovaPlugin {

    private static final String LOG_TAG = "Zip";

    @Override
    public boolean execute(String action, CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
        if ("unzip".equals(action)) {
            unzip(args, callbackContext);
            return true;
        }
        return false;
    }

    private void unzip(final CordovaArgs args, final CallbackContext callbackContext) {
        this.cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                unzipSync(args, callbackContext);
            }
        });
    }

    // Can't use DataInputStream because it has the wrong endian-ness.
    private static int readInt(InputStream is) throws IOException {
        int a = is.read();
        int b = is.read();
        int c = is.read();
        int d = is.read();
        return a | b << 8 | c << 16 | d << 24;
    }

    private void unzipSync(CordovaArgs args, CallbackContext callbackContext) {
        InputStream inputStream = null;
        try {
            String zipFileName = args.getString(0);
            String outputDirectory = args.getString(1);

            // Since Cordova 3.3.0 and release of File plugins, files are accessed via cdvfile://
            // Accept a path or a URI for the source zip.
            Uri zipUri = getUriForArg(zipFileName);

            Uri outputUri = getUriForArg(outputDirectory);

            CordovaResourceApi resourceApi = webView.getResourceApi();

            // Note that this is not actually used...??
            File tempFile = resourceApi.mapUriToFile(zipUri);

            boolean tempFileIsAnAsset = false;

            // Calvium 15/2/2015: support unzipping files from assets folder.
            // Needed because File.exists() does not work for assets
            if (tempFile == null) {
                String androidAssetStem = "file:///android_asset/";
                if (zipFileName.startsWith(androidAssetStem)) {
                    try {
                        cordova.getActivity().getAssets().open(zipFileName.replace(androidAssetStem, ""));
                        tempFileIsAnAsset = true;
                    } catch (IOException ex) {
                        String msg = "unzipSync: assets file doesn't exist";
                        Log.e("ZIP", msg);
                        callbackContext.error(msg);
                        return;
                    }
                }
            }

            if (tempFileIsAnAsset) {
                Log.i("ZIP", "unzipSync: unzipping from an asset file");
            } else if (tempFile == null || !tempFile.exists()) {
                String errorMessage = "Zip file does not exist";
                callbackContext.error(errorMessage);
                Log.e(LOG_TAG, errorMessage);
                return;
            }

            File outputDir = resourceApi.mapUriToFile(outputUri);
            outputDirectory = outputDir.getAbsolutePath();
            outputDirectory += outputDirectory.endsWith(File.separator) ? "" : File.separator;
            if (outputDir == null || (!outputDir.exists() && !outputDir.mkdirs())){
                String errorMessage = "Could not create output directory";
                callbackContext.error(errorMessage);
                Log.e(LOG_TAG, errorMessage);
                return;
            }

            OpenForReadResult zipFile = resourceApi.openForRead(zipUri);
            ProgressEvent progress = new ProgressEvent();
            progress.setTotal(zipFile.length);

            inputStream = new BufferedInputStream(zipFile.inputStream);
            inputStream.mark(10);
            int magic = readInt(inputStream);

            if (magic != 875721283) { // CRX identifier
                inputStream.reset();
            } else {
                // CRX files contain a header. This header consists of:
                //  * 4 bytes of magic number
                //  * 4 bytes of CRX format version,
                //  * 4 bytes of public key length
                //  * 4 bytes of signature length
                //  * the public key
                //  * the signature
                // and then the ordinary zip data follows. We skip over the header before creating the ZipInputStream.
                readInt(inputStream); // version == 2.
                int pubkeyLength = readInt(inputStream);
                int signatureLength = readInt(inputStream);

                inputStream.skip(pubkeyLength + signatureLength);
                progress.setLoaded(16 + pubkeyLength + signatureLength);
            }

            // The inputstream is now pointing at the start of the actual zip file content.
            ZipInputStream zis = new ZipInputStream(inputStream);
            inputStream = zis;

            ZipEntry ze;
            byte[] buffer = new byte[32 * 1024];
            boolean anyEntries = false;

            while ((ze = zis.getNextEntry()) != null)
            {
                // benvium
                long totalRead = 0;

                anyEntries = true;
                String compressedName = ze.getName();

                if (ze.isDirectory()) {
                    File dir = new File(outputDirectory + compressedName);
                    dir.mkdirs();
                } else {
                    File file = new File(outputDirectory + compressedName);
                    file.getParentFile().mkdirs();
                    if (file.exists() || file.createNewFile()) {
                        Log.w("Zip", "extracting: " + file.getPath());
                        FileOutputStream fout = new FileOutputStream(file);
                        int count;
                        while ((count = zis.read(buffer)) != -1) {
                            totalRead += count;
                            fout.write(buffer, 0, count);
                        }
                        fout.close();
                    }
                }
                progress.addLoaded(totalRead);
                updateProgress(callbackContext, progress);
                zis.closeEntry();
            }

            // final progress = 100%
            progress.setLoaded(progress.getTotal());
            updateProgress(callbackContext, progress);

            if (anyEntries)
                callbackContext.success();
            else
                callbackContext.error("Bad zip file");
        } catch (Exception e) {
            String errorMessage = "An error occurred while unzipping.";
            callbackContext.error(errorMessage);
            Log.e(LOG_TAG, errorMessage, e);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private void updateProgress(CallbackContext callbackContext, ProgressEvent progress) throws JSONException {
        Log.d("ZIP", "updateProgress() called with: " + "callbackContext = [" + callbackContext + "], progress = [" + progress + "]");
        PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, progress.toJSONObject());
        pluginResult.setKeepCallback(true);
        callbackContext.sendPluginResult(pluginResult);
    }

    private Uri getUriForArg(String arg) {
        CordovaResourceApi resourceApi = webView.getResourceApi();
        Uri tmpTarget = Uri.parse(arg);
        return resourceApi.remapUri(
                tmpTarget.getScheme() != null ? tmpTarget : Uri.fromFile(new File(arg)));
    }

    private static class ProgressEvent {
        private long loaded;
        private long total;
        public long getLoaded() {
            return loaded;
        }
        public void setLoaded(long loaded) {
            this.loaded = loaded;
        }
        public void addLoaded(long add) {
            this.loaded += add;

            // Clamp to max 100%
            if (loaded>total) {
                loaded = total;
            }
        }
        public long getTotal() {
            return total;
        }
        public void setTotal(long total) {
            this.total = total;
        }
        public JSONObject toJSONObject() throws JSONException {
            return new JSONObject(
                    "{loaded:" + loaded +
                    ",total:" + total + "}");
        }

        @Override
        public String toString() {
            return String.format("ProgressEvent{loaded=%d, total=%d, percent=%.0f}", loaded, total, (double) loaded / (double) total * 100);
        }
    }
}
