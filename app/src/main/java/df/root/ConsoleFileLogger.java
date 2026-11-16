package df.root;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Logger de console synchrone persistant sur disque, conçu pour conserver
 * chaque ligne immédiatement via flush() et FileDescriptor.sync() en cas
 * de redémarrage brutal ou kernel panic.
 */
public final class ConsoleFileLogger {

    private static final String TAG = "ConsoleFileLogger";
    private static final String LOG_FILE_NAME = "console_session.log";
    private static final long MERGE_WINDOW_MS = 300_000L; // 5 minutes

    private final File mLogFile;
    private final Object mLock = new Object();
    private final SimpleDateFormat mDateFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private FileOutputStream mFos;

    public ConsoleFileLogger(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            dir = context.getFilesDir();
        }
        if (dir != null && !dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        mLogFile = new File(dir, LOG_FILE_NAME);
    }

    public File getLogFile() {
        return mLogFile;
    }

    /**
     * Démarre ou fusionne une session de log lors d'une action utilisateur.
     * - Si console_session.log existe et a été modifié il y a moins de 5 min (< 300 000 ms),
     *   ouvre en mode append et insère un séparateur horodaté avec le nom de l'action.
     * - Sinon, réinitialise le fichier et démarre un nouveau journal.
     */
    public void startSession(String actionName) {
        synchronized (mLock) {
            closeStreamLocked();

            long now = System.currentTimeMillis();
            boolean exists = mLogFile.exists() && mLogFile.length() > 0;
            long lastModified = exists ? mLogFile.lastModified() : 0L;
            boolean shouldMerge = exists && lastModified > 0L && (now - lastModified) < MERGE_WINDOW_MS;

            try {
                File parent = mLogFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }

                mFos = new FileOutputStream(mLogFile, shouldMerge);
                String timestamp = mDateFormat.format(new Date(now));
                String header;
                if (shouldMerge) {
                    header = String.format(
                            Locale.US,
                            "\n==================== [%s] ACTION: %s (merged) ====================\n",
                            timestamp,
                            actionName
                    );
                } else {
                    header = String.format(
                            Locale.US,
                            "==================== [%s] NEW SESSION: %s ====================\n",
                            timestamp,
                            actionName
                    );
                }
                writeAndSyncLocked(header);
            } catch (IOException e) {
                Log.e(TAG, "Failed to start console log session", e);
                closeStreamLocked();
            }
        }
    }

    /**
     * Écrit immédiatement le message sur disque ligne par ligne en forçant
     * flush() et getFD().sync() à chaque ligne.
     */
    public void writeImmediate(String msg) {
        if (msg == null || msg.isEmpty()) {
            return;
        }
        synchronized (mLock) {
            if (mFos == null) {
                startSession("Auto");
                if (mFos == null) {
                    return;
                }
            }
            try {
                writeAndSyncLocked(msg);
            } catch (IOException e) {
                Log.e(TAG, "Failed to write/sync console log", e);
            }
        }
    }

    public void close() {
        synchronized (mLock) {
            closeStreamLocked();
        }
    }

    private void writeAndSyncLocked(String text) throws IOException {
        if (mFos == null || text == null || text.isEmpty()) {
            return;
        }
        int start = 0;
        int len = text.length();
        while (start < len) {
            int nl = text.indexOf('\n', start);
            int end = (nl >= 0) ? (nl + 1) : len;
            byte[] bytes = text.substring(start, end).getBytes(StandardCharsets.UTF_8);
            mFos.write(bytes);
            mFos.flush();
            mFos.getFD().sync();
            start = end;
        }
    }

    private void closeStreamLocked() {
        if (mFos != null) {
            try {
                mFos.flush();
                mFos.getFD().sync();
            } catch (IOException ignored) {
            }
            try {
                mFos.close();
            } catch (IOException ignored) {
            }
            mFos = null;
        }
    }
}
